# MicroTasking Punch List

Next work session: make onboarding, import, persistence, versioning, and update behavior production-ready.

1. **Empty first install** — DONE (2026-09-03)
   - `selected_categories` now defaults to an empty set (was `{Decluttering, Cleaning}`).
   - Removed the `seedTasks`/`loadSeedTasks(assets/tasks.json)` and `fallbackPromptTasks`
     fallbacks that populated the pool with built-in tasks when none were saved/eligible yet;
     a fresh install now starts with zero categories selected and zero tasks in the pool.
   - The category taxonomy is no longer a hardcoded list either (see item 2) - it's now
     derived from whatever tasks currently exist, so it's also empty until import/creation.

2. **Google Sheet task source and onboarding** — DONE (2026-09-03)
   - Categories are now driven by Google Sheet tab names: `importExternalTasksFromSheet`
     lists tab names by downloading the spreadsheet as `.xlsx` and reading `xl/workbook.xml`
     from the zip (the old GData "worksheets feed" is dead - confirmed via a live request, it
     now 404s - kept as a secondary fallback attempt only), fetches each tab's rows by name
     via the `gviz/tq` CSV export, and uses each tab name as the task category. A tab named
     `README` (case-insensitive) is skipped and never becomes a category. Verified end to end
     against the real production template sheet.
   - Import now runs on a background coroutine (`Dispatchers.IO`) instead of blocking the
     main thread, and Settings shows a real result message ("Imported N tasks across M
     categories", or an actionable failure reason) instead of a fire-and-forget label.
   - Settings/Task Pool/My Tasks no longer offer a fixed hardcoded category list - available
     categories are derived from whatever's currently in the pool, so category options only
     appear once a Sheet has been imported (or a task created locally with a custom category).
   - Real camera-based QR scanning implemented (CameraX + ML Kit barcode scanning):
     Settings' "Scan QR Code" button opens a full-screen live camera scanner
     (`QrScannerScreen`), requesting the `CAMERA` permission on demand. A successful scan
     registers the decoded URL immediately (persisted right away, not gated behind "Save
     Settings") and automatically runs one import. A separate "Update Tasks" button lets the
     user re-sync from the already-registered URL on demand at any later time.

3. **Unreliable updates**
   - Diagnose and fix updates that sometimes or often hang.
   - Verify download, handoff to the Android installer, installation, and launch end to end.
   - Preserve signing identity and data through updates.

4. **Persistent application state**
   - Score must survive closing and reopening the app, process death, reboot, and app updates.
   - Task queue must survive the same events.
   - Imported task Google Sheet source must survive the same events.
   - Uninstalling the app may clear all state.
   - Add regression tests for persistence and restoration.

5. **Define import/synchronization**
   - Document exactly what Import does on first use.
   - Document exactly what Synchronize does on later uses.
   - Define replacement/merge behavior, task identity, changed descriptions, deleted rows, categories, enabled checkboxes, and offline/error behavior.

6. **Bulletproof onboarding, install, and update**
   - Test the complete path from onboarding page to APK download to installation/update.
   - Make failures visible with actionable messages and retry paths.
   - Validate APK URL, release asset existence, signing certificate, version code, and generated QR payload.
   - Add automated checks where practical and document the manual device checklist.
