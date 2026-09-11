// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * Exercises [TaskDelivery.tick] against a real (Robolectric-simulated) Context/SharedPreferences
 * - regression coverage for DEFECTS.md item 4 (task dispatched before the active window opens,
 * manual pause not sticking, countdown ignoring Pause). Previously untestable at this level: see
 * that DEFECTS.md entry for why on-device verification was the only option until now.
 */
@RunWith(RobolectricTestRunner::class)
class TaskDeliveryTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    // Window 10:00-18:00 throughout, matching the scenario Vern reported.
    private val outsideWindow = LocalDateTime.of(2026, 1, 5, 9, 30)
    private val insideWindow = LocalDateTime.of(2026, 1, 5, 11, 0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = TaskDelivery.prefs(context)
        prefs.edit().clear().commit()
        val task = ManagedTask(
            id = "test-task",
            description = "Test task",
            category = "Testing",
            durationMinutes = 5,
            builtIn = false
        )
        prefs.edit()
            .putBoolean("setup_complete", true)
            .putString("managed_tasks", writeManagedTasks(listOf(task)))
            .putStringSet("selected_categories", setOf("Testing"))
            .putString("start_hour", "10")
            .putString("end_hour", "18")
            .putString("prompts_per_day", "6")
            .putInt("max_task_queue_size", 3)
            .putBoolean("background_prompts_enabled", true)
            .commit()
    }

    private fun queueSize(): Int =
        readTaskQueue(prefs.getString("task_queue", "[]") ?: "[]").size

    @Test
    fun tick_dispatchesOnce_whenInsideWindow() {
        val result = TaskDelivery.tick(context, force = false, now = insideWindow)
        assertTrue(result.dispatched)
        assertEquals(1, queueSize())
    }

    @Test
    fun tick_neverDispatchesOutsideWindow_evenIfEnabledFlagIsStillTrue() {
        // The exact defect: background_prompts_enabled reading true (e.g. left over from before
        // the window closed, or wrongly forced on by something else) must not let an automatic
        // tick dispatch before the window opens.
        val result = TaskDelivery.tick(context, force = false, now = outsideWindow)
        assertFalse(result.dispatched)
        assertEquals(0, queueSize())
    }

    @Test
    fun tick_manualPause_clearsCountdown_insideWindow() {
        // Seed prompts_window_start_epoch to match this "now" - otherwise reconcileState reads
        // the unset (0L) default as "a window has just opened" (correct real-world behavior on a
        // genuinely fresh install, which should force delivery on) and overrides the pause,
        // which isn't what this test is exercising.
        prefs.edit()
            .putLong("prompts_window_start_epoch", currentWindowStart(insideWindow, 10, 18).toEpochMillis())
            .putBoolean("background_prompts_enabled", false)
            .commit()
        val result = TaskDelivery.tick(context, force = false, now = insideWindow)
        assertFalse(result.dispatched)
        assertNull(result.nextDelayMillis)
        assertEquals(0L, prefs.getLong("next_dispatch_epoch_ms", 0L))
    }

    @Test
    fun tick_manualPause_outsideWindow_waitsForWindowOpen_doesNotDispatch() {
        prefs.edit()
            .putLong("prompts_window_start_epoch", currentWindowStart(outsideWindow, 10, 18).toEpochMillis())
            .putBoolean("background_prompts_enabled", false)
            .commit()
        val result = TaskDelivery.tick(context, force = false, now = outsideWindow)
        assertFalse(result.dispatched)
        assertNotNull(result.nextDelayMillis)
        assertEquals(0, queueSize())
    }

    @Test
    fun tick_forcedTap_stillDispatchesOutsideWindow_testingOverridePreserved() {
        val result = TaskDelivery.tick(context, force = true, now = outsideWindow)
        assertTrue(result.dispatched)
    }

    @Test
    fun tick_vacationMode_blocksAutomaticDispatch() {
        prefs.edit().putBoolean("vacation_mode", true).commit()
        val result = TaskDelivery.tick(context, force = false, now = insideWindow)
        assertFalse(result.dispatched)
        assertNull(result.nextDelayMillis)
        assertEquals(0, queueSize())
    }

    @Test
    fun tick_vacationMode_blocksForcedTapToo() {
        prefs.edit().putBoolean("vacation_mode", true).commit()
        val result = TaskDelivery.tick(context, force = true, now = insideWindow)
        assertFalse(result.dispatched)
        assertEquals(0, queueSize())
    }

    @Test
    fun nextDispatchEpoch_returnsNull_duringVacationMode() {
        prefs.edit().putBoolean("vacation_mode", true).commit()
        assertNull(TaskDelivery.nextDispatchEpoch(context, insideWindow))
    }

    @Test
    fun reconcile_doesNotForceDeliveryOn_whenManuallyPaused() {
        // Regression for the onCreate/onSettingsSaved bug: reconciling (what a relaunch does)
        // must never itself flip a manual pause back on outside of an actual window transition.
        prefs.edit()
            .putBoolean("background_prompts_enabled", false)
            .putLong("prompts_window_start_epoch", currentWindowStart(insideWindow, 10, 18).toEpochMillis())
            .commit()
        TaskDelivery.reconcile(context, insideWindow)
        assertFalse(prefs.getBoolean("background_prompts_enabled", true))
    }

    // "Stupid values" hardening: none of these should ever reach the app via the Settings Save
    // button (it already clamps), but loadSettings re-clamps anyway for state saved by an older
    // build, or poked directly into SharedPreferences (e.g. over adb) - see TaskDelivery.kt.

    @Test
    fun tick_survives_zeroMaxQueueSize_withoutCrashing() {
        prefs.edit().putInt("max_task_queue_size", 0).commit()
        val result = TaskDelivery.tick(context, force = false, now = insideWindow)
        // Coerced up to 1 rather than crashing List.take()/tick()'s capacity math on zero.
        assertTrue(result.dispatched)
        assertEquals(1, queueSize())
    }

    @Test
    fun tick_survives_negativeMaxQueueSize_withoutCrashing() {
        prefs.edit().putInt("max_task_queue_size", -5).commit()
        val result = TaskDelivery.tick(context, force = false, now = insideWindow)
        assertTrue(result.dispatched)
        assertEquals(1, queueSize())
    }

    @Test
    fun tick_survives_negativePromptsPerDay_withoutCrashing() {
        // Can't be typed via the UI (the field filters to digits only), but could be sitting in
        // an old/tampered prefs file - must behave like 0 ("automatic prompts off"), not crash.
        prefs.edit().putString("prompts_per_day", "-5").commit()
        val result = TaskDelivery.tick(context, force = false, now = insideWindow)
        assertFalse(result.dispatched)
        assertNull(result.nextDelayMillis)
    }

    @Test
    fun tick_survives_outOfRangeWindowHours_withoutCrashing() {
        // 99 and 250 are nonsensical hours a corrupted/tampered pref could still contain - must
        // clamp into 0..24 rather than reaching LocalTime.of() (which throws outside 0-23) or
        // producing a nonsensical never-matches window.
        prefs.edit()
            .putString("start_hour", "99")
            .putString("end_hour", "250")
            .commit()
        // Must not throw regardless of outcome.
        TaskDelivery.tick(context, force = false, now = insideWindow)
        TaskDelivery.tick(context, force = true, now = insideWindow)
        TaskDelivery.reconcile(context, insideWindow)
    }

    @Test
    fun tick_survives_emptyWindowHourStrings_withoutCrashing() {
        prefs.edit().putString("start_hour", "").putString("end_hour", "").commit()
        TaskDelivery.tick(context, force = false, now = insideWindow)
    }
}
