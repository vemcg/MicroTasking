# MicroTasking Punch List

Next work session: make onboarding, import, persistence, versioning, and update behavior production-ready.

1. **Unreliable updates**
   - Diagnose and fix updates that sometimes or often hang.
   - Verify download, handoff to the Android installer, installation, and launch end to end.
   - Preserve signing identity and data through updates.

2. **Persistent application state**
   - Score must survive closing and reopening the app, process death, reboot, and app updates.
   - Replace the "tasks completed this session" counter with a **streak**: count of
     consecutive tasks completed since the last time a task was not completed (abandoned or
     stack-evicted). A failed/abandoned task resets the streak to 0.
   - Task queue must survive the same events.
   - Imported task Google Sheet source must survive the same events.
   - Uninstalling the app may clear all state.
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

5. **Tasking logic**
   - Done (foreground only): prompts per day, active window, and remaining window time now drive real pacing — tasks are added to the queue one at a time at semi-random intervals computed from those settings, recalculated whenever settings are saved. See `TaskScheduling.kt`.
   - Done: rapid-test capability removed (checkbox, 15s loop, and the `rapid_test_mode` pref) — the About & Testing section is now just About.
   - Done: the "tasks completed this session" count is now a streak ("N in a row" on the Score screen, celebratory and hidden at 0; same phrasing at the bottom of the task queue screen) — increments on completion, resets to 0 on abandon, reject, or stack-eviction. Not yet persisted across restarts; that's still item 2's job.
   - Done: Score screen shows longest streak instead of a completion percentage, under Today/This week/This month/All time — but it's one session-only number reused under all four labels, since there's no persisted history to break it out by period yet (needs item 2).
   - Done: task selection is now two-stage — the active category is picked with a linear weighting (first active category, in tab order, is twice as likely as the last; uniform if only one active category), then a task within that category is picked uniformly at random among its enabled tasks. Decline counts (`savedDeclineCounts`) are still tracked/persisted but no longer influence selection odds. See `chooseWeightedTask` in `TaskPool.kt`.
   - Done: pausing ("Pause task queue") still consumes scheduled delivery slots in the background loop (so the day's quota keeps counting down) — it just skips adding the task to the visible queue. Prevents a burst of everything that "should" have arrived landing all at once when you resume.
   - Done: the task queue screen is reachable (and stays put) even with zero active tasks — shows "Task queue: 0 active of N" instead of being forced onto the Score screen.
   - Done: a notification (sound/vibration via the existing high-importance channel) fires whenever a task is actually added to the queue by the pacing loop, not just on the old fixed 15-minute background beat.
   - Done: the Score screen always auto-returns to the task queue after 5 seconds, regardless of whether the queue is empty (previously it only auto-returned when there were queued tasks, so an empty queue could leave you stuck on Score indefinitely).
   - Remaining, deferred by request: move task delivery into a real background service (foreground service or WorkManager) instead of a Compose `LaunchedEffect`, which dies when the activity does. Goal: closing the app must not stop the queue from filling or reset the streak — reopening should show whatever accumulated while it was closed. This also means separating the producer (background pacing that adds tasks to the queue) from the consumer (foreground UI that removes tasks and shows score) — they should not need to run in the same process lifetime. Needs `taskQueue`/`streak`/`longestStreak` persisted (ties into item 2), not just `deliveredInWindow`.
   - Remaining: wire background delivery (`PromptScheduler`/`PromptAlarmReceiver`, still a fixed 15-minute beat) to the same window/quota pacing logic as the foreground loop — likely subsumed by the background-service work above.
   - Remaining: at the end of the active window, time out anything still sitting in the queue and reset the streak to 0 if anything was timed out. Start hour 0 and end hour 24 (or any start hour equal to end hour) mean the window never ends, so nothing is ever timed out.
