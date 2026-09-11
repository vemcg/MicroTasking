// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

data class UserTask(val description: String, val category: String, val enabled: Boolean)

fun readUserTasks(json: String): List<UserTask> = runCatching {
    val tasks = JSONArray(json)
    List(tasks.length()) { index ->
        val task = tasks.getJSONObject(index)
        UserTask(
            description = task.getString("description"),
            category = task.getString("category"),
            enabled = task.getBoolean("enabled")
        )
    }
}.getOrDefault(emptyList())

fun writeUserTasks(tasks: List<UserTask>): String = JSONArray().apply {
    tasks.forEach { task ->
        put(JSONObject().apply {
            put("description", task.description)
            put("category", task.category)
            put("enabled", task.enabled)
        })
    }
}.toString()

fun eligiblePromptTasks(
    managedTasks: List<ManagedTask>,
    legacyUserTasks: List<UserTask>,
    selectedCategories: Set<String>
): List<ManagedTask> {
    val managedEligibleTasks = managedTasks
        .filter { task ->
            task.enabled && !task.temporarilyUnavailable && !task.neverSuggest &&
                task.category in selectedCategories
        }
    val legacyEligibleTasks = legacyUserTasks
        .filter { it.enabled && it.category in selectedCategories }
        .mapIndexed { index, task ->
            ManagedTask(
                id = "legacy-$index-${task.description}",
                description = task.description,
                category = task.category,
                durationMinutes = 5,
                builtIn = false
            )
        }
    return managedEligibleTasks + legacyEligibleTasks
}

/**
 * The single producer of queued tasks, shared by the foreground pacing loop and the background
 * alarm receiver so both read and write the exact same persisted state instead of drifting apart.
 */
object TaskDelivery {
    const val PREFS_NAME = "microtasking_settings"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class Settings(
        val startHour: Int,
        val endHour: Int,
        val promptsPerDay: Int,
        val maxQueueSize: Int,
        val promptTasks: List<ManagedTask>,
        val activeCategoryOrder: List<String>
    )

    private fun loadSettings(context: Context): Settings? {
        val prefs = prefs(context)
        if (!prefs.getBoolean("setup_complete", false)) return null
        val managedTasks = readManagedTasks(prefs.getString("managed_tasks", "[]") ?: "[]")
        val userTasks = readUserTasks(prefs.getString("user_tasks", "[]") ?: "[]")
        val selectedCategories = prefs.getStringSet("selected_categories", emptySet()) ?: emptySet()
        val promptTasks = eligiblePromptTasks(managedTasks, userTasks, selectedCategories)
        if (promptTasks.isEmpty()) return null
        val availableCategories = (managedTasks.map { it.category } + userTasks.map { it.category }).distinct()
        // Settings.kt's Save button already clamps these before persisting, but this is the one
        // chokepoint every scheduling computation (division included) reads through, so it's
        // sanitized again here too - defends against a value saved by an older build (before
        // those clamps existed), or one poked directly into SharedPreferences (e.g. over adb).
        // Out-of-range hours can otherwise reach java.time.LocalTime.of(), which throws on a
        // negative hour; a maxQueueSize <= 0 can reach List.take() with a negative count, which
        // throws; a negative promptsPerDay would otherwise bypass the "<= 0 means off" checks.
        return Settings(
            startHour = ((prefs.getString("start_hour", "9") ?: "9").toIntOrNull() ?: 9).coerceIn(0, 24),
            endHour = ((prefs.getString("end_hour", "21") ?: "21").toIntOrNull() ?: 21).coerceIn(0, 24),
            promptsPerDay = ((prefs.getString("prompts_per_day", "6") ?: "6").toIntOrNull() ?: 6).coerceAtLeast(0),
            maxQueueSize = prefs.getInt("max_task_queue_size", 3).coerceAtLeast(1),
            promptTasks = promptTasks,
            activeCategoryOrder = availableCategories.filter { it in selectedCategories }
        )
    }

    /**
     * Result of one [tick]: [nextDelayMillis] is how long the caller should wait before ticking
     * again (null = nothing to schedule right now), [dispatched] is whether a task was actually
     * added to the queue (the caller decides whether to notify), and [queueFull] flags a forced
     * dispatch that did nothing because the queue was already at capacity (normal mode only).
     */
    data class TickResult(val nextDelayMillis: Long?, val dispatched: Boolean, val queueFull: Boolean)

