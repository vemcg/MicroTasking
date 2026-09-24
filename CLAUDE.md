# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

MicroTasking is an Android app (Kotlin + Jetpack Compose) that periodically and semi-randomly
prompts the user, within a configurable daily time window, to do a short task (5-15 min) pulled
from a pool they customize via a shared Google Sheet. It has a companion app, **ActiveTasks**
(sibling repo `../ActiveTasks`), a traditional to-do list sharing the same spreadsheet — see
PUNCH_LIST.md item 8 for the planned hand-off between the two.

## Commands

Requires an Android SDK; `local.properties` (gitignored) must contain `sdk.dir=<path>`.

- Build debug APK: `./gradlew assembleDebug`
- Run all unit tests: `./gradlew testDebugUnitTest`
- Run one test class: `./gradlew testDebugUnitTest --tests "com.microtasking.app.TaskDeliveryTest"`
- Run one test method: `./gradlew testDebugUnitTest --tests "com.microtasking.app.TaskDeliveryTest.tick_dispatchesOnce_whenInsideWindow"`
- Fast compile check without running tests: `./gradlew compileDebugKotlin`
- Google Sheet *template* Apps Script tooling (unrelated to the Android app's build; `npm`/`clasp`,
  see `.clasp.json`): `npm run push:sheet` / `pull:sheet` / `open:sheet`

Manually trigger a build for a non-`main` branch (pushes to other branches do **not**
auto-trigger the release workflow): `gh workflow run "Build & release APK" --ref <branch>`.

## Build tracking (user-requested convention, 2026-09-24)

- **Last built version:** `v0.2.0-79` (branch `sheet-surrogate-keys`, built 2026-09-24). At the
  start of every session, note this as the current known state before doing anything else. After
  triggering a build and confirming it went live (`gh run list` / the new GitHub Release), update
  this line to the new version/branch/date — don't leave it stale once a newer build exists.
- **Copyright-comment convention, from 2026-09-24 forward:** when editing a file that already
  carries this project's own `Copyright (c) <year> Vern McGeorge` header (not the Gradle wrapper's
  or `LICENSE`'s), add or update a line directly under it reading `Updated <date>, after version
  <build version> <build branch> <build timestamp>` where `<build version>`,`<build branch>`, and `<build timestamp>` are whatever "Last built version" above says *at the time of
  the edit* (the most recent build that had already shipped, not one triggered by this edit, which
  hasn't happened yet). `<date>` is `YYYY-MM-DD`, matching this repo's existing dating convention
  in `PUNCH_LIST.md`/`SPEC.md`. Applies going forward only, to files actually touched for some
  other reason - not a retroactive pass over every file that currently carries the header.

## Architecture

Almost everything Compose/UI-related lives in one large file, `MainActivity.kt` (the Activity
plus every screen: settings, task pool editor, my-tasks, QR scanner, score, task prompt). Logic
that needs to run in JVM unit tests without a device is split out:

- **`TaskPool.kt`** — data model (`ManagedTask`, `TaskStackEntry`, `TaskLifecycleState`), Sheet
  re-sync merge logic (`mergeImportedManagedTasks` — the sheet's tab names are authoritative for
  the category list; in-app flags like `neverSuggest`/`temporarilyUnavailable` survive a re-sync),
  weighted task selection (`chooseWeightedTask` — leftmost active category ~2x the rightmost,
  linear between), and the JSON read/write helpers for everything persisted.
- **`TaskDelivery.kt`** — the single producer of queued tasks (`TaskDelivery.tick`), shared by the
  foreground pacing loop (a 1s poll in `MainActivity`'s `LaunchedEffect`) and the background
  `AlarmManager` receiver, so both read/write the exact same persisted state and pick up where the
  other left off. `tick()` dispatches on every non-blocked call *unless* a still-future
  `next_dispatch_epoch_ms` is already armed (that gate exists specifically to stop redundant
  dispatch on app-reopen/Resume/vacation-toggle — see the doc comment above the check and
  DEFECTS.md item 5 before changing it).
- **`TaskScheduling.kt`** — pure functions only: active-window math
  (`isWithinActiveWindow`/`millisUntilWindowOpens`/`Closes`), the fixed dispatch interval
  (`fixedDispatchIntervalMillis`), and the rapid-testing-mode threshold/floor.
- **`PromptAlarmReceiver.kt`** — background `AlarmManager` `BroadcastReceiver` + notification
  (`PromptNotifier`) + alarm arming (`PromptScheduler`, which sets a single wake-up at the next
  computed epoch — not a polling loop; the 1s foreground loop only runs while the app is open).
- **`DiagnosticLog.kt`** — plain-text, 48h-retained, 2MB-capped append-only operational log
  (task lifecycle, app lifecycle, settings changes), separate from crash reporting (not yet
  built, PUNCH_LIST.md item 7). Pull via `adb exec-out run-as com.microtasking.app cat files/diagnostic.log`.
- **`VersionInfo.kt`** — component-wise version parsing/comparison, to avoid lexical
  string-compare bugs (`"10"` sorting before `"2"`).

**Google Sheet import** (in `MainActivity.kt`): tab names are fetched via the `.xlsx` export's
zipped `workbook.xml` (legacy GData worksheets feed as fallback), then each tab's rows via the
`gviz` CSV export addressed by tab name. Column A of every tab is always the enabled checkbox;
`description`/`link` columns are matched by header text, not position, so column reordering is
safe. The Apps Script template tooling (`scripts/populate_google_sheet.js`, pushed via `clasp`,
`package.json`/`.clasp.json`) provisions a **new** user's fresh spreadsheet (checkbox-on-type,
tab auto-headering, README) — unrelated to reading an already-set-up sheet.

**Versioning**: `major.minor.feature-build`, e.g. `0.1.8-2`. `versionBase` is bumped by hand in
`app/build.gradle.kts`; `build` is `github.run_number` (monotonic, unique repo-wide). Every
branch's build gets its own permanent tag/release/asset `vBASE-N`; only non-`main` (dev) releases
get pruned on each new build so GitHub keeps just the newest one per branch.

**Testing**: Robolectric lets JVM unit tests exercise a real `Context`/`SharedPreferences` without
a device/emulator (see `TaskDeliveryTest`) — in particular, `tick`/`reconcile`/`nextDispatchEpoch`
all take an optional `now: LocalDateTime` parameter so tests can control time deterministically
instead of racing the real clock.

**Docs convention** — read before starting non-trivial work: `SPEC.md` (intended behavior,
including explicitly-called-out "not implemented" sections), `PUNCH_LIST.md` (scoped-but-not-started
feature work), `DEFECTS.md` (numbered bug write-ups: symptom → diagnosis → fix → verification;
referenced by item number elsewhere, e.g. in code comments and commit messages — keep that
numbering stable).

**Release pipeline** (`.github/workflows/release-apk.yml`): builds a debug APK signed with the
checked-in debug keystore (not a secret by design, keeps CI/local signing consistent so sideloads
install over prior versions), publishes a GitHub Release tagged `vBASE-N`, generates an
install/onboarding page with QR code(s) via `scripts/generate_install_page.py`, deploys it to
GitHub Pages. A non-`main` branch's push does not trigger this automatically (see Commands above).
