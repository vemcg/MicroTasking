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

## 4. Task dispatched before the active window opens; Pause doesn't stick; countdown ignores Pause

**Symptom (reported by Vern, 2026-09-11):** Active window 10:00 AM–6:00 PM. At 9:30 AM the task queue silently grew from 1 to 3. Pausing the task queue doesn't stay paused — something other than a manual Resume or the window opening keeps clearing it. Pausing also doesn't stop the on-screen "Next task in …" countdown; it kept counting down, and reaching zero appeared to be what un-paused things.

**Diagnosis (2026-09-11):** Two interacting root causes, both in `background_prompts_enabled` bookkeeping:
- `MainActivity.onCreate` unconditionally forced `background_prompts_enabled = true` on every cold launch ("pausing only lasts for the current running session" — a deliberate call made during the 2026-09-10 rework, now reversed at Vern's request). Android can and does kill the app between glances, so a relaunch silently undid a manual pause. Whichever moment the user next opened the app, `TaskDelivery.reconcile` ran *after* that forced write, and since reconcile only touches the flag at an actual window open/close transition, the forced `true` fell straight through untouched on any launch that didn't land exactly on a boundary — e.g. 9:30 AM, mid-way through the still-closed window.
- `TaskDelivery.tick`'s automatic dispatch path never checked the active window directly — only the `background_prompts_enabled` flag, which is supposed to track the window via edge-triggered transitions in `reconcileState`, but had no independent backstop. So the moment the flag above read `true` at 9:30 AM (pre-window), the very next tick dispatched immediately, and outside-window dispatch was generally only prevented as a side effect of the flag happening to be correct.
- `SettingsScreen`'s Save button had the same forced-`true` problem on every settings edit, not just first-time setup.
- Net effect on the countdown: a pause taken while outside the window was indistinguishable from the pre-existing "auto-paused because we're outside the window" state, so the countdown showed "Window opens in …" instead of "Paused," and the window-open transition (which is supposed to be one of only two legitimate un-pause triggers, the other being a manual Resume) fired regardless of whether the pause was automatic or an explicit user action — which is correct by Vern's stated rule ("only me or 10:00 AM should unpause it") for a pause taken *during* a still-open window, but wasn't reachable cleanly because of the two bugs above.

**Status:** Fixed on `tasking-revisited` (2026-09-11):
- Removed the forced `background_prompts_enabled = true` from `MainActivity.onCreate`; a relaunch now just runs `TaskDelivery.reconcile`, whose existing window-open/close transition logic is the sole automatic trigger.
- `TaskDelivery.tick` now hard-gates its non-forced dispatch path on `isWithinActiveWindow` directly, not just the flag — an automatic tick can never dispatch outside the window regardless of what set the flag. A forced/manual tap still bypasses this (existing "test outside the window" override).
- `SettingsScreen`'s Save no longer forces the flag on except for first-time setup completion.
- Added a **vacation mode** checkbox (Settings → Prompting Schedule): a separate `vacation_mode` flag, checked first in `TaskDelivery.tick` and `nextDispatchEpoch`, that blocks dispatch and alarm-arming unconditionally — including forced taps — until manually unchecked. Nothing else (not the window, not a relaunch) can clear it.
- Verified via `gradlew compileDebugKotlin` / `testDebugUnitTest` / `assembleDebug`. On-device verification still recommended for a first real-world pass, but this is no longer untestable in CI: added Robolectric (`app/build.gradle.kts`) so JVM unit tests can now exercise a real `Context`/`SharedPreferences`, and `TaskDeliveryTest.kt` covers this defect directly - regression tests for "no automatic dispatch before the window opens even if the enabled flag is wrongly true," "a manual pause clears the countdown and survives outside the window," "vacation mode blocks even a forced tap," and "reconcile never itself re-enables a manual pause." `TaskDelivery.tick`/`reconcile`/`nextDispatchEpoch` gained an optional `now: LocalDateTime` parameter (defaults to the real clock) to make this possible - no behavior change for real callers, all of which still call with the default.
  - **Gotcha for next time:** this dev machine's only installed JDK is 25 (see `gradle.properties`), and Robolectric 4.13's bundled ASM can't parse JDK 25's class file version ("Unsupported class file major version 69"). Bumped to Robolectric 4.17 (latest as of 2026-09-11), which resolved it, plus the `--add-opens` JVM flags Robolectric's docs require for JDK 17+ (`testOptions.unitTests.all` in `app/build.gradle.kts`). If Robolectric tests ever start failing with that same "Unsupported class file major version" error again after a JDK bump, check for a newer Robolectric release before assuming the app code broke.
  - **Follow-up hardening (2026-09-11, same day):** audited every place a "stupid" persisted setting (zero/negative/out-of-range window hours, queue size, or prompts-per-day) could reach live scheduling math. The Settings Save button already clamped these before persisting, but `TaskDelivery.loadSettings` - the one chokepoint every dispatch/pacing computation reads through - didn't re-clamp, so a value saved by an older build (before those Save-button clamps existed) or poked directly into SharedPreferences (e.g. over adb) could still reach `java.time.LocalTime.of()` (throws on a negative hour) or `List.take()` (throws on a negative count). Now clamped again at `loadSettings`: hours to `0..24`, prompts-per-day to `>= 0`, queue size to `>= 1`. Same fix applied to `MainActivity`'s own direct prefs reads that feed `makeTaskStack`/`isWithinActiveWindow`. Separately found and fixed a real (not just theoretical) crash: tapping **Substitute** on an already-queued task after the eligible task pool went empty (e.g. every category got unchecked while a task was still on-screen) called `chooseWeightedTask` on an empty list, which throws `NoSuchElementException` on `.last()` - now a no-op instead. Regression-covered in `TaskDeliveryTest.kt` (zero/negative queue size, negative prompts-per-day, out-of-range and blank window-hour strings - none of these should throw).
