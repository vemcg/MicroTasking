// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-84 main 2026-09-25
package com.microtasking.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * Root cause of "window opens in 11 hours" at 5 AM for a 10 AM-6 PM window (reported by Vern,
 * 2026-09-25 - confirmed real via `diagnostic.log`, which showed heavy forced-tap activity in
 * exactly that window: repeated QUEUED/STARTED/COMPLETED entries outside the 10-18 window, which
 * only ever happen on a forced tap since an automatic tick can't dispatch outside the window).
 *
 * `next_dispatch_epoch_ms` serves two different purposes that get conflated: it's both "when the
 * background alarm should next fire" (fine to compute via the fixed pacing formula even outside
 * the window - see `fixedDispatchIntervalMillis`'s own doc comment, "a plain fixed cadence, not a
 * window gate") and, until now, the literal source of the "Window opens in..." countdown text.
 * `TaskDelivery.tick(force = true)` bypasses the window gate and dispatches even when outside the
 * window (existing, intentional "test/force it now" behavior - triggered by tapping the
 * countdown text itself, which is exactly what "tap for one now" invites), then overwrites
 * `next_dispatch_epoch_ms` with `now + fixedDispatchIntervalMillis(...)` - a pacing interval
 * (hours, derived from prompts-per-day and window length) that has nothing to do with when the
 * window opens. The very next render, still outside the window, showed that pacing value
 * mislabeled as "Window opens in X".
 */
@RunWith(RobolectricTestRunner::class)
class ForcedTapWindowCountdownTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    // 5 AM, matching the report: outside the 10-18 window, 5 hours before it genuinely opens.
    private val fiveAM = LocalDateTime.of(2026, 1, 5, 5, 0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = TaskDelivery.prefs(context)
        prefs.edit().clear().commit()
        val tasks = (1..3).map { ManagedTask("t$it", "Task $it", "Testing", 5, false) }
        prefs.edit()
            .putBoolean("setup_complete", true)
            .putString("managed_tasks", writeManagedTasks(tasks))
            .putStringSet("selected_categories", setOf("Testing"))
            .putString("start_hour", "10")
            .putString("end_hour", "18")
            .putString("prompts_per_day", "2")
            .putInt("max_task_queue_size", 10)
            .putBoolean("background_prompts_enabled", true)
            .commit()
    }

    @Test
    fun aForcedTapOutsideTheWindow_writesAPacingValue_notWhenTheWindowOpens() {
        val correctWindowOpenWait = millisUntilWindowOpens(fiveAM, 10, 18)
        assertEquals("sanity check: 5 AM to 10 AM is 5 hours", 5 * 60 * 60 * 1000L, correctWindowOpenWait)

        // The forced tap the user's "tap for one now" performed - exactly what a tap on the
        // countdown text does (MainActivity.forceDispatchNow -> tick(force = true)).
        TaskDelivery.tick(context, force = true, now = fiveAM)
        val epochAfterForcedTap = prefs.getLong("next_dispatch_epoch_ms", 0L)

        assertNotEquals(
            "the stored epoch no longer represents \"when does the window open\" - it's a pacing " +
                "interval now, which is exactly why the UI must stop reading it for that text",
            fiveAM.toEpochMillis() + correctWindowOpenWait,
            epochAfterForcedTap
        )
    }

    @Test
    fun independentOfWhateverForcedTapsDid_millisUntilWindowOpensIsAlwaysCorrect() {
        // The fix: the "Window opens in..." text now comes from this pure function directly (live
        // off the clock and settings), not from next_dispatch_epoch_ms - so no forced tap, no
        // matter how many times it re-armed the pacing epoch, can throw it off.
        TaskDelivery.tick(context, force = true, now = fiveAM)
        TaskDelivery.tick(context, force = true, now = fiveAM)
        TaskDelivery.tick(context, force = true, now = fiveAM)

        assertEquals(5 * 60 * 60 * 1000L, millisUntilWindowOpens(fiveAM, 10, 18))
    }
}
