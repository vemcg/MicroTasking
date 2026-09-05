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
        return Settings(
            startHour = (prefs.getString("start_hour", "9") ?: "9").toIntOrNull() ?: 0,
            endHour = (prefs.getString("end_hour", "21") ?: "21").toIntOrNull() ?: 24,
            promptsPerDay = (prefs.getString("prompts_per_day", "6") ?: "6").toIntOrNull() ?: 0,
            maxQueueSize = prefs.getInt("max_task_queue_size", 3),
            promptTasks = promptTasks,
            activeCategoryOrder = availableCategories.filter { it in selectedCategories }
        )
    }

    /**
     * Millis until the next thing that needs to happen: either a delivery, or - if that would be
     * later than the window closing - the window close itself, so a leftover queue gets abandoned
     * right when the window closes instead of sitting stale until whenever the app next happens
     * to check. Null only when nothing should ever run right now (outside the window, wait for it
     * to open instead - see millisUntilWindowOpens).
     */
    fun computeNextDelayMillis(context: Context): Long? {
        val settings = loadSettings(context) ?: return null
        val prefs = prefs(context)
        val now = LocalDateTime.now()
        if (!isWithinActiveWindow(now, settings.startHour, settings.endHour)) {
            return millisUntilWindowOpens(now, settings.startHour, settings.endHour)
        }
        val deliveredInWindow = if (now.toLocalDate().toEpochDay() == prefs.getLong("prompts_count_epoch_day", -1L)) {
            prefs.getInt("prompts_delivered_in_window", 0)
        } else {
            0
        }
        val deliveryDelay = nextPromptDelayMillis(now, settings.startHour, settings.endHour, settings.promptsPerDay, deliveredInWindow)
        val closeDelay = millisUntilWindowCloses(now, settings.startHour, settings.endHour) ?: return deliveryDelay
        return if (deliveryDelay == null) closeDelay else minOf(deliveryDelay, closeDelay)
    }

    private class ReconciledState(
        var queue: List<TaskStackEntry>,
        var streak: Int,
        val longestStreak: Int,
        var deliveredInWindow: Int,
        var countEpochDay: Long,
        var windowStartEpoch: Long,
        var backgroundPromptsEnabled: Boolean,
        var timeoutStreak: Int,
        var lastOutcome: TaskLifecycleState
    ) {
        fun persist(prefs: SharedPreferences) {
            prefs.edit()
                .putString("task_queue", writeTaskQueue(queue))
                .putInt("streak", streak)
                .putInt("longest_streak", maxOf(longestStreak, streak))
                .putInt("prompts_delivered_in_window", deliveredInWindow)
                .putLong("prompts_count_epoch_day", countEpochDay)
                .putLong("prompts_window_start_epoch", windowStartEpoch)
                .putBoolean("background_prompts_enabled", backgroundPromptsEnabled)
                .putInt("timeout_streak", timeoutStreak)
                .putString("last_outcome", lastOutcome.name)
                .apply()
        }
    }

    /**
     * Reconciles persisted state against wall-clock reality. Three independent things can happen
     * here, matched to reality rather than to each other:
     *  - Outside the active window: anything still actionable in the queue is abandoned (scored
     *    the same as a manual Abandon) and the streak resets. This is what "the window closed"
     *    means for the queue - by the time the window opens again there should be nothing left.
     *  - The calendar day has changed since the delivery count was last reset: the daily count
     *    resets to 0, and so does the streak - a streak is a daily thing, and rolling into a new
     *    day always starts it fresh even if nothing was technically abandoned (e.g. an
     *    always-active window, which never "closes" and so never hits the abandon-driven reset
     *    above on its own). Tied to midnight, not to window open/close, so a manual pause
     *    spanning a midnight still gets this right without needing the window to cycle first.
     *  - A new window occurrence beginning: only clears a pause (a pause only lasts for the
     *    window it was pressed in). Nothing else - if the queue somehow isn't empty at this point
     *    (it should always be empty, since close already cleared it), open leaves it alone and
     *    simply builds on top of it rather than abandoning/scoring it a second time.
     */
    private fun reconcileState(prefs: SharedPreferences, settings: Settings, now: LocalDateTime): ReconciledState {
        val state = ReconciledState(
            queue = readTaskQueue(prefs.getString("task_queue", "[]") ?: "[]"),
            streak = prefs.getInt("streak", 0),
            longestStreak = prefs.getInt("longest_streak", 0),
            deliveredInWindow = prefs.getInt("prompts_delivered_in_window", 0),
            countEpochDay = prefs.getLong("prompts_count_epoch_day", -1L),
            windowStartEpoch = prefs.getLong("prompts_window_start_epoch", 0L),
            backgroundPromptsEnabled = prefs.getBoolean("background_prompts_enabled", true),
            timeoutStreak = prefs.getInt("timeout_streak", 0),
            lastOutcome = runCatching {
                TaskLifecycleState.valueOf(prefs.getString("last_outcome", TaskLifecycleState.COMPLETED.name)!!)
            }.getOrDefault(TaskLifecycleState.COMPLETED)
        )

        if (!isWithinActiveWindow(now, settings.startHour, settings.endHour) && state.queue.any { it.isActionable() }) {
            state.queue = state.queue.map { if (it.isActionable()) it.abandon() else it }
            state.streak = 0
            state.timeoutStreak = 0
            state.lastOutcome = TaskLifecycleState.ABANDONED
        }

        val today = now.toLocalDate().toEpochDay()
        if (today != state.countEpochDay) {
            state.deliveredInWindow = 0
            state.countEpochDay = today
            state.streak = 0
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
    fun reconcile(context: Context) {
        val settings = loadSettings(context) ?: return
        val prefs = prefs(context)
        reconcileState(prefs, settings, LocalDateTime.now()).persist(prefs)
    }

    /**
     * Runs one delivery slot: reconciles state (see [reconcileState]), then - only when inside
     * the window - either adds a task to the queue or, if paused, just consumes the delivery slot
     * silently, so a long pause can't cram a burst of catch-up deliveries in when it ends. Returns
     * true if a task was actually added (the caller decides whether to notify from that).
     */
    fun deliverOrConsumeSlot(context: Context): Boolean {
        val settings = loadSettings(context) ?: return false
        val prefs = prefs(context)
        val now = LocalDateTime.now()
        val state = reconcileState(prefs, settings, now)

        var taskAdded = false
        if (isWithinActiveWindow(now, settings.startHour, settings.endHour)) {
            if (state.backgroundPromptsEnabled) {
                val activeTasks = state.queue.filter { it.isActionable() }
                val keepCount = (settings.maxQueueSize - 1).coerceAtLeast(0)
                if (activeTasks.size >= settings.maxQueueSize) {
                    // The oldest entries beyond keepCount are about to be pushed off the queue by
                    // the new arrival below without the user ever having acted on them - that's a
                    // timeout, not a completion, and needs to be recorded as such rather than
                    // silently vanishing.
                    val timedOutCount = activeTasks.size - keepCount
                    state.streak = 0
                    state.timeoutStreak += timedOutCount
                    state.lastOutcome = TaskLifecycleState.TIMED_OUT
                }
                val nextTask = chooseWeightedTask(
                    tasks = settings.promptTasks,
                    activeCategoryOrder = settings.activeCategoryOrder,
                    previousTaskId = activeTasks.lastOrNull()?.task?.id
                )
                state.queue = activeTasks.takeLast(keepCount) + TaskStackEntry(nextTask)
                taskAdded = true
            }
            state.deliveredInWindow++
        }

        state.persist(prefs)
        return taskAdded
    }
}
