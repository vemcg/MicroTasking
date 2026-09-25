// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-84 main 2026-09-25
package com.microtasking.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * Regression coverage for a real, narrower staleness gap found while investigating "window opens
 * in 11 hours" (reported by Vern, 2026-09-25): `TaskDelivery.tick`'s "already armed" gate
 * (DEFECTS.md item 5, which exists to stop a redundant tick from double-dispatching) only recomputes
 * `next_dispatch_epoch_ms` unconditionally in the *outside-the-window* branch - verified below that
 * this branch is NOT stale, it recomputes every call, so it can't be what produced the reported
 * "Window opens in..." text being wrong. The gate that DOES skip recomputation
 * (`storedNextDispatch > now`) is only reachable while *inside* the window (or on a forced tap),
 * where the display text is "Next task in ...", not "Window opens in ...". A Settings save that
 * changes the window/prompts/queue size doesn't flip `background_prompts_enabled` or
 * `vacation_mode` the way Pause/Resume and the vacation toggle do, so - for that inside-window
 * case only - it never took either of the paths that already clear the epoch as a side effect. The
 * fix (`MainActivity.onCreate`'s `onSettingsSaved`) clears it explicitly, same as those two already
 * do. Real and worth keeping, but NOT confirmed to be the reported defect - see the chat reply for
 * why (need the user's on-device clock reading to pin that down).
 */
@RunWith(RobolectricTestRunner::class)
class PacingSettingsChangeTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    // 11 PM: outside both the old (9-21) and new (10-18) window used below.
    private val now = LocalDateTime.of(2026, 1, 5, 23, 0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = TaskDelivery.prefs(context)
        prefs.edit().clear().commit()
        prefs.edit()
            .putBoolean("setup_complete", true)
            .putString("managed_tasks", writeManagedTasks(listOf(ManagedTask("t", "Task", "Testing", 5, false))))
            .putStringSet("selected_categories", setOf("Testing"))
            .putString("prompts_per_day", "6")
            .putInt("max_task_queue_size", 3)
            .putBoolean("background_prompts_enabled", true)
            .commit()
    }

    // --- the pure decision (pacingSettingsChanged) ----------------------------------------

    @Test
    fun pacingSettingsChanged_trueForEachFieldThatFeedsTheSchedulingMath() {
        assertFalse(pacingSettingsChanged("9", "21", "6", 3, "9", "21", "6", 3))
        assertTrue("start hour", pacingSettingsChanged("9", "21", "6", 3, "10", "21", "6", 3))
        assertTrue("end hour", pacingSettingsChanged("9", "21", "6", 3, "9", "18", "6", 3))
        assertTrue("prompts per day", pacingSettingsChanged("9", "21", "6", 3, "9", "21", "8", 3))
        assertTrue("queue size", pacingSettingsChanged("9", "21", "6", 3, "9", "21", "6", 5))
    }

    // --- the outside-the-window branch is NOT stale (disproves my first theory) -----------

    @Test
    fun outsideTheWindow_tickAlwaysRecomputesFreshRegardlessOfWhatWasStoredBefore() {
        // Armed under the OLD window (9 AM-9 PM) while outside it: opens tomorrow 9 AM.
        prefs.edit().putString("start_hour", "9").putString("end_hour", "21").commit()
        TaskDelivery.tick(context, now = now)
        assertEquals(now.toEpochMillis() + 10 * 60 * 60 * 1000L, prefs.getLong("next_dispatch_epoch_ms", 0L))

        // Settings changed to 10 AM-6 PM, still outside it, epoch NOT explicitly cleared - the
        // outside-window branch in TaskDelivery.tick recomputes unconditionally either way.
        prefs.edit().putString("start_hour", "10").putString("end_hour", "18").commit()
        TaskDelivery.tick(context, now = now)

        assertEquals(
            "no staleness gate applies outside the window - this branch always overwrites",
            now.toEpochMillis() + 11 * 60 * 60 * 1000L, prefs.getLong("next_dispatch_epoch_ms", 0L)
        )
    }

    // --- the real, narrower gap: currently INSIDE the (new) window ------------------------

    @Test
    fun insideTheWindow_withoutClearing_aPacingChangeLeavesTheStaleNextTaskCountdown() {
        // Inside the window both before and after the change (10 AM, well within 9-21 and 8-20).
        val insideBoth = LocalDateTime.of(2026, 1, 5, 10, 0)
        prefs.edit().putString("start_hour", "9").putString("end_hour", "21").commit()
        TaskDelivery.tick(context, now = insideBoth) // dispatches and arms a pacing interval
        val staleEpoch = prefs.getLong("next_dispatch_epoch_ms", 0L)
        assertTrue("a pacing interval was armed", staleEpoch > insideBoth.toEpochMillis())

        // Prompts-per-day changed (changes the pacing interval), epoch NOT cleared - the bug this
        // fix addresses, for the one branch where tick()'s stale-epoch gate actually applies.
        prefs.edit().putString("prompts_per_day", "20").commit()
        TaskDelivery.tick(context, now = insideBoth)

        assertEquals(
            "storedNextDispatch > now gates this branch - it does NOT recompute on its own",
            staleEpoch, prefs.getLong("next_dispatch_epoch_ms", 0L)
        )
    }

    @Test
    fun insideTheWindow_clearingOnSave_recomputesFromTheNewPacing() {
        val insideBoth = LocalDateTime.of(2026, 1, 5, 10, 0)
        prefs.edit().putString("start_hour", "9").putString("end_hour", "21").commit()
        TaskDelivery.tick(context, now = insideBoth)
        val staleEpoch = prefs.getLong("next_dispatch_epoch_ms", 0L)

        // The fix: Settings Save persists the new prompts-per-day AND clears next_dispatch_epoch_ms
        // because pacingSettingsChanged reports true.
        prefs.edit().putString("prompts_per_day", "20").remove("next_dispatch_epoch_ms").commit()
        TaskDelivery.tick(context, now = insideBoth)

        assertTrue(
            "a fresh (differently-paced) epoch was armed, not the stale one",
            prefs.getLong("next_dispatch_epoch_ms", 0L) != staleEpoch
        )
    }

    @Test
    fun aCategoryOnlySave_doesNotNeedToClearTheEpoch() {
        // pacingSettingsChanged doesn't take categories at all - a pure category edit has nothing
        // stale to invalidate, since none of the scheduling math reads category selection.
        assertFalse(pacingSettingsChanged("9", "21", "6", 3, "9", "21", "6", 3))
    }
}
