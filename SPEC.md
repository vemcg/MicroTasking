# MicroTasking — Feature Spec (v0.1, pre-implementation)

Free alternative to "Today Is The Day". Core skill being trained: when prompted, don't
deliberate — just do the task immediately.

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
- Each task has a category and a duration tier: 5 / 10 / 15 min.
- Tasks are **repeatable**: completing a task does not remove it from the pool — it goes back
  in and can be selected again later. The pool is never "used up".
- Selection is random from active-category tasks, but **weighted adaptively** by the user's
  current score (see below) — three difficulty tiers (low/normal/high score) shift the odds
  toward shorter or longer tasks.

## On-prompt interaction
- Full-screen task view shows the task and two actions: **Mark Complete** and **Snooze**.
- Snooze: user picks a duration (freeform time frame, e.g. "20 min"). Clock pauses while snoozed.
  Task reappears at a **random point shortly after** the snooze duration elapses (small random
  buffer added, not the exact instant).
- No hard cap on snooze length/count yet (open — revisit if abuse/avoidance becomes an issue).

## Task stack (multiple concurrent tasks)
- Tasks are **stacked**, not single: more than one prompted task can be active/pending at once,
  each running its own independent budget/deadline clock.
- New setting **max concurrent tasks** (e.g. default 3) caps how many active tasks can be on
  the stack at the same time.
- If a new prompt fires while the stack is already at the limit, the **oldest** task on the
  stack is immediately marked **failed** (counts as not-completed against scoring) and removed
  to make room for the new one.
- The full-screen view shows all currently-active stacked tasks (not just one), each with its
  own Mark Complete / Snooze actions and its own remaining-time indicator.

## Timing & scoring
- Each task has a budget (5/10/15 min) and a deadline of 2x budget.
  - Completed within budget → on-time.
  - Completed between budget and 2x budget → late but counted.
  - Not completed within 2x budget (accounting for paused snooze time) → counts as not-completed.
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

## External task source (planned, design pending)
- Goal: let the user optionally point the app at a **published Google Sheet (CSV export URL)**
  as an additional/alternate task-pack source, refreshed **periodically** (not just manual pull).
- Still to design before implementation (do not build yet): sync interval/trigger, expected
  sheet column layout (must map to task/category/duration fields), how imported tasks merge
  with or replace the local pool, conflict handling if a row is edited/removed upstream, offline
  behavior when the sheet is unreachable, and whether this is per-category or a full pool swap.

## Distribution & updates
- Provide a **QR code** (e.g. in Settings/About) that links to the latest release APK for easy
  sideload install/update on another device — for sharing/installing the app itself, not for
  transferring in-app data.
- **Implemented** via GitHub Actions (`.github/workflows/release-apk.yml`): every push to
  `main` builds a debug APK, tags/publishes it as a GitHub Release, generates a QR code image
  pointing at that release's direct download URL (`scripts/generate_install_page.py`), and
  publishes a small install page + QR to GitHub Pages (`gh-pages` branch, `docs/` folder). Each
  push produces a new tag (`v<versionName>-<run number>`), so the QR always points at the
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
  that opens the full-screen view on tap; the completion timer still starts when the prompt
  fires, not when tapped.
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
- External task source (Google Sheet): sync interval, sheet schema/column mapping, merge vs.
  replace semantics, conflict/offline handling — see "External task source" section above.
- QR install/update: where the APK is hosted (e.g. GitHub Releases) and how the QR content
  gets generated/kept in sync with the latest build.
- Max concurrent tasks default value and whether it's adjustable per-category or global only.
- Snooze caps (max length / max count per task).
- Exact rolling-window size (N) and EWMA decay factor for scores.
- Exact weighting curve for adaptive difficulty tiers.
- Full list of categories beyond the starting two (Decluttering, Cleaning).
- Editing/deleting tasks from the pool, and adding/removing categories (basic CRUD assumed
  but not detailed yet).
