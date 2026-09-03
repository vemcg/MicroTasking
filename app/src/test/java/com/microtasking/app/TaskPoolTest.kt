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
    fun taskLifecycle_transitionsAndRecordsStartTime() {
        val entry = TaskStackEntry(builtInTasks.first())

        val started = entry.start()
        assertEquals(TaskLifecycleState.STARTED, started.state)
        assertNotNull(started.startedAtEpochMs)
        assertTrue(started.isActionable())
        assertEquals(TaskLifecycleState.COMPLETED, started.complete().state)
        assertEquals(TaskLifecycleState.ABANDONED, started.abandon().state)
        assertEquals(TaskLifecycleState.DEFERRED, entry.defer(7).state)
        assertTrue(!entry.defer(7).isActionable())
    }

    @Test
    fun chooseWeightedTask_avoidsPreviousTaskWhenAlternativeExists() {
        val first = builtInTasks[0]
        val second = builtInTasks[1]

        repeat(20) {
            assertEquals(second, chooseWeightedTask(listOf(first, second), emptyMap(), first.id))
        }
    }

    @Test
    fun declineCounts_roundTripThroughJson() {
        val counts = mapOf("task-a" to 2, "task-b" to 0)

        assertEquals(counts, readDeclineCounts(writeDeclineCounts(counts)))
        assertTrue(readDeclineCounts("not-json").isEmpty())
    }
}