    private class ReconciledState(
        var queue: List<TaskStackEntry>,
        var streak: Int,
        val longestStreak: Int,
        var lastDay: Long,
        var windowStartEpoch: Long,
        var backgroundPromptsEnabled: Boolean,
        var timeoutStreak: Int,
        var lastOutcome: TaskLifecycleState,
        var withinWindow: Boolean,
        var cleanDayStreak: Int,
        var cleanDayStreakLongest: Int,
        var dayHadCompletion: Boolean,
        var dayHadFailure: Boolean
    ) {
        fun persist(prefs: SharedPreferences) {
            prefs.edit()
                .putString("task_queue", writeTaskQueue(queue))
                .putInt("streak", streak)
                .putInt("longest_streak", maxOf(longestStreak, streak))
                .putLong("last_reconcile_day", lastDay)
                .putLong("prompts_window_start_epoch", windowStartEpoch)
                .putBoolean("background_prompts_enabled", backgroundPromptsEnabled)
                .putInt("timeout_streak", timeoutStreak)
                .putString("last_outcome", lastOutcome.name)
                .putBoolean("prompts_within_window", withinWindow)
                .putInt("clean_day_streak", cleanDayStreak)
                .putInt("clean_day_streak_longest", maxOf(cleanDayStreakLongest, cleanDayStreak))
                .putBoolean("day_had_completion", dayHadCompletion)
                .putBoolean("day_had_failure", dayHadFailure)
                .apply()
        }
    }

    /**
     * Reconciles persisted state against wall-clock reality. Three independent things can happen
     * here, matched to reality rather than to each other:
     *  - The active window just closed (inside it on the previous reconcile, outside it now):
     *    delivery is automatically paused (backgroundPromptsEnabled = false). The queue is left
     *    intact - a carried-over task simply waits until the user does it or it gets pushed off
     *    the top. Keyed off the inside->outside *transition* so it fires exactly once per close
     *    and won't re-clobber a manual Resume pressed afterward.
     *  - The calendar day has changed: the per-task "N in a row" streak resets (it's a within-day
     *    thing), and the clean-day streak absorbs the day(s) that just ended - a day with a
     *    failure resets it, a day with a completion and no failure advances it, an idle day
     *    leaves it alone. A multi-day gap (app closed over a weekend) collapses to one evaluation.
     *  - A new window occurrence beginning: automatically resumes delivery
     *    (backgroundPromptsEnabled = true).
     * Outside of these automatic transitions, backgroundPromptsEnabled is left alone - a manual
     * Pause/Resume sticks until the next automatic transition touches it.
     */
    private fun reconcileState(prefs: SharedPreferences, settings: Settings, now: LocalDateTime): ReconciledState {
        val withinWindow = isWithinActiveWindow(now, settings.startHour, settings.endHour)
        // Defaults to the current membership so a fresh install (or an upgrade that predates this
        // key) never reads as a spurious just-closed transition on its first reconcile.
        val wasWithinWindow = prefs.getBoolean("prompts_within_window", withinWindow)
        val state = ReconciledState(
            queue = readTaskQueue(prefs.getString("task_queue", "[]") ?: "[]"),
            streak = prefs.getInt("streak", 0),
            longestStreak = prefs.getInt("longest_streak", 0),
            // Fall back to the pre-rework day key so an upgrade doesn't read as a spurious
            // midnight rollover and reset the per-task streak on first launch.
            lastDay = prefs.getLong("last_reconcile_day", prefs.getLong("prompts_count_epoch_day", -1L)),
            windowStartEpoch = prefs.getLong("prompts_window_start_epoch", 0L),
            backgroundPromptsEnabled = prefs.getBoolean("background_prompts_enabled", true),
            timeoutStreak = prefs.getInt("timeout_streak", 0),
            lastOutcome = runCatching {
                TaskLifecycleState.valueOf(prefs.getString("last_outcome", TaskLifecycleState.COMPLETED.name)!!)
            }.getOrDefault(TaskLifecycleState.COMPLETED),
            withinWindow = withinWindow,
            cleanDayStreak = prefs.getInt("clean_day_streak", 0),
            cleanDayStreakLongest = prefs.getInt("clean_day_streak_longest", 0),
            dayHadCompletion = prefs.getBoolean("day_had_completion", false),
            dayHadFailure = prefs.getBoolean("day_had_failure", false)
        )

        if (wasWithinWindow && !withinWindow) {
            state.backgroundPromptsEnabled = false
        }

        val today = now.toLocalDate().toEpochDay()
        if (today != state.lastDay) {
            state.streak = 0
            if (state.lastDay >= 0L) {
                state.cleanDayStreak = rolledCleanDayStreak(
                    state.cleanDayStreak, state.dayHadCompletion, state.dayHadFailure
                )
                state.cleanDayStreakLongest = maxOf(state.cleanDayStreakLongest, state.cleanDayStreak)
            }
            state.dayHadCompletion = false
            state.dayHadFailure = false
            state.lastDay = today
        }

        val newWindowStart = currentWindowStart(now, settings.startHour, settings.endHour).toEpochMillis()
        if (newWindowStart != state.windowStartEpoch) {
            state.windowStartEpoch = newWindowStart
            state.backgroundPromptsEnabled = true
        }

        return state
    }

