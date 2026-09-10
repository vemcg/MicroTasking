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
