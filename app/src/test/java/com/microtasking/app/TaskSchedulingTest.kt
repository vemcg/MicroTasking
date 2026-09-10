// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TaskSchedulingTest {
    @Test
    fun isRapidTestingMode_onlyAboveThreshold() {
        assertTrue(isRapidTestingMode(500))
        assertTrue(isRapidTestingMode(5000))
        assertTrue(!isRapidTestingMode(499))
        assertTrue(!isRapidTestingMode(0))
    }

    @Test
    fun activeWindowLengthMillis_normalWraparoundAndAlwaysActive() {
        assertEquals(12 * 60 * 60 * 1000L, activeWindowLengthMillis(startHour = 9, endHour = 21))
        // 22 -> 6 wraps past midnight: 8 hours.
        assertEquals(8 * 60 * 60 * 1000L, activeWindowLengthMillis(startHour = 22, endHour = 6))
        // start == end means always active: a flat 24h.
        assertEquals(24 * 60 * 60 * 1000L, activeWindowLengthMillis(startHour = 9, endHour = 9))
    }

    @Test
    fun fixedDispatchIntervalMillis_dispatchCase_dividesWholeWindowByNMinusOne() {
        // 10:00, window 9-21 (12h window), previous tick dispatched, N = 5 -> 12h / 4 = 3h.
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        val interval = fixedDispatchIntervalMillis(now, startHour = 9, endHour = 21, promptsPerDay = 5, dispatched = true)
        assertEquals(3 * 60 * 60 * 1000L, interval)
    }

    @Test
    fun fixedDispatchIntervalMillis_noDispatchCase_dividesRemainingWindowByN() {
        // 10:00, window 9-21 -> 11h left, previous tick didn't dispatch, N = 11 -> 11h / 11 = 1h.
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        val interval = fixedDispatchIntervalMillis(now, startHour = 9, endHour = 21, promptsPerDay = 11, dispatched = false)
        assertEquals(60 * 60 * 1000L, interval)
    }

    @Test
    fun fixedDispatchIntervalMillis_zeroPromptsPerDay_returnsNull() {
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        assertNull(fixedDispatchIntervalMillis(now, startHour = 9, endHour = 21, promptsPerDay = 0, dispatched = true))
    }

    @Test
    fun fixedDispatchIntervalMillis_floors_normalAndRapid() {
        val now = LocalDateTime.of(2026, 1, 5, 9, 5)
        // Many prompts in a 1h window rounds to well under the 30s normal-mode floor.
        val normal = fixedDispatchIntervalMillis(now, startHour = 9, endHour = 10, promptsPerDay = 200, dispatched = true)
        assertEquals(30_000L, normal)
        // Rapid-testing mode drops the floor to 5s.
        val rapid = fixedDispatchIntervalMillis(now, startHour = 9, endHour = 21, promptsPerDay = 20_000, dispatched = true)
        assertEquals(5_000L, rapid)
    }

    @Test
    fun rolledCleanDayStreak_advancesOnCleanDay_resetsOnFailure_holdsOnIdle() {
        assertEquals(4, rolledCleanDayStreak(current = 3, hadCompletion = true, hadFailure = false))
        assertEquals(0, rolledCleanDayStreak(current = 9, hadCompletion = true, hadFailure = true))
        assertEquals(0, rolledCleanDayStreak(current = 9, hadCompletion = false, hadFailure = true))
        assertEquals(5, rolledCleanDayStreak(current = 5, hadCompletion = false, hadFailure = false))
    }

    @Test
    fun isWithinActiveWindow_handlesWraparoundAndAlwaysActive() {
        assertTrue(isWithinActiveWindow(LocalDateTime.of(2026, 1, 5, 23, 0), startHour = 22, endHour = 6))
        assertTrue(!isWithinActiveWindow(LocalDateTime.of(2026, 1, 5, 12, 0), startHour = 22, endHour = 6))
        assertTrue(isWithinActiveWindow(LocalDateTime.of(2026, 1, 5, 3, 0), startHour = 9, endHour = 9))
    }

    @Test
    fun weekAndMonthScoreWindowMillis_switchOnPromptsPerDay() {
        val now = LocalDateTime.of(2026, 2, 10, 12, 0) // February - 28 days in 2026 (not a leap year)
        assertEquals(7 * 24 * 60 * 60 * 1000L, weekScoreWindowMillis(promptsPerDay = 499))
        assertEquals(7 * 60_000L, weekScoreWindowMillis(promptsPerDay = 500))
        assertEquals(28 * 24 * 60 * 60 * 1000L, monthScoreWindowMillis(now, promptsPerDay = 499))
        assertEquals(28 * 60_000L, monthScoreWindowMillis(now, promptsPerDay = 500))
    }
}
