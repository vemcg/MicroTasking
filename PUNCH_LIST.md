# MicroTasking Punch List

Next work session: make onboarding, import, the spreadsheet template, persistence, versioning, and update behavior production-ready.

1. **Spreadsheet improvements** — *merged to `main` 2026-09-10 (branch `spreadsheet-improvements`, 21 commits). Spreadsheet behaviors verified by Vern; the manual `setupMicroTaskingSheet` run and the `onEdit` / new-tab / on-device import+re-sync checks all passed. The new stable release's install page points at Sheet `1YZNQxZlzj8Xj4Bya2v8YEiq01n6jseD3JZSr7BlThJo`. App version stays 0.1.7; template version lives in README row 2.*
   - Done: **auto-manage each row from its description cell.** `onEdit` in `scripts/populate_google_sheet.js`: typing a description into column B on a row with no checkbox adds one (checked); clearing a row's description (trimmed empty) **deletes the whole row** (checkbox + description + link together), bottom-up for multi-row clears so the table stays gap-free. Skips the README tab; A1 edits still fan out to every row. (`addRowCheckbox_` is add-only now; removal is row deletion.)
   - Done: **a newly added tab self-headers.** `onEdit` can't see a sheet insertion, so `setupMicroTaskingSheet` installs an `onChange` trigger (`onGridChange_`); on `INSERT_GRID` it writes the standard header row (A1 master checkbox, `Description`/`Link`, bold+centered) and column widths (40/500/250) on any non-README tab missing them. Header/width logic factored into `applyCategoryTabHeader_`. **Both live triggers are now installable** (`onGridChange_` = onChange, `onSheetEdit_` = onEdit) — there is deliberately no simple `onEdit`, because a simple trigger fires *unreliably in a File→Make-a-copy copy* (which is the bug the user hit) and would double-run alongside an installable one (dangerous with the row-delete logic). `ensureTriggers_` installs both idempotently. An `onOpen` adds a **MicroTasking menu**: "Repair headers & triggers" (`repairSheet_` — non-destructive: headers any bare tab + reinstalls triggers) and "Rebuild everything from template" (full `setupMicroTaskingSheet`). Every copy must run one of those once; after that, tab-add and checkbox-on-type work in that copy. `setupMicroTaskingSheet` also headers any user-added tab.
   - Done: **header formatting.** B1/C1 read `Description`/`Link`, and A1:C1 are bold + horizontally centered — in both the Apps Script and the `.xlsx` builder (`scripts/generate_sheet_template.py`, which was also de-duplicated from a bad merge). Safe for import: `parseExternalTaskCsv` lowercases headers before matching.
   - Done: **version identity.** `TEMPLATE_VERSION` constant in both scripts (keep in sync with `buildVersionBase`). Both write `Template version: <version> (…date…)` as README row 2; the Sheet's own title stays plain `MicroTasking Task Pool Template`. README section headings are now bolded by content-match (rows ending in `:`) instead of hardcoded row numbers, which had drifted wrong.
   - Done: **README readability + content.** Column A is a fixed 700px (`.xlsx`: width 95) with wrap on, so every line shows in full instead of `autoResizeColumn` blowing it out. `1. CATEGORIES (TABS):` is split onto its own bold line (was jammed onto the first sentence); blank line added under `HOW TO USE THIS SPREADSHEET:`. New content: tab order → weighting ("leftmost enabled category ~2× the rightmost, linear between" — matches `chooseWeightedTask` in `TaskPool.kt`), and a note in section 2 that task-row order within a tab doesn't affect assignment odds. README text is duplicated between `populate_google_sheet.js` and `generate_sheet_template.py` — keep them in sync.
   - Done: **clasp deploy path.** `scripts/populate_google_sheet.js` is pushed to the template Sheet's bound Apps Script project with `npm run push:sheet` (`clasp push --force`); `.clasp.json` at repo root, `package.json` pins `@google/clasp`. No more pasting the file into the editor by hand — but `setupMicroTaskingSheet` still has to be Run once from the editor after a push (`clasp run` not set up; needs a GCP project + scoped manifest).
   - Done: **onboarding points at the current template Sheet** `1YZNQxZlzj8Xj4Bya2v8YEiq01n6jseD3JZSr7BlThJo` — `--template-url` in `release-apk.yml` + the default in `generate_install_page.py`.
   - Done: **import is positional on column A and imports everything.** `parseExternalTaskCsv` no longer looks for an "enabled"/"checkbox" header — column A is always the toggle. Every row with a description imports; an unchecked column A means `enabled = false` (stored, shown on the Task Pool screen, never queued). A tab with no checkboxes at all imports everything enabled (backward compat). The CSV splitter now honors `"`-quoted fields, so descriptions may contain commas.
   - Done: **stable identity + gentle re-sync.** Task id is `external-<category>-<description>` (no more timestamp), so a re-sync updates in place. `TaskPool.mergeImportedManagedTasks`: the sheet checkbox wins for `enabled`, but `neverSuggest` / `temporarilyUnavailable` set in the app survive a re-sync. Editing a description in the sheet changes identity → remove-old + add-new.
   - Done: **the sheet's *tab names* are the category list after import.** `mergeImportedManagedTasks` takes `authoritativeCategories` = `result.tabNames` (README-filtered); `runSheetImport` prunes managed tasks (all but `custom-` in a surviving category) *and* legacy `user_tasks` against it. Authority is by tab existence, not task presence — a tab with zero rows keeps its category; a category with no tab is dropped even if the import brought no tasks. Guard is now `authoritativeCategories.isNotEmpty()` (was `importedTasks.isNotEmpty()`) — a sheet whose rows are all cleared still cleans up; a sheet that only has the default `Sheet1` gets an explicit "run setupMicroTaskingSheet first" message and changes nothing. The success message lists which categories were removed. (v0.1.7-56 dropped only `external-`; -57 kept built-ins; -58 was still gated on a non-empty import — that's why "Must Do"/"For Alice" survived, the import was a no-op.) Caveat: if tab enumeration succeeds but every per-tab CSV fetch transiently fails, the pool is still pruned to those tab names.
   - Done: **Settings is an accordion + the category list scrolls.** One section open at a time (`openSection` string), whole header row tappable. Section renamed **Task Categories** (was "Active Categories"); its list is a bounded (`heightIn(max=320.dp)`) independently-scrolling `Column`.
   - Done: **onboarding QR normalizes the pasted URL.** `generate_install_page.py`: `sheetIdFrom` pulls the id out of whatever the user pastes (with `#gid=`, `?usp=`, `/edit`, or a bare id); the QR encodes `https://docs.google.com/spreadsheets/d/<id>`. Caption under the QR reads `Generated from sheet <id> at <timestamp>` (the real doc title isn't in the URL and the page can't fetch it, so it's the id).
   - Not done: **tri-state A1** — dropped by request (A1 stays a plain 2-state master toggle).
   - Migration note: existing installs' imported tasks have the old `external-<cat>-<hash>-<timestamp>` ids, so the first re-sync after this ships won't carry their in-app flags over (one-time). No migration written — pools are small and re-checkable.
   - Follow-ups for item 2: a custom (`custom-…`) task the user added in-app that lands in a category the sheet also defines is still dropped on sync (unchanged behavior); the CSV reader still can't handle a sheet cell whose value spans multiple lines.

2. **Define import/synchronization**
   - Document exactly what Import does on first use.
   - Document exactly what Synchronize does on later uses.
   - Define replacement/merge behavior, task identity, changed descriptions, deleted rows, categories, enabled checkboxes, and offline/error behavior.

3. **Unreliable updates**
   - Diagnose and fix updates that sometimes or often hang.
   - Verify download, handoff to the Android installer, installation, and launch end to end.
   - Preserve signing identity and data through updates.

4. **Persistent application state**
   - Done: task queue, streak, and longest streak now persist to SharedPreferences (`task_queue`/`streak`/`longest_streak`) and survive closing/reopening the app - see the Tasking logic item. Not yet verified across process death, reboot, or app updates specifically (only tested via normal close/reopen and `gradlew assembleDebug`, not on a real device this round).
   - Done: per-period (today/this week/this month/all-time) longest-streak stats now come from a real persisted completion log (`completion_log`: `CompletionRecord(epochMs, streakAtCompletion)` per completed task, in `TaskPool.kt`), trimmed to the last 45 days on every write. `longestStreakSince` picks the max streak reached within a given window; "all time" still just uses the existing `longest_streak` counter directly. Rapid testing mode (`isRapidTestingMode` in `TaskScheduling.kt`) is no longer a hardcoded flag - it's driven live by the "prompts per day" Settings field: 500+ turns it on, no rebuild needed. On: shrinks "this week"/"this month" down to 7 minutes / minutes-matching-the-current-month's-day-count instead of 7 days / days-in-month, and drops the queue-delivery floor from 30 seconds to 5.
   - Imported task Google Sheet source already survives (via `managed_tasks`/`external_sheet_url`, pre-existing).
   - Uninstalling the app clears all state (SharedPreferences, pre-existing/inherent).
   - Add regression tests for persistence and restoration.

5. **Bulletproof onboarding, install, and update**
   - Test the complete path from onboarding page to APK download to installation/update.
   - Make failures visible with actionable messages and retry paths.
   - Validate APK URL, release asset existence, signing certificate, version code, and generated QR payload.
   - Add automated checks where practical and document the manual device checklist.
   - Done: the install page shows two side-by-side QR codes when built from a non-main branch — one for the latest main release, one for that branch's just-built release — and just the single main QR on ordinary main builds. When both show, the "Scan or Download" step names the Stable Release QR (left) as the one to use, and a callout under the codes flags the development build (right) as in-progress and unguaranteed. "Find latest main release" tells main's releases apart from branch releases by *release title* (`MicroTasking vX.Y.Z-N` for main vs. `MicroTasking vX.Y.Z-N (branch)` for everything else), not by tag. See the "Determine timestamped version/tag" and "Find latest main release" steps in `release-apk.yml`.
   - Done: every build (main or dev) gets its own versioned tag `vX.Y.Z-N` and asset `MicroTasking-vX.Y.Z-N.apk` (`N` = `github.run_number`, unique repo-wide). Dev builds additionally delete the previous dev release first (matched by the ` (branch)` title suffix), so GitHub keeps only the newest dev release; main releases are permanent history. The earlier fixed `dev` tag / `MicroTasking-dev.apk` was dropped — Android's download manager never overwrites a same-named file (it appends ` (1)`, ` (2)`, …), so the fixed name maximized phone-side clutter; distinct per-build names at least download once cleanly under their own identity. Repeat downloads of *different* versions still accumulate in the phone's Downloads — that's a phone-side cleanup, not something the pipeline controls.
   - Done: QR image filenames are version-stamped (`qr-<version>.png`, `qr-main-<version>.png`) so GitHub Pages / its CDN / the browser can't serve a stale QR next to a fresh download button (they used to disagree on the version). `peaceiris/actions-gh-pages` wipes files not in the publish dir each deploy, so old ones don't pile up.

6. **Tasking logic**
   - **Pause/window/vacation-mode fixes (2026-09-11)** — see [DEFECTS.md](DEFECTS.md) item 4. Cold launch no longer force-resumes delivery (a manual pause now survives a relaunch); `TaskDelivery.tick`'s automatic path now hard-checks the active window directly instead of trusting the enabled flag alone; Settings Save no longer force-resumes on every edit, only first-time setup. New **vacation mode** checkbox (Settings → Prompting Schedule, next to Pause/Resume): a hard override that blocks all dispatch and alarm-arming — even forced taps — until manually unchecked.
   - **Reworked on `tasking-revisited` (2026-09-10) — the notes below predate it.** Dispatch is now a fixed interval recomputed every tick (`fixedDispatchIntervalMillis` in `TaskScheduling.kt`, `TaskDelivery.tick`). **Every automatic tick dispatches**: the new task joins the queue if there's room, or pushes the oldest actionable task off the top of a full queue — and *that eviction is a timeout*. Interval is `windowLength / (N-1)` after a dispatch (the usual case); the `remainingWindow / N` no-dispatch pacing is now only hit by a manual force-tap into an already-full queue in normal mode (a no-op). The window closing **no longer abandons the queue** — a task fails by being aged off the top of a full queue on the regular cadence, by a forced tap into a full queue in rapid-testing mode, or by a manual Abandon. *(Later fix, same branch: the rework had gated automatic dispatch on "under half full", so a full queue's countdown expired without dispatching and nothing ever timed out — now every tick dispatches; `RAPID_TESTING_THRESHOLD` also dropped 1000 → 500. That in turn surfaced a pre-existing crash — `tick` could re-queue an already-queued task, and the task screen's `LazyColumn` hard-crashes on the duplicate key, crash-looping the app; fixed by excluding queued ids in `tick`, de-duping in `readTaskQueue`, and an index-qualified list key. See [DEFECTS.md](DEFECTS.md) items 2–3.)* New **clean-day streak** (days with ≥1 completion and no abandon/timeout; idle days don't break it) alongside the unchanged per-task "N in a row"; shown on the Score screen. New tap-to-force **countdown** at the bottom of the task screen. `prompts_delivered_in_window` / the hard daily cap are gone; `next_dispatch_epoch_ms` drives both the countdown and the alarm. Manual Resume now re-ticks immediately (`MainActivity.setBackgroundPrompts`) — fixes DEFECTS.md item 1. Unit tests updated; on-device verification still pending.
   - Done: prompts per day, active window, and remaining window time drive real pacing — tasks are added one at a time at semi-random intervals, recalculated whenever settings are saved. A single shared producer, `TaskDelivery.kt`, backs both the foreground Compose loop and the background `AlarmManager` receiver — both read/write the same persisted state (`task_queue`/`streak`/`longest_streak`/`prompts_delivered_in_window`/`prompts_count_epoch_day`/`prompts_window_start_epoch`/`prompts_within_window`), so whichever is active (foreground while open, the alarm chain while closed) picks up exactly where the other left off. `MainActivity.onStart`/`onStop` hand off between them: opening cancels the alarm (the live loop takes over), closing arms it with a freshly-computed delay. A 30-second floor on the computed delay (`nextPromptDelayMillis`) prevents a pacing edge case (little window time left, several prompts still due) from firing a tight, ANR-causing burst of deliveries.
   - Done: closing the app no longer loses progress — task queue, streak, and longest streak persist and are restored on next launch.
   - Done: `TaskDelivery.reconcileState` handles window-close, day-change, and window-open as three independent checks against wall-clock time, not chained off each other:
     - Window close (was inside the active window on the previous reconcile, now outside — a real inside→outside transition): abandons anything still actionable in the queue — same ABANDONED state and streak reset as a manual Abandon — and turns `backgroundPromptsEnabled` off. A delivery tick is deliberately scheduled right at close time so this runs promptly rather than sitting stale. Transition-keyed (not "currently outside the window"), so it fires exactly once per close and doesn't tear down a manual Resume driving the queue outside the configured window for testing.
     - Day change (calendar day rolled over): resets the daily delivery count *and* the streak — a streak is a daily thing, so a new day always starts it fresh, including for an always-active window (start hour == end hour) that never technically "closes".
     - Window open (a new window occurrence has begun): automatically turns `backgroundPromptsEnabled` on. If the queue somehow isn't empty by this point (it should always be, since close already cleared it), open leaves it alone rather than abandoning/scoring it again.
   - Done: the active window automatically drives `backgroundPromptsEnabled` (on at open, off at close, as above), but a manual Pause/Resume always takes precedence and is genuinely honored regardless of window state - including manually forcing delivery on *outside* the configured window, for testing. `TaskDelivery.computeNextDelayMillis`/`deliverOrConsumeSlot` gate purely on `backgroundPromptsEnabled` now, not on window membership directly; `nextPromptDelayMillis` paces against the real time-to-close when actually inside the window, or a flat 24h when a manual override is running outside it. The one exception: the per-day delivery-count tick (which exists purely to stop a long pause from cramming a catch-up burst in on resume) only advances while genuinely inside the real window, so a manual test session outside it doesn't burn through the normal daily pacing budget. Pausing itself still just freezes the current queue as-is with no new tasks added; a cold app launch always resumes regardless of prior pause state (pausing only lasts for the current running session) and also runs `TaskDelivery.reconcile` before the UI reads persisted state, so a day/window boundary crossed while closed shows up immediately instead of waiting for the first delivery tick. Covered by `TaskSchedulingTest` (pacing inside vs. outside the window, quota exhaustion, the rapid-testing floor).
   - Done: reopening the app while it's already running (icon tap or the delivery notification) brings the existing instance to the front instead of stacking a new one (`launchMode="singleTask"`).
   - Caveat: verified via `gradlew compileDebugKotlin`/`assembleDebug`/`testDebugUnitTest` plus several rounds of on-device testing (which is how the ANR burst, the always-active-window streak bug, and the rapid-test-mode window-close teardown were found) — keep testing close/reopen/pause/window-boundary/background-delivery, there could still be more edge cases like those.
   - ~~Open defect: manual Resume doesn't deliver promptly.~~ Fixed by the 2026-09-10 rework (see top of this item) — Resume runs `TaskDelivery.tick` immediately and the foreground loop polls a persisted epoch instead of sleeping out one long interval. [DEFECTS.md](DEFECTS.md) item 1.
   - Done: per-completion history for real per-period (today/this week/this month/all-time) stats — see the Persistent application state item.
   - Deferred by request: syncing any of this back to the Google Sheet.

