# MicroTasking — Feature Spec (v0.1, pre-implementation)

Free alternative to "Today Is The Day". Core skill being trained: when prompted, don't
deliberate — just do the task immediately.

## Punch list (not started / not coded / not in progress)
- Persistent structured storage — still SharedPreferences/JSON, no Room/SQLite, no durable history log.
- Real score tracking — rolling average, EWMA, and all-time % (only an in-session completed/attempted counter exists, resets on process death).
- Prompt scheduling honoring the configured window/frequency — scheduler ignores start/end hour and prompts-per-day, fires on a flat fixed interval instead of stratified random sampling.
- Snooze action (pause clock, random re-prompt shortly after) — doesn't exist; only a "show another task" decline exists.
- Onboarding flow (welcome, notifications, combined permissions screen, category review, task pool review, summary) — not implemented.
- Exact-alarm / full-screen-intent / battery-optimization permission requests and the degraded-behavior reminder banner — only POST_NOTIFICATIONS is requested.
- Task stacking, "max concurrent tasks" setting, and oldest-task-auto-fail-on-overflow — not implemented.
- No-spoiler enforcement (task identity hidden until its prompt fires) — not implemented/verified.
- External task source (Google Sheet import via QR-transported link, tabs-as-categories) — design finalized, nothing built yet.
- Per-task adaptive duration (Start/Complete/Abandon workflow, elapsed-time tracking, rolling-average tier reclassification) — replaces the old fixed 5/10/15 authored duration; not implemented.
- Defer mechanism (1 week / 1 month / 3 months / 6 months, triggered pre-Start from the prompt screen, backfills the stack immediately) — replaces enabled/temporarily-unavailable/never-suggest entirely; not implemented.
- Per-task completion history log and streak tracking — not implemented.
- Snooze caps (max length/count) — moot until snooze itself is implemented.
- Category CRUD (add/remove/rename categories) — category list is hardcoded, not editable.
- Optional task link (tap a task to open a URL, e.g. a how-to video) — no link field or tap-to-open exists yet.
- Task queue row shows category name (not the near-useless "State: Ready") plus a queued-at
  timestamp — not implemented (see "Task queue display" below).
- On-device diagnostic/operational log, readable over USB when tethered — not implemented (see
  "Diagnostics" below).

## Daily prompting
- Single configurable window per day. Default: 9am–9pm, 6 prompts/day.
- Prompts fire at random times within the window, spaced using **stratified random
  sampling**: the window is split into N equal sub-intervals (one per prompt) and one random
  moment is chosen within each sub-interval. This keeps prompts feeling random while
  preventing them from clustering together (e.g. two prompts landing in the same hour).
- Alert is a **full-screen takeover** (auto-launches, like an alarm), not just a notification.
- "Exact alarm" permission (see Permissions below) does not mean a fixed schedule — it just
  makes whichever random time the app picks fire reliably instead of being delayed by Doze.
- **No spoilers**: the selected task's identity must not be visible anywhere (home screen,
  notification text, widgets, logs visible in-app, etc.) before its prompt actually fires.
  Even if a task is picked/queued ahead of time internally for scheduling, the user should
  never be able to see or predict which task is coming until the full-screen prompt appears.

## Task categories & pool
- Tasks belong to a **category**. Onboarding/settings shows categories as **checkboxes** so the
  user picks which are active/eligible for prompting.
- Seed categories (see `content/tasks.json`): Decluttering, Cleaning (both pre-checked by
  default), plus Admin/Paperwork, Finances, Health, and Errands (unchecked by default, based on
  commonly-cited procrastination categories — user can enable anytime).
