// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPoolTest {
    @Test
    fun managedTasks_roundTripThroughJson() {
        val tasks = listOf(
            ManagedTask(
                id = "custom-1",
                description = "Sort one drawer",
                category = "Decluttering",
                durationMinutes = 10,
                builtIn = false,
                enabled = false,
                temporarilyUnavailable = true,
                neverSuggest = true
            )
        )

        assertEquals(tasks, readManagedTasks(writeManagedTasks(tasks)))
    }

    @Test
    fun readManagedTasks_returnsEmptyListForMalformedJson() {
        assertTrue(readManagedTasks("not-json").isEmpty())
    }

    @Test
    fun makeTaskStack_limitsEntriesAndStartsReady() {
        val entries = makeTaskStack(builtInTasks, maxEntries = 2)

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.state == TaskLifecycleState.READY })
        assertTrue(entries.all { it.startedAtEpochMs == null })
    }

    @Test
    fun readTaskQueue_collapsesDuplicateTaskIds() {
        val task = builtInTasks.first()
        val one = TaskStackEntry(task)
        // A queue persisted by an older build could carry two entries for the same task; the
        // task screen keys its LazyColumn by task id and a duplicate key crashes it.
        val json = writeTaskQueue(listOf(one, one.start(), TaskStackEntry(builtInTasks[1])))

        val queue = readTaskQueue(json)

        assertEquals(2, queue.size)
        assertEquals(listOf(task.id, builtInTasks[1].id), queue.map { it.task.id })
        // The first occurrence wins, so the un-started entry is the one kept.
        assertEquals(TaskLifecycleState.READY, queue.first().state)
    }

    @Test
    fun taskLifecycle_transitionsAndRecordsStartTime() {
        val entry = TaskStackEntry(builtInTasks.first())

        val started = entry.start()
        assertEquals(TaskLifecycleState.STARTED, started.state)
        assertNotNull(started.startedAtEpochMs)
        assertTrue(started.isActionable())
        assertEquals(TaskLifecycleState.COMPLETED, started.complete().state)
        assertEquals(TaskLifecycleState.ABANDONED, started.abandon().state)
        assertEquals(TaskLifecycleState.TIMED_OUT, entry.timeout().state)
        assertTrue(!entry.timeout().isActionable())
    }

    @Test
    fun chooseWeightedTask_avoidsPreviousTaskWhenAlternativeExists() {
        val first = builtInTasks[0]
        val second = builtInTasks[1]

        repeat(20) {
            assertEquals(second, chooseWeightedTask(listOf(first, second), listOf(first.category), first.id))
        }
    }

    @Test
    fun declineCounts_roundTripThroughJson() {
        val counts = mapOf("task-a" to 2, "task-b" to 0)

        assertEquals(counts, readDeclineCounts(writeDeclineCounts(counts)))
        assertTrue(readDeclineCounts("not-json").isEmpty())
    }

    @Test
    fun managedTasks_referredAtRoundTripsThroughJson() {
        val tasks = listOf(builtInTasks.first().copy(referredAt = 1_700_000_000_000L))

        assertEquals(tasks, readManagedTasks(writeManagedTasks(tasks)))
    }

    @Test
    fun managedTasks_missingReferredAtFieldReadsAsNull() {
        // Simulates a pool persisted by a build older than the referredAt field.
        val json = writeManagedTasks(listOf(builtInTasks.first()))
        val withoutField = org.json.JSONArray(json).getJSONObject(0).apply { remove("referredAt") }

        val read = readManagedTasks(org.json.JSONArray().put(withoutField).toString())

        assertEquals(null, read.single().referredAt)
    }

    @Test
    fun mergeImportedManagedTasks_carriesForwardReferredAtLikeNeverSuggest() {
        val task = builtInTasks.first()
        val existing = listOf(task.copy(neverSuggest = true, referredAt = 1_700_000_000_000L))
        // A fresh CSV-based import never knows about referredAt (see refreshReferralState) - it
        // always comes back null from the sheet-import path itself.
        val imported = listOf(task)

        val merged = mergeImportedManagedTasks(imported, existing)

        assertEquals(1_700_000_000_000L, merged.single().referredAt)
        assertTrue(merged.single().neverSuggest)
    }

    // DEV (sheet-surrogate-keys): the actual point of the exercise - a task's per-app flags and
    // referral state now survive a Sheet description (or category text) rename, because identity
    // is taskId-based instead of category+description text once the Sheet has one.

    @Test
    fun mergeImportedManagedTasks_taskIdKeepsFlagsAcrossADescriptionRename() {
        val existing = listOf(
            ManagedTask(
                id = "external-uuid-123", description = "Wipe the counters", category = "Cleaning",
                durationMinutes = 5, builtIn = false, neverSuggest = true, temporarilyUnavailable = true,
                referredAt = 1_700_000_000_000L, taskId = "uuid-123"
            )
        )
        // Re-sync after someone reworded the Sheet row by hand - same taskId, new description/id
        // text would be identical either way here since the id is now built from taskId alone.
        val imported = listOf(
            ManagedTask(
                id = "external-uuid-123", description = "Wipe down the kitchen counters", category = "Cleaning",
                durationMinutes = 5, builtIn = false, taskId = "uuid-123"
            )
        )

        val merged = mergeImportedManagedTasks(imported, existing)

        val task = merged.single()
        assertEquals("Wipe down the kitchen counters", task.description)
        assertTrue("neverSuggest survives the rename", task.neverSuggest)
        assertTrue("temporarilyUnavailable survives the rename", task.temporarilyUnavailable)
        assertEquals("referredAt survives the rename", 1_700_000_000_000L, task.referredAt)
    }

    @Test
    fun refreshReferralState_prefersTaskIdKeyOverTextKeyWhenPresent() {
        // A stale category/description (as if read before a rename) would miss the legacy
        // "category|description" key entirely - taskId-based matching doesn't care.
        val task = builtInTasks.first().copy(
            category = "Old Name", description = "Stale text", taskId = "uuid-123"
        )
        val referredKeys = setOf("uuid-123")

        val result = refreshReferralState(listOf(task), referredKeys, now = 99L)

        assertEquals(99L, result.single().referredAt)
    }

    @Test
    fun refreshReferralState_stampsNewlyReferredTaskAndClearsUnreferredOne() {
        val referred = builtInTasks[0]
        val stillReferred = builtInTasks[1].copy(referredAt = 1_700_000_000_000L)
        val noLongerReferred = builtInTasks[2].copy(referredAt = 1_700_000_000_000L)
        val neverReferred = builtInTasks[3]
        val tasks = listOf(referred, stillReferred, noLongerReferred, neverReferred)
        val referredKeys = setOf(
            WebAppClient.rowKey(referred.category, referred.description),
            WebAppClient.rowKey(stillReferred.category, stillReferred.description)
        )

        val result = refreshReferralState(tasks, referredKeys, now = 42L)
        val byId = result.associateBy { it.id }

        assertEquals(42L, byId.getValue(referred.id).referredAt)
        assertEquals(1_700_000_000_000L, byId.getValue(stillReferred.id).referredAt) // unchanged, not re-stamped
        assertEquals(null, byId.getValue(noLongerReferred.id).referredAt)
        assertEquals(null, byId.getValue(neverReferred.id).referredAt)
    }

    @Test
    fun eligiblePromptTasks_excludesReferredTasks() {
        val referred = builtInTasks.first().copy(referredAt = 1_700_000_000_000L)
        val notReferred = builtInTasks[1]

        val eligible = eligiblePromptTasks(
            managedTasks = listOf(referred, notReferred),
            legacyUserTasks = emptyList(),
            selectedCategories = setOf(referred.category, notReferred.category)
        )

        assertEquals(listOf(notReferred), eligible)
    }
}