7. **In-app crash reporting** — *not started; scoped 2026-09-10. The app currently has zero crash reporting — today's duplicate-task crash-loop ([DEFECTS.md](DEFECTS.md) item 3) was only diagnosable because Vern's device was attached and `adb logcat -b crash` still had the trace.*
   - Install `Thread.setDefaultUncaughtExceptionHandler` at startup. On crash, write one report file to app-private storage (overwrite, not a queue — a crash-loop must collapse to a single report). On the next launch, show a dismissible banner: "MicroTasking closed unexpectedly last time." — no mid-session popups.
   - **Direction chosen: pre-filled GitHub issue.** Banner button opens `github.com/vemcg/MicroTasking/issues/new` with the trace in the body (repo is public, issues enabled). Also keep a "Copy" button for the paste-to-Claude workflow. Not doing automatic upload (Sentry/Crashlytics) unless real external users appear; not doing fixed-address email.
   - **Duplicate suppression (Vern's requirement — he doesn't want 13+ identical issues from one loop):**
     - On-device: fingerprint each crash (exception class + top few `com.microtasking.app` frames, hashed). If the same fingerprint already fired / was already reported, don't re-surface the banner (or show a muted "already reported" state). This alone kills the crash-loop case.
     - Put the fingerprint in the issue title, e.g. `[crash] IllegalArgumentException @ MainActivity:658 (a3f2c1)`.
     - Server-side backstop: a small `issues.opened` GitHub Action that reads the fingerprint, searches existing open issues, and if it finds a match, comments "another occurrence (vX.Y.Z-N, <device>)" on the original and closes the dup. Handles the "several users, same bug, over days" case that on-device dedup can't see.
   - **Deferred:** report contents (trace-only vs. trace + device + persisted-state snapshot). The state snapshot is what made DEFECTS item 3 a fast diagnosis, but it embeds task descriptions. Decide when building.
   - Until this ships, crash diagnosis = keep the test device attached and pull `adb logcat -b crash` / `adb exec-out run-as com.microtasking.app cat shared_prefs/…`.

