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
- Each task on the stack starts in a **not-started** state, offering two actions:
  **Start** and **Defer**.
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

## Storage
- **Local-first.** All data — score/history, task pool, categories, settings — lives on-device
  (Room/SQLite once implementation starts). No accounts, no analytics/telemetry.
- Task pool data is structured so an **optional external task source** can be layered on top
  (see below) without a rewrite. Outside of that opt-in feature, there are no other network
  calls and nothing else leaves the phone.

## External task source (design finalized, not yet built)
- **Getting the link into the app**: the GitHub Pages install page (same page as the
  install/update QR) gets a text field where the user pastes an ordinary Google Sheet share
  link and clicks "Generate QR". The page encodes it client-side into a custom-scheme QR
  (`microtasking://import-tasks?url=<encoded sheet link>`) — no server involved, plain
  JS/QR-library on the static page. The app registers an intent-filter for `microtasking://`
  and jumps straight to an import-confirmation screen when that QR is scanned with the
  in-app scanner (new capability — camera-based QR scan, requires `CAMERA` permission).
- **Sharing requirement**: the Sheet just needs ordinary "Anyone with the link — Viewer"
  sharing. No "Publish to web" step required.
- **Tabs = categories**: each tab in the Sheet becomes a category 1:1 (tab title is the
  category name), so users can define arbitrary custom categories just by naming tabs — no
  hardcoded category list needed for imported content.
- **Caveat**: Google Sheets tab names can't contain `/`. "Admin/Paperwork" becomes
  "Admin - Paperwork" as a tab name — category-name matching for the merge rule below must
  normalize this the same way on both sides, or the category gets renamed to avoid `/` going
  forward. Not yet resolved in code.
- **Starter template**: `content/microtasking-sheet-template.xlsx` (generated by
  `scripts/generate_sheet_template.py` from `content/tasks.json`) — one tab per seed category,
  `description, durationMinutes, link` columns, ready to upload to Google Drive as a starting
  point. Re-run the script after editing `content/tasks.json` to regenerate it.
- **How tabs are read**: a one-time Sheets API v4 metadata call (`spreadsheets.get`) lists
  every tab's title + internal `gid`, using a Google Cloud API key restricted to the Sheets
  API and to this app's package name + signing-cert SHA-1 (locked to the checked-in debug
  keystore). Each tab's rows are then fetched via the plain CSV export URL
  (`.../export?format=csv&gid=<id>`), which needs no API key — just the link-sharing
  permission above.
- **Row schema per tab** (category comes from the tab, so no category column needed):
  `description, link (optional)`. **No `durationMinutes` column** — duration is now an
  app-maintained adaptive value (see "Per-task duration adaptation"), never authored; every
  imported task starts at 5 min like any other. Rows with an empty description are skipped,
  not fatal. `link`, if a real hyperlink is used in the sheet cell, is read as its target URL.
- **Merge behavior**: re-syncing a source replaces its own previously-imported tasks (IDs
  derived deterministically from `sourceUrl + tab + row index`, so decline-counts/overrides
  survive a refresh). If an imported tab's name matches an existing category, its tasks
  **merge into that category** rather than staying in a separate namespaced bucket.
- **Sync cadence**: manual "Refresh now" always available, plus periodic background sync
  (interval TBD, e.g. daily) once a source is registered.
- **Guardrails**: HTTPS-only, timeout + response-size cap on fetches, CSV parsed as plain data
  only (never rendered/executed as HTML).

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

## Open questions (for later)
- External task source: exact periodic sync interval (e.g. daily?) and Google Cloud API key
  setup steps (project creation, Sheets API enablement, Android app restriction) — see
  "External task source" section above for the rest (now finalized).
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

