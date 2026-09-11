# MicroTasking Defects

Field-testing defects found in shipped/branch builds. Feature-scope work lives in [PUNCH_LIST.md](PUNCH_LIST.md); this file is for "it's built but it misbehaves" bugs.

## 1. Manual Resume doesn't deliver a task promptly

**Symptom:** After manually restarting tasking (Pause → Resume / "Resume task queue"), nothing shows up for a while. It can be many minutes before the first task appears.

**Expected:** Manually resuming tasking should produce a task assignment (near-)immediately, subject to the normal queue-size and window rules. A manual Resume is an explicit "give me work now" gesture.

**Diagnosis (2026-09-07):**
- The Resume control (`onBackgroundPromptsChanged(true)` in `MainActivity.kt`) only flips the `background_prompts_enabled` pref. It doesn't trigger a delivery or a re-pace.
- While the app is foregrounded, delivery is driven by the pacing loop in the `LaunchedEffect` at `MainActivity.kt:338-373`. That loop sits in `delay(delayMs)` where `delayMs` came from `TaskDelivery.computeNextDelayMillis` — a semi-random paced interval derived from prompts-per-day and time remaining in the active window, routinely minutes long.
- The `LaunchedEffect` keys are `promptTasks, savedStartHour, savedEndHour, savedPromptsPerDay`. The Resume toggle is not a key, so toggling it neither restarts the loop nor recomputes the delay — the loop just keeps sleeping out the interval it already committed to.
- Background path has the same shape: the `AlarmManager` alarm was armed with the pre-Resume delay, so a Resume while closed also waits out the old interval.

**Possible fix (per Vern):** On Resume, run one delivery slot immediately (`TaskDelivery.deliverOrConsumeSlot`) and then recompute pacing — e.g. make `backgroundPromptsRunning` a `LaunchedEffect` key (or cancel/restart the loop), and re-arm the background alarm with a near-zero delay. Respect `maxQueueSize` and the active-window gating so Resume can't overfill the queue or force delivery where it shouldn't.

**Status:** Fixed on `tasking-revisited` (2026-09-10) as part of the dispatch-model rework. Pause/Resume now goes through `MainActivity.setBackgroundPrompts`, which persists the flag and then immediately runs `TaskDelivery.tick` — that re-paces and dispatches a task right away. The foreground loop also polls every second against a persisted `next_dispatch_epoch_ms` rather than sleeping out one long committed interval, and the on-screen countdown can be tapped to force a task immediately. Background alarm is re-armed from the same persisted epoch.

## 2. Countdown expiry never dispatches into a full queue, so tasks never auto-fail

**Symptom:** When the queue is full, the on-screen countdown runs down to zero and resets without anything being added to the queue. A task the user never acts on just sits there — it's never aged out / timed out. The only failure path left is a manual Abandon.

**Expected:** Every countdown expiry should dispatch a task. When the queue is already full, the new arrival pushes the oldest actionable task off the top, and that eviction counts as a timeout (a real automatic failure, breaking the streaks). This is the intended replacement for the old window-close abandon.

**Diagnosis (2026-09-10):** The `tasking-revisited` dispatch rework gated automatic dispatch on `underHalf` (`activeCount * 2 < maxQueueSize`) in `TaskDelivery.tick`. At or above half full — including completely full — the tick skipped, so the eviction/timeout branch (guarded by `activeCount >= maxQueueSize`) was unreachable except via a forced tap in rapid-testing mode.

**Status:** Fixed on `tasking-revisited` (2026-09-10). Automatic ticks now always dispatch; `underHalf` is gone. A full queue evicts-and-times-out the oldest actionable task on the regular cadence. A manual force-tap into a full queue in normal mode stays a no-op (only the automatic cadence times things out); in rapid-testing mode the tap still evicts. `RAPID_TESTING_THRESHOLD` also lowered 1000 → 500.

## 3. Duplicate task in the queue crashes the app on the task screen (crash-loop)

**Symptom:** "MicroTasking keeps closing" / "this app has a bug" the moment the task screen tries to render — repeatedly, so the app is unusable until its data is cleared. Seen on build 61 and much more frequently on build 63 (Galaxy S23, Android 16).

**Diagnosis (2026-09-10, from device logcat):**
```
FATAL EXCEPTION: main  (com.microtasking.app)
java.lang.IllegalArgumentException: Key "external-Decluttering-Empty one trash/recycling bin
that isn't empty yet." was already used. If you are using LazyColumn/Row please make sure you
provide a unique key for each item.
  at androidx.compose.foundation.lazy ... (task-queue LazyColumn, MainActivity.kt)
```
The persisted `task_queue` held two entries for the *same* task id. The task screen's `LazyColumn` keys items by `entry.task.id`, and Compose throws hard on a duplicate key — on every launch, before any pacing tick could repair the queue, so it crash-loops.

Root cause: `TaskDelivery.tick` picked the next task with `chooseWeightedTask(previousTaskId = <last actionable id>)`, which only avoids the single most-recent task, not everything already queued. With ≥2 tasks queued it could re-pick an earlier one and append it. Defect 2's "every tick dispatches" fix made this hit constantly (before, dispatch only happened at ≤1 actionable, where a collision was almost impossible). The user's leftmost category ("Decluttering", ~2× weight) collided first.

**Status:** Fixed on `tasking-revisited` (2026-09-10), three layers:
- `TaskDelivery.tick` now excludes every still-queued task id from the candidate pool before `chooseWeightedTask` (same guard `MainActivity`'s Substitute already used), and caps effective queue capacity at the eligible-pool size so a small pool can't force a repeat.
- `readTaskQueue` collapses duplicate task ids on load (`distinctBy { it.task.id }`, first wins) — repairs already-corrupt persisted state from older builds, which is what breaks the crash-loop for anyone already stuck.
- The task-queue `LazyColumn` key is now `"${id}#${index}"` so a duplicate can never crash the screen again.

Verified on-device (build `v0.1.7-64`, Galaxy S23 / Android 16): the previously crash-looping install — whose persisted `task_queue` held the duplicate — now cold-launches cleanly (0 crashes over repeated launches), and `tick` grew the queue to full with three distinct tasks.
