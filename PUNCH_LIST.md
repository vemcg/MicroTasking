# MicroTasking Punch List

Next work session: make onboarding, import, persistence, versioning, and update behavior production-ready.

1. **Unreliable updates**
   - Diagnose and fix updates that sometimes or often hang.
   - Verify download, handoff to the Android installer, installation, and launch end to end.
   - Preserve signing identity and data through updates.

2. **Persistent application state**
   - Done: task queue, streak, and longest streak now persist to SharedPreferences (`task_queue`/`streak`/`longest_streak`) and survive closing/reopening the app - see item 5. Not yet verified across process death, reboot, or app updates specifically (only tested via normal close/reopen and `gradlew assembleDebug`, not on a real device this round).
   - Remaining: per-period (today/this week/this month/all-time) longest-streak stats - currently one session-derived number is shown under all four labels, since there's no persisted per-completion history to break it out by date range. Would need a real log of completions (timestamp + duration), not just aggregate counters - `TaskStackEntry.completedAtEpochMs` now captures the timestamp half of that, unused so far.
   - Imported task Google Sheet source already survives (via `managed_tasks`/`external_sheet_url`, pre-existing).
   - Uninstalling the app clears all state (SharedPreferences, pre-existing/inherent).
   - Add regression tests for persistence and restoration.

3. **Define import/synchronization**
   - Document exactly what Import does on first use.
   - Document exactly what Synchronize does on later uses.
   - Define replacement/merge behavior, task identity, changed descriptions, deleted rows, categories, enabled checkboxes, and offline/error behavior.

4. **Bulletproof onboarding, install, and update**
   - Test the complete path from onboarding page to APK download to installation/update.
   - Make failures visible with actionable messages and retry paths.
   - Validate APK URL, release asset existence, signing certificate, version code, and generated QR payload.
   - Add automated checks where practical and document the manual device checklist.
   - Done: the install page shows two side-by-side QR codes when built from a non-main branch — one for the latest main release, one for that branch's just-built release — and just the single main QR on ordinary main builds. "Find latest main release" tells main's releases apart from branch releases by *release title* (`MicroTasking vX.Y.Z-N` for main vs. `MicroTasking vX.Y.Z-N (branch)` for everything else), not by tag — tags are always the plain `vX.Y.Z-N` shape regardless of branch, since `github.run_number` is unique repo-wide so no two branches' tags can ever collide anyway. See the "Determine timestamped version/tag" and "Find latest main release" steps in `release-apk.yml`.
   - Done: neither the QR carve-out label, the download button text, nor the actual downloaded filename/tag include the branch name anymore — all just show/use the short `vX.Y.Z-N` (e.g. `MicroTasking-v0.1.7-40.apk`). Branch is still visible in the GitHub release title and in the app's own About section, just not in anything that ends up on the phone. Note: repeat downloads of different versions over time will still pile up as separate files in the phone's Downloads — Android's download manager doesn't overwrite/dedupe by itself, and that's not something the install page can control from its end.

5. **Tasking logic**
   - Done: prompts per day, active window, and remaining window time drive real pacing — tasks are added one at a time at semi-random intervals, recalculated whenever settings are saved. A single shared producer, `TaskDelivery.kt`, backs both the foreground Compose loop and the background `AlarmManager` receiver — both read/write the same persisted state (`task_queue`/`streak`/`longest_streak`/`prompts_delivered_in_window`/`prompts_count_epoch_day`/`prompts_window_start_epoch`), so whichever is active (foreground while open, the alarm chain while closed) picks up exactly where the other left off. `MainActivity.onStart`/`onStop` hand off between them: opening cancels the alarm (the live loop takes over), closing arms it with a freshly-computed delay. A 30-second floor on the computed delay (`nextPromptDelayMillis`) prevents a pacing edge case (little window time left, several prompts still due) from firing a tight, ANR-causing burst of deliveries.
   - Done: closing the app no longer loses progress — task queue, streak, and longest streak persist and are restored on next launch.
   - Done: `TaskDelivery.reconcileState` handles window-close, day-change, and window-open as three independent checks against wall-clock time, not chained off each other:
     - Window close (now outside the active window): abandons anything still actionable in the queue — same ABANDONED state and streak reset as a manual Abandon — scored exactly like the user abandoned them. A delivery tick is deliberately scheduled right at close time (even with nothing left to deliver) so this runs promptly rather than sitting stale.
     - Day change (calendar day rolled over): resets the daily delivery count *and* the streak — a streak is a daily thing, so a new day always starts it fresh, including for an always-active window (start hour == end hour) that never technically "closes" and so never hits the abandon-driven reset above.
     - Window open (a new window occurrence has begun): un-pauses if paused. That's the only thing it does — if the queue somehow isn't empty by this point (it should always be, since close already cleared it), open leaves it alone rather than abandoning/scoring it again.
   - Done: pausing ("Pause task queue" — on the Score screen, the task queue screen, and in Settings) leaves the current queue exactly as it is; the only effect is that no new tasks get added. Delivery slots still tick down silently in the background while paused, so resuming picks the pace back up at the same rate rather than dumping a catch-up burst. A cold app launch always resumes regardless of prior pause state (pausing only lasts for the current running session) and also runs `TaskDelivery.reconcile` before the UI reads persisted state, so a day/window boundary crossed while closed shows up immediately instead of waiting for the first delivery tick.
   - Done: reopening the app while it's already running (icon tap or the delivery notification) brings the existing instance to the front instead of stacking a new one (`launchMode="singleTask"`).
   - Caveat: verified via `gradlew compileDebugKotlin`/`assembleDebug` plus several rounds of on-device testing (which is how the ANR burst and the always-active-window streak bug above were found) — keep testing close/reopen/pause/window-boundary/background-delivery, there could still be more edge cases like those.
   - Deferred by request: per-completion history for real per-period (today/this week/this month/all-time) stats, and syncing any of this back to the Google Sheet — see item 2's remaining bullet.