8. **"Refer to ActiveTasks" hand-off** — *core built (2026-09-18), one gap remains.* See `SPEC.md`
   "Task referral to ActiveTasks" and "Sheet connection & API" for the full design. (The "Sheet
   write-back" section was replaced by "Sheet connection & API" on 2026-09-19 — item 9 below is
   the build plan for it.)
   Built: `ManagedTask.referredAt` field + JSON round-trip (`TaskPool.kt`), exclusion from
   `eligiblePromptTasks`, `refreshReferralState`/`mergeImportedManagedTasks` sync-time
   reconciliation, the Apps Script `doGet`/`doPost` endpoints and hidden+warning-protected
   `Importance`/`Urgency` columns (`populate_google_sheet.js`), the `WebAppClient` network layer
   (`ReferralBridge.kt`), and the in-app UI — a "Refer to ActiveTasks" button available on any queued
   task regardless of state, opening a full-screen Eisenhower touch-capture screen
   (`EisenhowerReferralScreen`) that writes raw unweighted importance/urgency values. The
   onboarding page's combined Sheet-URL + Web App URL QR is built on the MicroTasking side
   (commit `e3cf305`); it is superseded by item 9's single connection code. Not yet verified
   on-device. See ActiveTasks's `SPEC.md`/`PUNCH_LIST.md` item 1 for its side (ingestion gating,
   progress tracking, completion actions, priority weighting).

