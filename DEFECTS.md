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

**Status:** Recorded, not yet fixed.
