// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-84 main 2026-09-25
package com.microtasking.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * Regression coverage for "the countdown timer stops [with nothing to queue]... the window opens,
 * and the time actually stops. Also, you don't get any message" (reported by Vern, 2026-09-25).
 *
 * Root cause was entirely in `MicroTaskingApp`'s `LaunchedEffect`: it used to `return@LaunchedEffect`
 * immediately whenever `promptTasks` was empty, which also skipped starting the 1-second poll loop
 * further down in the SAME effect - freezing `clockMillis` (and, through it, every recomposition
 * that depends on it - the countdown text, the live window-open check, everything) for good, since
 * nothing else in the app advances that clock. This test proves the guard was never needed for
 * safety in the first place: `TaskDelivery.tick` already no-ops harmlessly on an empty pool via
 * `loadSettings` returning null the moment `eligiblePromptTasks` is empty, before touching
 * `next_dispatch_epoch_ms`, the queue, or anything else - so removing the guard and always running
 * the poll loop (`MainActivity.kt`) doesn't risk the crash-on-empty-pool class of bug the guard
 * might have originally been defending against (see DEFECTS.md item 4's `chooseWeightedTask`
 * follow-up, a related but distinct empty-pool crash that's already fixed elsewhere).
 *
 * The "No tasks available" message itself (`TaskPromptScreen`'s new `hasEligibleTasks` parameter)
 * is Compose UI and not exercised here - this covers the invariant the whole fix depends on.
 */
@RunWith(RobolectricTestRunner::class)
class EmptyPoolCountdownTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    private val now = LocalDateTime.of(2026, 1, 5, 11, 0) // inside a 10-18 window

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = TaskDelivery.prefs(context)
        prefs.edit().clear().commit()
        prefs.edit()
            .putBoolean("setup_complete", true)
            // No managed_tasks, no user_tasks, no selected_categories - an empty pool either from a
            // fresh install or every category having been unchecked.
            .putString("start_hour", "10")
            .putString("end_hour", "18")
            .putString("prompts_per_day", "6")
            .putInt("max_task_queue_size", 3)
            .putBoolean("background_prompts_enabled", true)
            .commit()
    }

    @Test
    fun tick_onAnEmptyPool_isAHarmlessNoOp_regardlessOfWindowOrForce() {
        val before = prefs.getLong("next_dispatch_epoch_ms", 0L)

        val automatic = TaskDelivery.tick(context, now = now)
        assertFalse(automatic.dispatched)
        assertNull(automatic.nextDelayMillis)

        val forced = TaskDelivery.tick(context, force = true, now = now)
        assertFalse(forced.dispatched)
        assertFalse(forced.queueFull)

        assertEquals(
            "an empty pool must never arm/touch next_dispatch_epoch_ms - nothing will ever be due",
            before, prefs.getLong("next_dispatch_epoch_ms", 0L)
        )
    }

    // DEFECTS.md item 7 note: found while writing the above, but NOT fixed here (out of scope -
    // Vern reported the countdown/message symptom, not this one). loadSettings returning null
    // short-circuits tick() before reconcileState ever runs, so day-rollover/streak bookkeeping
    // (clean-day streak, the per-day "N in a row" reset, etc.) also silently stops advancing while
    // the pool is empty - same root shape as the countdown bug, different symptom. This test
    // documents the current (unfixed) behavior rather than asserting the fixed one.
    @Test
    fun tick_currentlyDoesNotReconcileDayRolloverEitherOnAnEmptyPool_knownGapNotFixedHere() {
        prefs.edit().putLong("last_reconcile_day", now.toLocalDate().minusDays(1).toEpochDay()).putInt("streak", 5).commit()

        TaskDelivery.tick(context, now = now)

        assertEquals(
            "known gap: the day did NOT roll over, because tick() returns before reconcileState runs " +
                "when the pool is empty - see DEFECTS.md item 7",
            now.toLocalDate().minusDays(1).toEpochDay(), prefs.getLong("last_reconcile_day", -1L)
        )
    }
}
