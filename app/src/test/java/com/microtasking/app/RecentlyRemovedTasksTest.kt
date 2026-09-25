// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-84 main 2026-09-25
package com.microtasking.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for "a task I fully completed pops back briefly then disappears again"
 * (reported by Vern, 2026-09-25; root cause diagnosed jointly with ActiveTasks - DEFECTS.md item 8).
 * `mergeImportedManagedTasks` merges purely by id against whatever a Sheet CSV read says, with no
 * memory of a deletion that already landed locally via the "fullyCompleted" cross-app event - a
 * sync that catches Google's gviz cache before it's caught up with the deletion re-adds the row
 * until a fresher sync removes it again. These tests cover the guard in isolation from the
 * SharedPreferences/Context plumbing in [RecentlyRemovedTasks].
 */
class RecentlyRemovedTasksTest {
    private val task = ManagedTask(
        id = "external-Decluttering-Empty one trash bin",
        taskId = "row-42",
        description = "Empty one trash bin",
        category = "Decluttering",
        durationMinutes = 5,
        builtIn = false
    )

    @Test
    fun filterResurrectedRows_dropsARowRemovedWithinTheTtl() {
        val removed = listOf(RemovedTaskMarker(key = "row-42", removedAtEpochMs = 1_000L))

        val result = filterResurrectedRows(listOf(task), removed, now = 1_000L + 60_000L)

        assertTrue("the just-deleted row must not be resurrected by a stale CSV read", result.isEmpty())
    }

    @Test
    fun filterResurrectedRows_letsTheRowThroughOnceTheMarkerExpires() {
        val removed = listOf(RemovedTaskMarker(key = "row-42", removedAtEpochMs = 1_000L))

        val result = filterResurrectedRows(listOf(task), removed, now = 1_000L + recentlyRemovedTaskTtlMs + 1)

        assertEquals(
            "once the TTL has genuinely passed, a real re-add under the same key must not be blocked forever",
            listOf(task), result
        )
    }

    @Test
    fun filterResurrectedRows_matchesByTheLegacyCategoryDescriptionKey_whenThereIsNoTaskId() {
        val legacyTask = task.copy(taskId = null, id = "external-Decluttering-Empty one trash bin")
        val removed = listOf(RemovedTaskMarker(key = "Decluttering|Empty one trash bin", removedAtEpochMs = 1_000L))

        val result = filterResurrectedRows(listOf(legacyTask), removed, now = 1_000L + 60_000L)

        assertTrue("a pre-surrogate-key row must still match on category+description", result.isEmpty())
    }

    @Test
    fun filterResurrectedRows_leavesUnrelatedRowsAlone() {
        val other = task.copy(id = "external-Decluttering-Something else", taskId = "row-99", description = "Something else")
        val removed = listOf(RemovedTaskMarker(key = "row-42", removedAtEpochMs = 1_000L))

        val result = filterResurrectedRows(listOf(other), removed, now = 1_000L + 60_000L)

        assertEquals(listOf(other), result)
    }

    @Test
    fun pruneExpiredRemovals_dropsOnlyWhatHasActuallyExpired() {
        val fresh = RemovedTaskMarker(key = "fresh", removedAtEpochMs = 9_000L)
        val stale = RemovedTaskMarker(key = "stale", removedAtEpochMs = 1_000L)

        val result = pruneExpiredRemovals(listOf(fresh, stale), now = 9_000L + recentlyRemovedTaskTtlMs - 1)

        assertEquals(listOf(fresh), result)
    }

    @Test
    fun recentlyRemovedTasks_jsonRoundTrips() {
        val markers = listOf(RemovedTaskMarker("row-42", 1_000L), RemovedTaskMarker("Decluttering|Something", 2_000L))

        assertEquals(markers, readRecentlyRemoved(writeRecentlyRemoved(markers)))
    }
}
