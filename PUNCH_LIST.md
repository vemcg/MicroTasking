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
   - Done: the install page shows two side-by-side QR codes when built from a non-main branch — one for the latest main release, one for that branch's just-built release — and just the single main QR on ordinary main builds. Non-main release tags now get the branch name appended (e.g. `v0.1.7-30-tasking-logic`) so "find the latest main release" can tell them apart by tag pattern alone; see the "Find latest main release" step in `release-apk.yml` and `--branch`/`--main-url`/`--main-version` in `scripts/generate_install_page.py`.
   - Done: the branch name no longer appears in the QR carve-out label or the download button text (it was already shown once via the heading above each QR, and made the carve-out font shrink to fit) — both now show just the short `vX.Y.Z-N`. The actual download link/asset filename is untouched, still the full exact tag. Full branch/commit/timestamp info still shows in the app's own About section.

5. **Tasking logic**
   - Done: prompts per day, active window, and remaining window time drive real pacing — tasks are added one at a time at semi-random intervals, recalculated whenever settings are saved. Foreground (Compose loop) and background (AlarmManager) now share one producer implementation, `TaskDelivery.deliverOrConsumeSlot`/`computeNextDelayMillis` in `TaskDelivery.kt` - both read/write the same persisted `task_queue`/`streak`/`longest_streak`/`prompts_delivered_in_window`/`prompts_window_start_epoch` keys, so whichever is active (foreground while open, the alarm chain while closed) picks up right where the other left off. `MainActivity.onStart`/`onStop` hand off between them: opening cancels the alarm (the live loop takes over), closing arms it with a freshly-computed delay.
   - Done: closing the app no longer loses progress — task queue, streak, and longest streak persist and are restored on next launch (item 2).
   - Done: pausing ("Pause task queue", now on both the Score screen and the task queue screen, and in Settings) leaves the current queue exactly as it is — the only effect is that no new tasks get added while paused (delivery slots still tick down silently in the background so resuming doesn't dump a backlog). Resuming picks the pace back up at the same rate as before, nothing is fast-forwarded to catch up.
   - Done: hitting the end of the active window is a stronger, distinct event from pausing — anything still sitting in the queue is abandoned (not just silently dropped: each entry transitions to the same ABANDONED state a manual Abandon produces) and the streak resets to 0, i.e. it's scored exactly like the user abandoned them. Runs inside `deliverOrConsumeSlot` on the next tick after the window rolls over, so it applies whether that tick came from the foreground loop or the background alarm, and whether or not prompts were paused at the time.
   - Done: reopening the app while it's already running (tapping the icon, or the delivery notification) brings the existing instance to the front instead of stacking a new one (`launchMode="singleTask"`).
   - Fixed: on-device testing found a real bug — opening the app very late in the active window (little time left, several prompts still due) made `remainingWindowMillis / remainingPrompts` round toward zero, producing near-instant delivery delays. That fired a tight loop of deliveries (each doing disk I/O and posting a notification) fast enough to ANR/crash the app. `nextPromptDelayMillis` now enforces a 30-second floor and returns null (skip until the next window) rather than deliver anything if less than 30 seconds remain in the window.
   - Caveat: still only verified via `gradlew compileDebugKotlin`/`assembleDebug` plus one round of on-device testing (which is how the bug above was found) - keep testing close/reopen/pause/background-delivery before trusting it fully.
   - Deferred by request: per-completion history for real per-period (today/this week/this month/all-time) stats, and syncing any of this back to the Google Sheet - see item 2's remaining bullet.
