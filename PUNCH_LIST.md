# MicroTasking Punch List

Next work session: make onboarding, import, persistence, versioning, and update behavior production-ready.

1. **Empty first install**
   - A fresh installation starts with no categories and no tasks.
   - Do not seed built-in tasks or categories until the user imports or creates them.

2. **Google Sheet task source and onboarding**
   - Tasks are maintained on the computer in a Google Sheet.
   - The user pastes the Sheet URL into the onboarding page.
   - The onboarding page generates a QR code from that URL.
   - The phone uses the QR flow to import the tasks.
   - Do not require pasting the Sheet URL into the app Settings form.
   - Do not require manually entering the URL on the phone.
   - Clarify the intended scan mechanism before implementation: the phone must receive the generated Sheet URL/task-source payload, but the current wording says not to have the phone read a QR code with the phone.

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

6. **Redefine versioning** — DONE (2026-09-03)
   - Human-facing version shown in the app is short: `v0.1.7-11` (base app version + unpadded
     build/run number). Full provenance (`yyyymmdd.HHmmss` UTC build timestamp + short Git
     commit hash) is generated at build time but only surfaced in Settings > About & Testing,
     not embedded in the main version string.
   - `versionCode` stays a single monotonically increasing integer (UTC epoch seconds at build
     time), independent of the display string, so Android's update check is always numeric.
   - Added `VersionInfo.kt`: parses "major.minor.patch-buildNumber" and compares component by
     component (numeric build-number compare), so "10" never sorts below "2" the way it would
     under lexical string comparison.
   - CI (`release-apk.yml`) now passes `buildVersionBase`/`buildNumber`/`buildTimestamp`/
     `buildGitSha` to Gradle; release tags are the short `v0.1.7-<run_number>` form.

7. **Bulletproof onboarding, install, and update**
   - Test the complete path from onboarding page to APK download to installation/update.
   - Make failures visible with actionable messages and retry paths.
   - Validate APK URL, release asset existence, signing certificate, version code, and generated QR payload.
   - Add automated checks where practical and document the manual device checklist.
