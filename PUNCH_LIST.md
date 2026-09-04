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

5. **Tasking logic**
   - Done (foreground only): prompts per day, active window, and remaining window time now drive real pacing — tasks are added to the queue one at a time at semi-random intervals computed from those settings, recalculated whenever settings are saved. See `TaskScheduling.kt`.
   - Done: rapid-test capability removed (checkbox, 15s loop, and the `rapid_test_mode` pref) — the About & Testing section is now just About.
   - Done: the "tasks completed this session" count is now a streak (`Streak: N`) — increments on completion, resets to 0 on abandon, reject, or stack-eviction. Not yet persisted across restarts; that's still item 2's job.
   - Remaining: wire background delivery (`PromptScheduler`/`PromptAlarmReceiver`, still a fixed 15-minute beat) to the same window/quota pacing logic as the foreground loop.
   - Remaining: at the end of the active window, time out anything still sitting in the queue and reset the streak to 0 if anything was timed out. Start hour 0 and end hour 24 (or any start hour equal to end hour) mean the window never ends, so nothing is ever timed out.