9. **Sheet connection & API (private Sheet, shared contract for both apps)** — *specified
   2026-09-19 in `SPEC.md` "Sheet connection & API"; nothing built yet.* Goal: the Sheet can stay
   private; both apps read and write it (add a row, delete a row, set/clear importance+urgency)
   only through the Sheet-bound Apps Script Web App, protected by a secret key carried in one
   "connection code" (one setup QR works in both apps). Build in this order — each phase ends at
   a checkpoint so nothing downstream is written against an unverified contract:
   - **Phase 1 — the script** (`scripts/populate_google_sheet.js`; do this first, everything else
     depends on it):
     1. Key + envelope: random key in Script Properties, `key=` check on every `doGet`/`doPost`
        (`unauthorized`), error envelope `{"ok":false,"code","error"}`, `hello` (`apiVersion: 2`),
        menu items **Show connection code** / **Reset connection code**. Verify
        `ScriptApp.getService().getUrl()` returns the `/exec` URL (SPEC open question); if not,
        fall back to showing the key alone.
     2. Header-based column lookup (`Description`/`Link`/`Importance`/`Urgency`, column A always
        the checkbox) replacing the hardcoded B–E ranges in `doGet`/`doPost`/`ensureReferralColumns_`.
     3. `getTasks` (Sheet-order tabs, empty tabs included, blank-A = enabled, hyperlink targets,
        `needsRepair` for headerless tabs; never writes). Keep `getPriorities` as the legacy shape.
     4. `createRow` / `createTab` (validation incl. README/`/`/100-char/link scheme, literal-text
        descriptions so `=…` never evaluates, create + format a missing tab at the far right,
        repair a headerless tab first, append after the last *described* row, checkbox, upsert of
        an existing row's priority).
     5. `setPriority`/`clearPriority`/`deleteRow` moved to the new codes and idempotency rules
        (`no_such_tab`/`no_such_row`; clear/delete of a missing row = success), repairing the
        referral columns on demand; **every write under `LockService.getScriptLock()`** (`busy`).
     6. README text in both `populate_google_sheet.js` and `generate_sheet_template.py`: drop
        "Anyone with the link", describe the connection code. Bump `TEMPLATE_VERSION` /
        `versionBase` together at the end of the item, not per step.
     7. **Tests.** Pull the pure logic (validation, header lookup, row resolution, response
        shaping) into functions a Node test (`node --test`, wired to `npm test`) can run against a
        small in-memory `SpreadsheetApp` fake — there is no Apps Script test harness today. Plus a
        manual checklist on a throwaway copy of the template: wrong/missing key, reset key,
        hand-added tab then `createRow`, rename a tab then `setPriority` (`no_such_tab`), delete a
        row twice, a `=1+1` description, two near-simultaneous creates, a headerless tab, and the
        redeploy flow (old deployment → `unknown_action` → "redeploy").
     - **Checkpoint:** `npm run push:sheet`, Run setup, redeploy as **New version** of the
       *existing* deployment (not a new deployment — that changes the URL), and exercise every
       call with `curl` against Vern's own Sheet made private.
   - **Phase 2 — MicroTasking app:** (1) store the connection code (reuse the `web_app_url`
     pref), mask the key in Settings, redact `key=` in `DiagnosticLog` and error text; (2) extend
     `WebAppClient` — `hello`, `getTasks`, `createRow`, `deleteRow`, error-code parsing — with
     JVM tests against a local HTTP stub; (3) update `parseSetupQr` + `SetupQrTest`
     (uncommitted today) for the single-line code; (4) import via `getTasks`, legacy xlsx/gviz
     read kept as the fallback, and an `apiVersion`/`unknown_action` check that shows the
     "redeploy your Sheet's script" banner; (5) contract error handling (`no_such_*` → notice +
     resync, `unauthorized` → rescan message, `busy` → one retry); (6) My Tasks "Add task" →
     `createRow` when connected (local `custom-` when not) with the successful add mirrored under
     its `external-…` id, one-time "Upload to Sheet" for existing `custom-` tasks, and Task Pool
     "Delete from Sheet" with confirm.
   - **Phase 3 — ActiveTasks** (its own repo; tracked in its `PUNCH_LIST.md`): `parseSetupQr` in the
     scanner (today it dumps the scanned text into the Sheet-URL field), connection-code
     setting, `SheetApiClient` rewrite (stop swallowing errors into an empty list, fix the
     `"$url?action=…"` concatenation so an existing `?key=` survives, add `getTasks`/`createRow`/
     `setPriority`), sync via `getTasks`, **Add item** screen, re-triage write-through.
   - **Interim, done 2026-09-20:** the page now makes two separate single-line QRs (Sheet URL,
     Web App URL) in a shared `#setup` section, because the old combined QR got its Sheet URL
     overwritten by ActiveTasks' scanner. ActiveTasks still needs `parseSetupQr` to use them.
   - **Phase 4 — onboarding page** (`scripts/generate_install_page.py`): one **Connection code**
     box replacing the two URL boxes, validation for editor-address / `/dev` / missing-`key=`
     mix-ups, remove the "Anyone with the link" step, and make ActiveTasks's install page link to it.
     Ship this **after** ActiveTasks's `parseSetupQr`: MicroTasking already tolerates the new
     one-line code, but today's ActiveTasks scanner would mangle it.
   - **Phase 5 — end-to-end on a device** (also closes item 8's unverified round trip): private
     Sheet, fresh install of both apps, scan one QR into each, import, add from each app, refer,
     re-triage, Complete (for now), Fully complete, a hand edit made mid-flight (rename a tab,
     delete a row), Reset connection code → both apps show the rescan message, and a not-yet-
     redeployed script → the redeploy banner. Push the branch, then manually trigger the
     release workflow (`gh workflow run "Build & release APK" --ref <branch>`).
   - Out of scope for this item: renaming/deleting tabs through the API, editing an existing
     row's text through the API, an offline write queue, per-user sign-in (all noted in the SPEC).

10. **Harden against user edits to the shared Sheet** — *MicroTasking's side implemented
    2026-09-22, branch `sheet-surrogate-keys` (the same branch as item 9's app-side taskId work
    above - see that item for what it depends on).* Full write-up lives in ActiveTasks's
    `PUNCH_LIST.md` item 4 (that side is where the headline bug bites - a renamed description
    orphans a stuck, un-dismissable duplicate card there, and a renamed tab makes the orphan
    permanently unreachable). Confirmed while originally scoping this: MicroTasking's own pool
    already handled a description/tab rename cleanly on its own (`mergeImportedManagedTasks`
    rebuilds from the current import every sync, so a stale id is simply dropped, not orphaned) -
    the two gaps actually fixed here are narrower ones the broader "audit other
    user-editable-Sheet-input paths" ask in ActiveTasks's write-up turned up, both real for
    MicroTasking too:
    - **Duplicate-id crash risk.** The Task Pool screen keys its `LazyColumn` by `ManagedTask.id`
      (`items(visibleTasks, key = { it.id })`) with no de-dup guard - the same bug class
      `readTaskQueue`'s `.distinctBy` already protects the queue against. A not-yet-repaired
      sheet's legacy `external-<category>-<description>` id collides if two rows share text; even
      a repaired sheet's Task ID column can briefly hold a copy/paste duplicate before the
      script's own dedupe catches up (item 9's `ensureTaskIds_`). Fixed: `mergeImportedManagedTasks`
      now `.distinctBy { it.id }`s its output, first occurrence wins - a transient sheet-side
      duplicate degrades to "one row temporarily invisible" instead of crashing every screen that
      renders the pool.
    - **Formula injection via hand-typing.** A user typing something that starts with `=` into
      Description/Link gets it silently evaluated by Sheets into a wrong, confusing value neither
      app can ever recover the literal text from. Fixed in `populate_google_sheet.js`:
      `ensurePlainTextDescriptions_` pre-formats columns B/C as Plain Text (`setNumberFormat("@")`)
      on tab creation and on every "Repair headers & triggers" run, so future typing is always
      stored literally. Forward-only, same caveat as the script's existing `createRow`
      literal-text protection - it can't retroactively fix a cell that already evaluated.
    - 1 new Kotlin test (`mergeImportedManagedTasks_duplicateIdInTheImportCollapsesToOne`), 64
      total pass. Script side verified against the same hand-built fake `SpreadsheetApp` harness
      as item 9 (not committed - see that item).
    - **Not done, still dangling** (see item 9's own "not yet built" list, which this doesn't
      shrink): the v2 wire contract itself (`hello`/`getTasks`/`key=` auth/`createRow` etc.) and
      categoryId consumption by either app - a *tab* rename is still not end-to-end safe, only a
      *description* rename is, once a sheet has been repaired to have Task IDs. Also still open:
      no Node test harness for the script (item 9 Phase 1 step 7), and whether ActiveTasks added
      the equivalent `LazyColumn` de-dup guard + a way to clear a permanently-stuck item is that
      session's own call - not re-verified here.