    /**
     * Reconciles day/window rollovers against persisted state without attempting a delivery.
     * Call this at app launch, before reading persisted streak/queue into UI state - otherwise a
     * day or window boundary crossed while the app was closed wouldn't show up until whatever
     * happens to run the first delivery tick, which could be a long wait.
     */
    fun reconcile(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val settings = loadSettings(context) ?: return
        val prefs = prefs(context)
        reconcileState(prefs, settings, now).persist(prefs)
    }

    /**
     * Runs one pacing tick: reconciles state (see [reconcileState]), decides whether to add a task
     * to the queue, computes the fixed interval until the next tick, and persists everything
     * (including next_dispatch_epoch_ms, which drives the on-screen countdown and the background
     * alarm). Every tick that gets past the gates adds a task to the queue:
     *  - Queue below capacity -> the new task just joins it.
     *  - Queue already full -> the new arrival pushes the oldest actionable task(s) off the top,
     *    and that eviction is a timeout. This is the only automatic failure path now that
     *    window-close no longer abandons the queue - a stale task the user never acted on is
     *    aged out by the regular cadence.
     * The one exception is a manual "give me one now" tap into an already-full queue in normal
     * mode: that's a no-op (flagged via [TickResult.queueFull]), because an impatient tap
     * shouldn't cost you a task - only the automatic cadence times things out. In rapid-testing
     * mode even the tap evicts, so a developer can burn through the queue quickly.
     * Delivery is gated on backgroundPromptsEnabled (kept in sync with the window by
     * reconcileState) unless [force] is set.
     */
    fun tick(context: Context, force: Boolean = false, now: LocalDateTime = LocalDateTime.now()): TickResult {
        val settings = loadSettings(context) ?: return TickResult(null, dispatched = false, queueFull = false)
        val prefs = prefs(context)
        val state = reconcileState(prefs, settings, now)

        // Vacation mode is a hard override: nothing dispatches, not even a manual/forced tap,
        // until the user goes into Settings and unchecks it themselves. Day/window bookkeeping
        // above still runs so streaks don't go stale, but no next tick gets scheduled - not even
        // the alarm gets armed (see nextDispatchEpoch below), so nothing wakes this up.
        if (prefs.getBoolean("vacation_mode", false)) {
            state.persist(prefs)
            prefs.edit().remove("next_dispatch_epoch_ms").apply()
            return TickResult(null, dispatched = false, queueFull = false)
        }

        // An automatic tick may never dispatch outside the active window, full stop - this is
        // checked directly against the clock, not just via backgroundPromptsEnabled (which
        // reconcileState keeps in sync with the window via edge-triggered transitions, but that's
        // one more thing that can drift; dispatch itself shouldn't depend on nothing else in the
        // codebase ever mis-setting that flag - see DEFECTS.md item 4). A forced/manual tap still
        // bypasses both checks (existing "test outside the window" override).
        val withinWindowNow = isWithinActiveWindow(now, settings.startHour, settings.endHour)
        if (!force && (!state.backgroundPromptsEnabled || !withinWindowNow)) {
            state.persist(prefs)
            val editor = prefs.edit()
            val result = if (withinWindowNow) {
                // Paused by the user while the window is open: nothing is scheduled until Resume.
                editor.remove("next_dispatch_epoch_ms")
                TickResult(null, dispatched = false, queueFull = false)
            } else {
                // Outside the window - auto-paused, or a manual pause taken outside it either
                // way: wait for the window to open. The window-open transition is the one
                // automatic thing (besides a manual Resume) allowed to clear a pause.
                val delay = millisUntilWindowOpens(now, settings.startHour, settings.endHour)
                editor.putLong("next_dispatch_epoch_ms", now.toEpochMillis() + delay)
                TickResult(delay, dispatched = false, queueFull = false)
            }
            editor.apply()
            return result
        }

        if (settings.promptsPerDay <= 0) {
            state.persist(prefs)
            prefs.edit().remove("next_dispatch_epoch_ms").apply()
            return TickResult(null, dispatched = false, queueFull = false)
        }

        val actionable = state.queue.filter { it.isActionable() }
        val activeCount = actionable.size
        // The queue can't hold more distinct tasks than the eligible pool has - otherwise a new
        // dispatch would have to repeat one already queued, and the task screen crashes on a
        // duplicate LazyColumn key. Cap the effective capacity at the pool size.
        val effectiveMaxQueueSize = minOf(settings.maxQueueSize, settings.promptTasks.size)
        val atCapacity = activeCount >= effectiveMaxQueueSize

        var dispatched = false
        var queueFull = false

        if (force && atCapacity && !isRapidTestingMode(settings.promptsPerDay)) {
            // Normal mode: a manual "give me one now" tap does nothing while the queue is full.
            queueFull = true
        } else {
            val keepCount = (effectiveMaxQueueSize - 1).coerceAtLeast(0)
            val kept = actionable.takeLast(keepCount)
            if (activeCount > keepCount) {
                // The queue is full and a new task is arriving anyway (automatic cadence, or a
                // forced tap in rapid-testing mode): the oldest actionable entries get pushed off
                // the top without the user ever having acted on them - that's a timeout.
                val timedOutCount = activeCount - keepCount
                state.streak = 0
                state.timeoutStreak += timedOutCount
                state.lastOutcome = TaskLifecycleState.TIMED_OUT
                state.dayHadFailure = true
                state.cleanDayStreak = 0
            }
            // Never hand back a task that's still sitting in the queue.
            val keptIds = kept.mapTo(mutableSetOf()) { it.task.id }
            val candidates = settings.promptTasks.filterNot { it.id in keptIds }
                .ifEmpty { settings.promptTasks }
            val nextTask = chooseWeightedTask(
                tasks = candidates,
                activeCategoryOrder = settings.activeCategoryOrder,
                previousTaskId = kept.lastOrNull()?.task?.id
            )
            state.queue = kept + TaskStackEntry(nextTask)
            dispatched = true
        }

        val interval = fixedDispatchIntervalMillis(
            now, settings.startHour, settings.endHour, settings.promptsPerDay, dispatched
        )
        state.persist(prefs)
        prefs.edit().apply {
            if (interval == null) remove("next_dispatch_epoch_ms")
            else putLong("next_dispatch_epoch_ms", now.toEpochMillis() + interval)
        }.apply()
        return TickResult(interval, dispatched, queueFull)
    }

    /**
     * Read-only: when the next dispatch is due, as an epoch-millis instant. Uses the value the
     * last [tick] persisted; falls back to a fresh interval computation when nothing is stored
     * yet. Never dispatches - safe for arming the background alarm.
     */
    fun nextDispatchEpoch(context: Context, now: LocalDateTime = LocalDateTime.now()): Long? {
        val prefs = prefs(context)
        // On vacation: never arm anything, so nothing wakes this up until the box is unchecked.
        if (prefs.getBoolean("vacation_mode", false)) return null
        val stored = prefs.getLong("next_dispatch_epoch_ms", 0L)
        if (stored > 0L) return stored
        val settings = loadSettings(context) ?: return null
        // Manually paused while the window is open: nothing is scheduled until Resume.
        if (!prefs.getBoolean("background_prompts_enabled", true) &&
            isWithinActiveWindow(now, settings.startHour, settings.endHour)
        ) return null
        val interval = fixedDispatchIntervalMillis(
            now, settings.startHour, settings.endHour, settings.promptsPerDay, dispatched = false
        ) ?: return null
        return now.toEpochMillis() + interval
    }
}