- Each task has a category and a **duration tier: 5 / 10 / 15 min — app-maintained, not
  authored**. Every task (built-in, user-created, or imported) starts at **5 min**. The tier is
  reclassified automatically from actual measured time-on-task (see "Per-task duration
  adaptation" below); it is never set manually and is not part of the task pool/sheet schema.
- Each task can have an **optional link** (e.g. a how-to/demo video URL) — if present, the
  full-screen prompt shows a tappable action that opens it in the device's default browser
  (standard `ACTION_VIEW` intent, not an in-app browser). Example: a "Do 100 steps" task
  linking to a video demonstrating the exercise routine. Editable wherever tasks are
  created/edited (My Tasks, Task Pool).
- Tasks are **repeatable**: completing (or abandoning) a task does not remove it from the pool
  — it goes back in and can be selected again later. The pool is never "used up", and nothing
  is ever permanently hidden/disabled — see **Defer** below for the only way to postpone one.
- Selection is random from active-category, non-deferred tasks, but **weighted adaptively** by
  the user's current score (see below) — three difficulty tiers (low/normal/high score) shift
  the odds toward shorter or longer tasks.

## On-prompt interaction (Start / Complete / Abandoned / Defer)
- Each task on the stack starts in a **not-started** state, offering two actions: **Start** and
  **Defer**.
  - **Start** begins the personal work-timer for that task and is required before Complete or
    Abandoned become available — you can't mark something complete without having started it.
  - **Defer**: pick 1 week / 1 month / 3 months / 6 months. The task immediately leaves the
    stack (no score/failure impact — deferring is neutral) and is hidden from selection until
    the deferral expires. A replacement task is selected immediately and **appended to the
    bottom of the stack** so the stack stays full. Only available pre-Start; once a task is
    started, Defer is no longer offered.
- Once **Start** is pressed, the task's actions become **Complete** and **Abandoned**:
  - **Complete**: stops the timer, records the actual elapsed time, counts as a success. See
    "Per-task duration adaptation" for how this reshapes the task's tier.
  - **Abandoned**: manual give-up, counts as a failure against scoring. Task returns to the
    pool (still repeatable, not deferred, not disabled) for future selection.
- **Refer to ActiveTasks** is available on **any** queued task regardless of the states above —
  not-started, or already Started/mid-timer — the only requirement is that the task is currently
  on the queue. See "Task referral to ActiveTasks" below for the full flow — in short, it opens an
  Eisenhower-matrix touch screen, writes the touch position back to the shared Sheet, and removes
  the task from the stack immediately with a replacement backfilled at the bottom (a neutral
  outcome, like Defer — referring an already-Started task counts as neither Complete nor
  Abandoned, and discards its in-progress timer).
- **The only two ways a task fails**: (1) it gets **pushed off the top of the stack** when a
  new prompt fires while at the "max concurrent tasks" limit (see Task stack below), or
  (2) it is manually marked **Abandoned**. There is no separate deadline/timeout auto-fail —
  the old fixed budget/2x-deadline mechanic is removed.

## Per-task duration adaptation
- Every task starts at **5 min**. After each **Complete**, the actual elapsed Start→Complete
  time is recorded per-task.
- The task's duration tier is recomputed as a **rolling average over the last N completions**
  of that specific task (N still open — default proposal: 5), snapped to the nearest of
  5 / 10 / 15 min. E.g. if actual completion time trends closer to 10 than 5, the task becomes
  a 10-min task going forward.
- This is independent of the score-based adaptive difficulty weighting below — that weighting
  picks *which* tasks get selected more often; this adaptation changes what tier a *specific*
  task is currently classified as.

## Task stack (multiple concurrent tasks)
- Tasks are **stacked**, not single: more than one prompted task can be active/pending at once.
- New setting **max concurrent tasks** (e.g. default 3) caps how many active tasks can be on
  the stack at the same time.
- If a new prompt fires while the stack is already at the limit, the **oldest** task on the
  stack is immediately marked **failed** (counts as not-completed against scoring) and removed
  to make room for the new one. This is one of the only two failure paths (see above).
- The full-screen view shows all currently-active stacked tasks (not just one), each with its
  own Start/Defer or Complete/Abandoned actions depending on whether it's been started, plus
  its own elapsed/remaining-time indicator.

### Task queue display (not implemented)
- Each queue row currently prints `"State: ${label} • ${duration} min"` (`MainActivity.kt`, the
  `LazyColumn` over `taskEntries`), where `label` is "Ready" for the common case — a state name
  that's true of almost every row and tells the user nothing. Replace it:
  - **READY** entries: show `"${task.category} • ${duration} min"` — category name instead of
    the state word, no literal "Category:" prefix needed.
  - **Non-READY** entries (Started/Completed/Abandoned/Timed out) keep showing the state, since
    that's the informative case: `"${task.category} • ${duration} min • ${label}"`.
- **Queued-at timestamp**: `TaskStackEntry` gains a new field (`queuedAtEpochMs`, set at creation
  in `makeTaskStack`/`TaskDelivery.tick`, persisted like `startedAtEpochMs`/`completedAtEpochMs`
  already are) and each row displays it in human-readable local time (e.g. `"Queued 3:42:11 PM"`).
  Purpose: let the user visually confirm, from the screen alone, whether two entries that appear
  together were actually dispatched at different ticks (seconds/minutes apart) or genuinely landed
  in the same tick — direct evidence for the "two tasks show up at once" question tracked in
  [DEFECTS.md](DEFECTS.md), without needing a USB pull to check.

## Prompt cadence
- New prompts are dispatched on a **fixed interval**, recomputed on every scheduling tick
  (window open, each background alarm, app reopen). **Every automatic tick dispatches a task**:
  - Queue **below capacity** → the new task just joins it. The next interval spreads the
    remaining *prompts-per-day − 1* dispatches evenly across the whole active window.
  - Queue **already full** → the new arrival pushes the **oldest** actionable task off the top
    and that eviction is a **timeout** — the regular automatic failure path for a stale task
    the user never acted on.
- The active window closing **no longer clears the queue** — a carried-over task simply
  waits until it's done or aged out by the cadence above.
- The task screen shows a live **H:MM:SS countdown** to the next dispatch; tapping it forces
  a task immediately and re-paces (a no-op when the queue is already full, except in
  rapid-testing mode where it evicts the oldest).

## Timing & scoring
- Each task tracks actual elapsed Start→Complete time (see "Per-task duration adaptation").
  There is no fixed deadline/budget auto-fail anymore — a task only fails via stack-eviction
  or manual Abandon (see On-prompt interaction above).
- Score is shown as **three numbers** after each response:
  1. Rolling average (last N tasks, e.g. N=10)
  2. Exponentially-weighted (recency-biased) average
  3. All-time completion percentage
- Difficulty adjustment (three tiers) uses the score to shift task-tier odds:
  - Low score → weight pool toward 5-min tasks.
  - High score → weight pool toward 15-min tasks.
  - Mid score → normal/even mix.

## History & stats
- Full stats & streak tracking: per-task completion log, timing (prompt time → complete time),
  streaks, and the three score metrics over time.
- Two streaks are tracked separately:
  - **Consecutive completions** ("N in a row") — resets on any abandon/timeout and at midnight.
  - **Clean-day streak** — consecutive days that had at least one completion and no
    abandon/timeout. An idle day (e.g. prompts-per-day set to 0, nothing done) leaves it
    unchanged rather than breaking it. Shown on the Score screen.

## Storage
- **Local-first.** All data — score/history, task pool, categories, settings — lives on-device
  (Room/SQLite once implementation starts). No accounts, no analytics/telemetry.
- Task pool data is structured so an **optional external task source** can be layered on top
  (see below) without a rewrite. Outside of that opt-in feature, there are no other network
  calls and nothing else leaves the phone.

## External task source (design finalized, not yet built)
- **Getting the connection into the app**: the GitHub Pages onboarding page turns a **connection
  code** (the Sheet's Apps Script Web App URL plus a secret key) into a setup QR, client-side —
  no server, plain JS/QR-library on the static page. The in-app scanner (camera QR scan,
  `CAMERA` permission) registers it. Full design in "Sheet connection & API" below. Older QR
  codes that carry only a Sheet share link keep working as a read-only fallback (see "How tabs
  are read").
- **Sharing requirement**: none with a connection code — the Sheet can stay fully private
  (owner-only), because the Web App runs as the owner. Only the legacy read-only fallback needs
  "Anyone with the link — Viewer" sharing.
- **Tabs = categories**: each tab in the Sheet becomes a category 1:1 (tab title is the
  category name), so users can define arbitrary custom categories just by naming tabs — no
  hardcoded category list needed for imported content.
- **Caveat**: Google Sheets tab names can't contain `/`. "Admin/Paperwork" becomes
  "Admin - Paperwork" as a tab name — category-name matching for the merge rule below must
  normalize this the same way on both sides, or the category gets renamed to avoid `/` going
  forward. Not yet resolved in code.
- **Starter template**: `content/microtasking-sheet-template.xlsx` (generated by
  `scripts/generate_sheet_template.py` from `content/tasks.json`) — one tab per seed category,
  column A = enabled checkbox, column B = `Description`, column C = `Link` (bold, centered
  headers), ready to upload to Google Drive as a starting point. Re-run the script after editing
  `content/tasks.json` to regenerate it. Both the builder and the Apps Script carry a
  `TEMPLATE_VERSION` constant (kept in sync with `buildVersionBase`): README row 2 is stamped
  `Template version: <v> (…date…)`; the spreadsheet's own title stays plain
  `MicroTasking Task Pool Template`. The live sheet behavior (A1 master toggle; a row's
  checkbox appearing when its description is typed and disappearing when it's cleared) is in the
  bundled Apps Script `scripts/populate_google_sheet.js`. That script is the source of truth for
  the shared template Sheet and is pushed to its bound project with `npm run push:sheet` (clasp);
  users who copy the template still paste it into their copy by hand.
- **How tabs are read**: with a connection code, one `getTasks` call to the Web App returns
  every tab (in Sheet order) with its rows — see "Sheet connection & API". **Legacy fallback**
  (a scan/URL carrying only a Sheet link, Sheet shared "Anyone with the link"): tab names from
  the `.xlsx` export's `workbook.xml` (legacy GData worksheets feed as a backup), then each tab's
  rows via the `gviz` CSV export by tab name — read-only, MicroTasking only, with no referral,
  add, or delete. (The earlier plan of a restricted Google Cloud API key plus
  `spreadsheets.get` was never built and is dropped.)
- **Row schema per tab** (category comes from the tab, so no category column needed):
  column A = enabled checkbox (no header text — row 1 col A is the master toggle), column B =
  `Description`, column C = `Link` (optional). **No `durationMinutes` column** — duration is an
  app-maintained adaptive value (see "Per-task duration adaptation"), never authored; every
  imported task starts at 5 min. Rows with an empty description are skipped, not fatal. `link`,
  if a real hyperlink is used in the cell, is read as its target URL. The CSV parser honors
  `"`-quoted fields (so a description may contain commas) but not fields spanning newlines.
- **`Importance`/`Urgency` columns** (added by "Task referral to ActiveTasks" below, matched by
  header text like the columns above): hidden, warning-protected, not part of the user-facing
  schema, and **not read via this CSV/gviz path at all** — unlike every other column, they're
  read only through the Apps Script Web App (see "Sheet connection & API" below), specifically because
  the CSV/gviz export doesn't respect a column's hidden state. A non-empty value on either makes
  the row ineligible for selection.
- **Import is all-or-nothing per row, not per checkbox**: every row with a description is
  imported. An unchecked column A means the task exists in the pool but is `enabled = false`, so
  it is stored and visible on the Task Pool screen but never queued. If a tab has no checkboxes
  at all (a sheet predating this convention), every row imports as enabled.
- **Merge behavior**: task identity is `external-<category>-<description>`. Re-syncing replaces
  every category the sheet contains wholesale; categories the sheet doesn't mention are left
  alone. For a task that still exists after a re-sync, the sheet checkbox is authoritative for
  `enabled`, but the app-only flags the user set on the Task Pool screen (`neverSuggest`,
  `temporarilyUnavailable`) are carried over. Editing a description in the sheet changes its
  identity, so it reads as remove-old + add-new. (`MainActivity.parseExternalTaskCsv` /
  `TaskPool.mergeImportedManagedTasks`.)
- **Sync cadence**: manual "Refresh now" always available, plus periodic background sync
  (interval TBD, e.g. daily) once a source is registered.
- **Guardrails**: HTTPS-only, timeout + response-size cap on fetches, CSV parsed as plain data
  only (never rendered/executed as HTML).

## Task referral to ActiveTasks (design finalized, not yet built)

Companion to **External task source** above: once import is in place, a queued task can be
**referred** to the sibling app **ActiveTasks** (`../ActiveTasks`) instead of being done here. See
ActiveTasks's own `SPEC.md` ("Priority: Eisenhower matrix", "Referral bridge") and
`PUNCH_LIST.md` item 1 for its side of this feature.

- **Trigger**: a **"Refer to ActiveTasks"** action available on any queued task, in any state — not
  gated to pre-Start like Defer (see "On-prompt interaction" above). Referring an already-Started
  task discards its in-progress timer and counts as neither Complete nor Abandoned.
- **Eisenhower touch capture**: tapping it opens a full-screen 2x2 matrix (same quadrant layout
  ActiveTasks's own triage widget uses — Important/Not important rows, Urgent/Not urgent columns).
  The **precise touch position** is recorded, not just which quadrant it landed in: horizontal
  position maps to **urgency** (left edge = 1.0, right edge = 0.0), vertical position maps to
  **importance** (top edge = 1.0, bottom edge = 0.0) — two continuous floats in `[0, 1]`, not
  booleans. (Axis direction and the exact mapping are a first proposal — tunable, see Open
  questions.)
- **Write-back**: those two values are written to the task's row in the shared Sheet via the
  Apps Script Web App (see "Sheet connection & API" below) as soon as the touch is confirmed. On
  success, the task is immediately removed from this app's stack — same as Defer, a replacement
  is selected and appended to the bottom to keep the stack full. On failure (network/Web App
  error), the referral is not applied and the task stays on the stack (exact failure-state UX
  TBD at build time).
- **Exclusion from future selection**: a new field, `ManagedTask.referredAt: Long?` (epoch ms,
  null = not referred), is the authoritative local signal — set immediately on a successful
  referral write (no waiting on the next sync), and refreshed from the sheet's `Importance`/
  `Urgency` state at every sync boundary (see "Sheet connection & API" below for how those are read).
  `TaskDelivery`'s selection logic excludes any task with `referredAt != null`. This is a
  **dedicated field, not a reuse of `ManagedTask.neverSuggest`**: `mergeImportedManagedTasks`
  explicitly preserves `neverSuggest`/`temporarilyUnavailable` across every re-sync as
  user-set flags that must survive re-import (see its doc comment in `TaskPool.kt`) — reusing
  that field for referral would mean either fighting that preserve-on-resync contract when a
  referral is reversed (ActiveTasks's "Complete (for now)"), or special-casing which resets are
  "real" `neverSuggest` vs. referral-driven. A dedicated field avoids that entirely: it's simply
  overwritten from sheet state on every sync, same as `enabled` already is.
- **Sheet schema addition** (`populate_google_sheet.js` / `applyCategoryTabHeader_`): two new
  columns per category tab — `Importance`, `Urgency` — matched by header text like
  `Description`/`Link`, not position, placed after the existing columns. Both are **hidden**
  (`Sheet.hideColumn`) and covered by a **warning-only protected range** — not a hard lock,
  since the Web App runs as the sheet owner and needs to write them; the intent is to keep an
  accidental manual edit from silently corrupting referral state, not to make the range
  literally uneditable. A blank `Importance`/`Urgency` cell means "not referred." (ActiveTasks's
  own progress value is local-only, not a sheet column — see its `SPEC.md`.)
- **Row identity for the write**: the Apps Script endpoint resolves which row to write by
  `(tab name, description text)`, scanning column B server-side — not by row/gid index. Matches
  the existing `external-<category>-<description>` id convention, and index-based addressing
  would be actively unsafe: `onSheetEdit_` already deletes rows and shifts everything up when a
  description is cleared, so a cached row index could silently point at the wrong row moments
  later.

## Sheet connection & API (Apps Script Web App) (design finalized, not yet built)

Replaces the earlier "Sheet write-back" design, which covered only the referral columns and left
reading on the public CSV export. This section is the single contract between the Sheet-bound
script (`scripts/populate_google_sheet.js`, owned by this repo) and **both** apps. ActiveTasks's
`SPEC.md` points here rather than restating it.

### Principles

- **The Sheet is the source of truth; the API is a macro for hand edits.** Users are expected to
  do most maintenance (tabs, rewording, reordering, bulk edits) directly in Google Sheets, and
  nothing is locked out of either path. Every API action produces a state a person could have
  produced by hand, and both apps must tolerate any hand edit made between two calls.
- **Who owns a row is decided by its Importance/Urgency columns** (unchanged): both blank = in
  MicroTasking's pool; either non-blank = in ActiveTasks's list (see "Task referral to ActiveTasks").
- **ActiveTasks remains a companion, not a standalone to-do app.** It lists only rows that carry a
  priority, has no "inbox" of unprioritized rows, and with nothing prioritized yet it comes up
  blank. A row typed into the Sheet by hand is a MicroTasking pool task, not an ActiveTasks item, by
  design. (A standalone ActiveTasks was considered and dropped 2026-09-19.)
- **Both apps do the same things to the Sheet** (table below). They differ only in what a new
  row starts as: MicroTasking adds an *unprioritized* row (lands in the pool); ActiveTasks adds a
  *prioritized* row from its matrix touch (born in ActiveTasks's list, so MicroTasking never queues
  it).

| Operation | MicroTasking | ActiveTasks |
|---|---|---|
| Read every tab and row | import / periodic sync | "Sync Lists" (keeps only rows with a priority) |
| Add a task | My Tasks → `createRow` without priority | Add item → `createRow` with the matrix touch |
| Set priority | "Refer to ActiveTasks" → `setPriority` | re-triage → `setPriority` (write-through; the local copy updates even if the call fails) |
| Clear priority | client function only, no UI | "Complete (for now)" → `clearPriority` |
| Delete row | Task Pool → "Delete from Sheet" (confirm) → `deleteRow` | "Fully complete" → `deleteRow` |
| Create tab | implicit: `createRow` with a new category name creates the tab | none — adds go to an existing list; tab management stays in the Sheet |

Exact screen layout of the new Add/Delete actions is TBD at build time.

### Connection code, key, and privacy

- **Connection code** = the deployed Web App URL plus a secret key, as one string:
  `https://script.google.com/macros/s/<id>/exec?key=<key>`. It is what the setup QR carries and
  what both apps store. Apps treat it as an opaque URL and append `&action=…` (or POST to it).
- **The key** is a random value (≥128 bits, e.g. a UUID with dashes stripped) the script keeps in
  Script Properties, generated on first use. Every `doGet`/`doPost` requires `key=` in the query
  string (GET and POST alike) and otherwise returns `{"ok":false,"code":"unauthorized"}`. A missing
  and a wrong key are indistinguishable.
- **The Sheet itself can be private.** The script runs "Execute as: Me", so it reads and writes
  as the owner; nothing needs "Anyone with the link" any more, and the README/onboarding text
  telling users to enable it is removed. The deployment still has to be "Who has access: Anyone"
  (a phone can't sign in as the owner), which is why the key is what protects it.
- **Honest limits.** The key is a bearer secret, like a share link: anyone who obtains the code
  (screenshot, QR, clipboard) can read and write the task Sheet until it is reset. It is not
  per-user sign-in (that would need OAuth and a Google Cloud project in both apps, deliberately
  avoided). Mitigations: the code exposes only what the script returns (not the whole document,
  hidden columns of unrelated tabs, or the README), and the menu item **MicroTasking → Reset
  connection code** issues a new key, invalidating every old QR.
- **Apps never log or display the full key.** Settings shows the URL with the key masked
  (`…/exec?key=k7Qm…`), and `DiagnosticLog` and error messages redact any `key=` value.

### Wire contract

Every response is JSON: `{"ok":true, …}` or `{"ok":false,"code":"<code>","error":"<human text>"}`.
Codes: `unauthorized`, `no_such_tab`, `no_such_row`, `invalid`, `reserved_tab` (the README tab),
`busy` (couldn't take the script lock within ~10s), `unknown_action`. Row identity is always
`(category = tab name, description = column B text, exact trimmed match)`, never a row/gid index;
if hand edits ever produce two rows with the same description in one tab, the first wins.

| Call | Request | Success response and effect |
|---|---|---|
| `hello` | `GET ?action=hello` | `{"ok":true,"apiVersion":2,"templateVersion":"0.1.8","actions":[…]}`. Apps call it first on every sync to detect an out-of-date deployment. |
| `getTasks` | `GET ?action=getTasks` | `{"ok":true,"tabs":[{"name","needsRepair","rows":[{"enabled","description","link","importance","urgency"}]}]}` — see "Reading" below. |
| `getPriorities` | `GET ?action=getPriorities` | Legacy (apiVersion 1) shape, kept for already-shipped clients: every row with a priority. |
| `createRow` | `POST {"action":"createRow","category","description","link"?,"enabled"?,"importance"?,"urgency"?}` | `{"ok":true,"created":bool,"tabCreated":bool}` — see "Writing" below. |
| `createTab` | `POST {"action":"createTab","name"}` | `{"ok":true,"created":bool}`. Formats a new tab like a hand-added one. No app UI in this version; kept so a future client isn't blocked. |
| `setPriority` | `POST {"action":"setPriority","category","description","importance","urgency"}` (each 0–1) | `{"ok":true}`, or `no_such_tab` / `no_such_row`. |
| `clearPriority` | `POST {"action":"clearPriority","category","description"}` | `{"ok":true,"found":bool}` — a missing tab or row is success (the row is already not in ActiveTasks's list). |
| `deleteRow` | `POST {"action":"deleteRow","category","description"}` | `{"ok":true,"deleted":bool}` — a missing tab or row is success (already gone). |

Not in the contract on purpose: renaming or deleting a **tab**, and editing an existing row's
description/link/enabled flag. All are done by hand in the Sheet, which the apps already
tolerate (see below), and any of them can be added later as a new action — `hello`'s `actions`
list lets apps degrade gracefully. Sheets tab names can't contain `/`; `createRow`/`createTab`
reject such names as `invalid` (see the caveat under "External task source").

**Reading (`getTasks`).**
- Tabs come back in Sheet order (tab order sets MicroTasking's category weighting), README
  excluded, **empty tabs included** with `"rows":[]` (ActiveTasks shows an empty tab as an empty list).
- Columns are found by header text, case-insensitively — `Description`, `Link`, `Importance`,
  `Urgency` — and column A is always the enabled checkbox, so column reordering stays safe. The
  script's own reads and writes use the same header lookup (no hardcoded column letters).
- `enabled` is the checkbox's value; a blank column-A cell counts as enabled. A row with a blank
  description is skipped. `link` is the hyperlink target if the cell holds a real link, else its
  text. `importance`/`urgency` are numbers, or `null` when the cell is blank.
- A tab with no header row (added by hand where the triggers never fired, e.g. on mobile) is read
  with default columns B/C/D/E and reported `"needsRepair":true`; it is not an error. Reads never
  write.

**Writing — what the script must do itself.** A change made by a script does **not** fire the
installable `onEdit`/`onChange` triggers (the comment above `onSheetEdit_` already notes this), so
the Web App has to apply, in the script, everything those triggers would have done for a hand edit.
The trigger helpers (`applyCategoryTabHeader_`, `addRowCheckbox_`, `ensureReferralColumns_`) are
reused directly.
- **`createRow`**: trims `category` and `description`; rejects an empty value, a category of
  `README` (`reserved_tab`), a name with `/`, over 100 characters (Sheets' tab-name limit), a
  `link` that isn't `http(s)://`, or a priority where only one of importance/urgency is given
  (`invalid`). If the tab doesn't exist it is created at the far right (lowest odds; the user
  reorders by hand) and headered/formatted first; a tab lacking a header is repaired first. If the
  row already exists it is not duplicated: `created:false`, and if a priority was supplied while
  the existing row has none it is applied (so re-adding a pool task from ActiveTasks refers it). A new
  row is appended after the last row that has a description (not `getLastRow()`, which the hidden
  columns can inflate), gets its checkbox in column A (checked unless `enabled:false`), and its
  Importance/Urgency cells hold the supplied priority or stay blank.
- **Descriptions and links are always stored as literal text.** A leading `=` is never evaluated
  as a formula (the cell is set to plain-text format before writing).
- **`setPriority`/`clearPriority`**: first repair the Importance/Urgency columns (header, hidden,
  warning-protected) if the tab lacks them, so an older tab never ends up with visible, unprotected
  referral values.
- **`deleteRow`**: nothing to format — Sheets shifts the rows below up, the same result as the
  existing clear-the-description behavior.
- **Every write holds `LockService.getScriptLock()`** across find-then-write, so two phones (or
  both apps) can't interleave; a timeout returns `busy`. The lock covers script runs only, not a
  person typing in the Sheet at the same time — which is why identity is re-resolved by
  description inside the lock rather than cached.

### Hand-edit tolerance (both apps)

The tab set, tab names, tab order, descriptions, and checkboxes can all change between any two
calls. Therefore:
- **Reads are authoritative.** Each import replaces the local view of every tab the Sheet
  contains (unchanged: `mergeImportedManagedTasks` — the Sheet's tab names decide the category
  list). ActiveTasks's re-sync keeps its existing "add only, never remove an in-progress item"
  behavior.
- **`no_such_tab` / `no_such_row` on a write is a state, not a failure.** It means the row was
  changed by hand after the app last synced. The app shows a short notice ("That task was changed
  or removed in your Sheet"), drops or refreshes the local item, and resyncs — it does not show the
  generic "couldn't reach the Sheet" error.
- **`unauthorized`** means the key was reset or the code is wrong: stop, and tell the user to scan
  the new setup QR (no retry loop).
- **`busy`** is retried once after ~2s, then surfaced like any other transient failure.
- **Network failure or Web App error**: the write is not applied and local state is unchanged
  (existing behavior); there is no offline write queue in this version. The one exception is
  ActiveTasks's re-triage: its local copy keeps the new priority and the error is shown (see the
  operations table).
- **A successful add is mirrored locally at once**: the app inserts the task under the id the next
  sync will produce (`external-<category>-<description>`) rather than waiting, so there is no
  interim `custom-` copy and no duplicate after the sync. For ActiveTasks the inserted item carries the
  priority it was created with.
- **`TaskDelivery.tick` never calls the network** (unchanged). Reads happen at existing sync
  boundaries (manual refresh + periodic background sync); writes happen only on an explicit user
  action.

### Adding tasks from the phone (My Tasks)

MicroTasking's Settings → Local Task Management → My Tasks (description + category, stored locally
as `custom-` tasks) becomes the on-the-go entry point:
- **Connected** (a connection code is registered): "Add task" calls `createRow`; the category
  field accepts an existing tab or a new name (which creates the tab). Nothing new is stored
  locally as `custom-`.
- **Not connected**: unchanged — local `custom-` tasks, as today.
- Existing local `custom-` tasks get a one-time "Upload to Sheet" action once connected (each is a
  `createRow`, then removed locally as the sync returns it as `external-…`). Exact UX TBD.
- ActiveTasks gets an equivalent Add item (pick a list, type the item, touch the matrix) — see its
  `SPEC.md`.

### Getting the code into the apps, and deployment

- **Setup QR**: one line of plain text — the connection code. The scanner classifies lines by
  content (`script.google.com/` = connection code; anything else = legacy Sheet URL —
  `parseSetupQr`), so old one- and two-line codes keep working. **The same QR works in both
  apps**; ActiveTasks's scanner adopts `parseSetupQr` (today it drops the raw scanned text into its
  Sheet-URL field).
- **On scan**: MicroTasking saves the code, calls `hello`, then runs a full `getTasks` import;
  ActiveTasks saves it and syncs.
- **Interim (2026-09-20, built): two separate QR codes.** Until the connection code exists, the
  onboarding page's setup step (`#setup`, shared by both apps) has one box and one single-line QR
  for the Sheet URL and another for the Web App URL — not one combined two-line code. Each app
  must update only the setting the scanned line belongs to (`parseSetupQr`); a scan never blanks
  the other setting. ActiveTasks' scanner still writes raw scanned text into its Sheet-URL field,
  so it overwrites the Sheet URL when given the Web App code — it must adopt `parseSetupQr` (see
  its `PUNCH_LIST.md` item 3) before both codes are usable there. Its install page links to this
  section rather than repeating the steps.
  **Settings layout (both apps, identical):** one section titled **Google Sheet Connection**,
  top to bottom: why a Sheet URL is needed → "Google Sheet URL" box → **Scan Sheet QR Code**; why
  the Web App URL is needed → "Apps Script Web App URL" box → **Scan Web App QR Code**; then a
  single action button (MicroTasking **Update Tasks**, ActiveTasks **Sync Lists**) and its status
  message. Both scan buttons open the same scanner and route the result by content
  (`parseSetupQr`), never by which button was pressed.
- **Onboarding page** (`scripts/generate_install_page.py`): the two boxes (Sheet URL, Web App URL)
  become one **Connection code** box, with the "set sharing to Anyone with the link" instruction
  removed. Validation flags the two common mix-ups: the Apps Script *editor* address
  (`…/home/projects/…/edit`) and a `/dev` test URL, and now a code with no `key=`. It stays the one
  setup page for both apps; ActiveTasks's install page links to it rather than duplicating the
  generator.
- **Getting the code out of the Sheet**: new menu items **MicroTasking → Show connection code**
  (a dialog with the ready-to-paste code, or "Deploy the Web App first" if not yet deployed) and
  **Reset connection code**. The dialog builds the code from `ScriptApp.getService().getUrl()`
  plus the stored key. (Whether `getUrl()` reliably returns the `/exec` URL — versus `/dev` —
  needs verifying at build time; the fallback is showing the key alone and the page taking URL and
  key in two boxes.)
- **The `/exec` URL runs the *deployed version*, not the saved code.** Every script change
  (including all the new actions here) needs each user to redeploy: Deploy → Manage deployments →
  ✏️ → Version: **New version** → Deploy. Choosing *New deployment* instead would change the URL
  and invalidate every QR. `hello`'s `apiVersion` exists for this: an app that gets `hello` back
  as `unknown_action` (an apiVersion-1 script) or an `apiVersion` below what a feature needs shows
  "Your Sheet's script is out of date — redeploy it" with those steps, and keeps working with
  whatever the old script does support (v1: referral write-back only, no key check, no
  `getTasks`).
- **Legacy fallback**: an app holding only a Sheet URL (no connection code) uses the old
  xlsx/gviz read path. MicroTasking works read-only that way; ActiveTasks needs a connection code (it
  cannot work without priorities).

## Versioning
- Version string shape: **`major.minor.feature-build`**, e.g. `0.1.8-2`.
  - `major.minor.feature` (`versionBase`, e.g. `0.1.8`) is bumped by hand in
    `app/build.gradle.kts` (`buildVersionBase` property, default value) when a batch of features
    lands — this is the "feature release" number, not tied to semver compatibility rules.
  - `build` is `github.run_number` — GitHub's own monotonically-increasing, repo-wide workflow-run
    counter. It is **automatic, not something to set by hand**: it increments on every run of the
    "Build & release APK" workflow, on any branch. Because pushes to a non-`main` branch don't
    auto-trigger the workflow (see [[feedback_manual_workflow_trigger]] /
    "Always manually trigger the release workflow on non-main pushes" in memory), the next build's
    number is "whatever `run_number` is next" — check the most recent release/tag rather than
    assuming a specific value.
  - So after "Bump version base to 0.1.8", the *first* build at that base is `0.1.8-<N>` where `N`
    is simply the next run number in sequence — there's no separate per-base counter that resets
    to 1.

## Distribution & updates
- Provide a **QR code** (e.g. in Settings/About) that links to the latest release APK for easy
  sideload install/update on another device — for sharing/installing the app itself, not for
  transferring in-app data.
- **Implemented** via GitHub Actions (`.github/workflows/release-apk.yml`): every push to
  `main` builds a debug APK, tags/publishes it as a GitHub Release, generates a QR code image
  pointing at that release's direct download URL (`scripts/generate_install_page.py`), and
  publishes a small install page + QR to GitHub Pages (`gh-pages` branch, `docs/` folder). Each
  push produces a new tag (`v0.1.7-<run number>-<UTC yyyymmdd.HHmmss>-<short commit>`), so the QR always points at the
  latest build.
- Debug builds are signed with a **checked-in debug keystore** (`keystore/debug.keystore`, not
  a secret — Android debug keys are never meant to be secret) so every CI build shares the same
  signature and installs cleanly over the previous version on-device.
- One-time manual setup still required: enable GitHub Pages in repo Settings → Pages → source
  = `gh-pages` branch, root. After that, the page URL is
  `https://<owner>.github.io/<repo>/`.

## Permissions & OS behavior
- `POST_NOTIFICATIONS` (Android 13+) — required just to alert at all. Onboarding blocks setup
  until granted; app is non-functional without it.
- `USE_FULL_SCREEN_INTENT` — lets the notification auto-launch the full-screen task view over
  the lock screen. On Android 14+ this must be enabled manually by the user in system settings
  (app can deep-link there, can't force-grant). If denied, falls back to a heads-up notification
  that opens the full-screen view on tap; stack-eviction order is based on when the task was
  prompted, not when it was tapped (the personal work-timer itself only starts on **Start**).
- Exact alarm permission (`SCHEDULE_EXACT_ALARM` / "Alarms & reminders" toggle) — needed so
  prompts fire at the intended random time instead of being batched/delayed by Doze. If denied,
  prompts still fire but may arrive later than scheduled (inexact alarm fallback).
- Battery optimization exemption — not a formal permission; onboarding will ask the user to
  exclude the app from battery optimization for reliability, since Doze can otherwise delay or
  drop scheduled alarms.
- If any of the above three are skipped during onboarding, the app shows an occasional
  dismissible in-app reminder banner (not a repeated permission prompt) noting the degraded
  behavior, until the user grants it or the banner is dismissed.

## Onboarding flow
1. **Welcome screen** — explains the concept (random prompts, do the task now, don't overthink)
   so the permission asks that follow make sense.
2. **Notifications** (`POST_NOTIFICATIONS`) — required/blocking; can't proceed without it.
3. **Permissions screen (combined)** — one screen listing full-screen alerts, exact alarms,
   and battery optimization exemption together, each with its own allow action; all skippable.
4. **Category selection** — checkboxes, Decluttering + Cleaning pre-checked.
5. **Review starting task pool** — pre-seeded default tasks per selected category (5-8 each),
   user can trim/add before finishing.
6. **Prompt window & frequency** — default 9am–9pm / 6 prompts/day, editable here.
7. **Summary screen** — recap what's active/skipped and what that means, then finish → home.

## Diagnostics (not implemented)
- Distinct from the crash-reporting feature already scoped in
  [PUNCH_LIST.md](PUNCH_LIST.md) item 7 (uncaught-exception handler → one overwritten report file
  → pre-filled GitHub issue). This is an **operational log** covering normal (non-crash) app
  activity, so a USB-tethered pull can explain behavior like "why did two tasks show up at once"
  without that having been a crash.
- Written to app-private storage so it survives process death, and readable the same way
  [[reference_android_crash_debugging]] already pulls persisted state: force-stop not required,
  `adb exec-out run-as com.microtasking.app cat files/diagnostic.log`.
- **Format**: plain text, one line per event, leading local-time timestamp — readable directly
  off an `adb pull`/`cat`, no parsing step needed. E.g.:
  `2026-09-12 14:03:11  QUEUED  task=declutter-surface category=Decluttering duration=5m`
- **Events logged**:
  - Task lifecycle: queued, dispatched/prompted, started, completed, abandoned, timed out,
    deferred, substituted — each with task id, category, and duration.
  - App/permission lifecycle: process start, first `onResume` after start, and each permission
    grant/deny/revoke transition (notifications, full-screen intent, exact alarm, battery
    exemption).
  - Settings changes: window start/end, prompts-per-day, max-queue-size, and active-category set
    — logged as before → after on each Save.
  - (Scheduler ticks — every `TaskDelivery.tick` call, dispatch or not, with the reason — were
    considered but left out for now; the events above should already show *that* something odd
    happened. Revisit if the task-lifecycle log alone isn't enough to explain a future report.)
- **Retention**: rolling 48-hour window — on each write, entries older than 48h are dropped from
  the front of the file (it's append-ordered, so this is a cheap linear scan from the start, not a
  sort). A hard size cap (2 MB) is also enforced as a backstop independent of the time window, so a
  future bug that logs in a tight loop (e.g. a `tick` storm like the historical duplicate-task
  crash-loop in [DEFECTS.md](DEFECTS.md) item 3) can't grow the file unbounded before the 48h trim
  catches up.
  - **Sizing**: at normal usage (6 prompts/day, occasional app opens/settings edits) this is on
    the order of 10-20 KB/day — even a heavy day (frequent app opens, several settings edits, all
    3 stack slots cycling) stays well under 200 KB/day, so 48h of history is comfortably under
    1 MB in practice. That's negligible next to the hundreds of MB to low GB of free space typical
    app-private storage has — the 2 MB cap above is a safety backstop against a logging bug, not
    an expected ceiling.

## Open questions (for later)
- External task source: exact periodic sync interval (e.g. daily?) and Google Cloud API key
  setup steps (project creation, Sheets API enablement, Android app restriction) — see
  "External task source" section above for the rest (now finalized).
- Task referral to ActiveTasks: exact touch-axis mapping and the resulting priority formula weighting
  are a first proposal, not user-validated — revisit once there's a feel for how it ranks in
  practice (ActiveTasks's existing `important*2 + urgency` note says the same about its old boolean
  formula). Whether the warning-only protected range actually blocks a script running "Execute
  as: Me" the way intended, or needs a different protection setup — verify during
  implementation. Failure-state UX when the referral write-back call fails (offline, Web App
  misconfigured/undeployed, etc.).
- Sheet connection & API: whether `ScriptApp.getService().getUrl()` reliably returns the `/exec`
  URL for "Show connection code" (fallback described in that section); exact layout of the new
  Add task / Delete from Sheet / Upload-to-Sheet actions; whether a lost/rotated key needs
  anything friendlier than "scan the new QR"; whether an offline write queue is worth adding
  (none in this version).
- QR install/update: where the APK is hosted (e.g. GitHub Releases) and how the QR content
  gets generated/kept in sync with the latest build.
- Max concurrent tasks default value and whether it's adjustable per-category or global only.
- Snooze caps (max length / max count per task) — snooze (short pause/re-prompt) is still a
  separate, undecided concept from Defer (long-term 1wk-6mo postponement, now finalized).
- Exact rolling-window size (N) for per-task duration adaptation (default proposal: 5).
- Exact rolling-window size (N) and EWMA decay factor for the overall score metrics.
- Exact weighting curve for adaptive difficulty tiers.
- Full list of categories beyond the starting two (Decluttering, Cleaning).
- Category CRUD (add/remove/rename categories) — see punch list.

