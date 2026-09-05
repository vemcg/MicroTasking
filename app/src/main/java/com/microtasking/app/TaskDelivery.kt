package com.microtasking.app

import android.content.Context
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

    /**
     * Runs one tick against persisted state. Three independent things can happen here, matched to
     * wall-clock reality rather than to each other:
     *  - Outside the active window: anything still actionable in the queue is abandoned (scored
     *    the same as a manual Abandon) and the streak resets. This is what "the window closed"
     *    means for the queue - by the time the window opens again there should be nothing left.
     *  - The calendar day has changed since the delivery count was last reset: the daily count
     *    resets to 0. Tied to midnight, not to window open/close - an overnight window is still
     *    "open" straight through midnight, and a manual pause spanning a midnight shouldn't need
     *    the window to cycle before the count is right again; it's just checked on whatever tick
     *    happens to run first once the day has actually turned over (which, manually, means the
     *    first resume after midnight).
     *  - A new window occurrence beginning: only clears a pause (a pause only lasts for the
     *    window it was pressed in). Nothing else - if the queue somehow isn't empty at this point
     *    (it should always be empty, since close already cleared it), open leaves it alone and
     *    simply builds on top of it rather than abandoning/scoring it a second time.
     * Only when inside the window does it then either add a task to the queue or - if paused -
     * just consume the delivery slot silently, so a long pause can't cram a burst of catch-up
     * deliveries in when it ends. Returns true if a task was actually added (the caller decides
     * whether to notify from that).
     */
    fun deliverOrConsumeSlot(context: Context): Boolean {
        val settings = loadSettings(context) ?: return false
        val prefs = prefs(context)
        val now = LocalDateTime.now()

        var queue = readTaskQueue(prefs.getString("task_queue", "[]") ?: "[]")
        var streak = prefs.getInt("streak", 0)
        val longestStreak = prefs.getInt("longest_streak", 0)
        var deliveredInWindow = prefs.getInt("prompts_delivered_in_window", 0)
        var countEpochDay = prefs.getLong("prompts_count_epoch_day", -1L)
        var windowStartEpoch = prefs.getLong("prompts_window_start_epoch", 0L)
        var backgroundPromptsEnabled = prefs.getBoolean("background_prompts_enabled", true)

        val withinWindow = isWithinActiveWindow(now, settings.startHour, settings.endHour)
        if (!withinWindow && queue.any { it.isActionable() }) {
            queue = queue.map { if (it.isActionable()) it.abandon() else it }
            streak = 0
        }

        val today = now.toLocalDate().toEpochDay()
        if (today != countEpochDay) {
            deliveredInWindow = 0
            countEpochDay = today
        }

        val newWindowStart = currentWindowStart(now, settings.startHour, settings.endHour).toEpochMillis()
        if (newWindowStart != windowStartEpoch) {
            windowStartEpoch = newWindowStart
            backgroundPromptsEnabled = true
        }

        var taskAdded = false
        if (withinWindow) {
            if (backgroundPromptsEnabled) {
                val activeTasks = queue.filter { it.isActionable() }
                if (activeTasks.size >= settings.maxQueueSize) {
                    streak = 0
                }
                val nextTask = chooseWeightedTask(
                    tasks = settings.promptTasks,
                    activeCategoryOrder = settings.activeCategoryOrder,
                    previousTaskId = activeTasks.lastOrNull()?.task?.id
                )
                queue = activeTasks.takeLast((settings.maxQueueSize - 1).coerceAtLeast(0)) + TaskStackEntry(nextTask)
                taskAdded = true
            }
            deliveredInWindow++
        }

        prefs.edit()
            .putString("task_queue", writeTaskQueue(queue))
            .putInt("streak", streak)
            .putInt("longest_streak", maxOf(longestStreak, streak))
            .putInt("prompts_delivered_in_window", deliveredInWindow)
            .putLong("prompts_count_epoch_day", countEpochDay)
            .putLong("prompts_window_start_epoch", windowStartEpoch)
            .putBoolean("background_prompts_enabled", backgroundPromptsEnabled)
            .apply()

        return taskAdded
    }
}
