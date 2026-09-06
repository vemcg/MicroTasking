// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTaskImportTest {
    @Test
    fun normalizeGoogleSheetCsvUrl_convertsEditLinkToExportUrl() {
        val input = "https://docs.google.com/spreadsheets/d/abc123/edit?usp=sharing#gid=456"
        val result = normalizeGoogleSheetCsvUrl(input)

        assertEquals("https://docs.google.com/spreadsheets/d/abc123/export?format=csv&gid=456", result)
    }

    @Test
    fun splitCsvLine_keepsCommasInsideQuotedFields() {
        assertEquals(
            listOf("TRUE", "Sort the mail into keep, recycle, shred", "https://x.test"),
            splitCsvLine(""" TRUE ,"Sort the mail into keep, recycle, shred", https://x.test """.trim())
        )
    }

    @Test
    fun splitCsvLine_unescapesDoubledQuotes() {
        assertEquals(
            listOf("FALSE", """Read "Atomic Habits", chapter 1""", ""),
            splitCsvLine(""" FALSE,"Read ""Atomic Habits"", chapter 1", """.trim())
        )
    }

    @Test
    fun parseExternalTaskCsv_columnAIsTheEnabledToggleWithNoHeaderText() {
        // Row 1: A1 is the master checkbox (exports as TRUE), B1/C1 are the real headers.
        val csv = """
            TRUE,Description,Link
            TRUE,Wash the dishes,https://example.com/dishes
            FALSE,Take out the trash,
            TRUE,Reply to one email,
        """.trimIndent()

        val tasks = parseExternalTaskCsv(csv, "Imported")

        assertEquals(3, tasks.size)
        assertEquals(listOf("Wash the dishes", "Take out the trash", "Reply to one email"), tasks.map { it.description })
        assertEquals(listOf(true, false, true), tasks.map { it.enabled })
        assertEquals("Imported", tasks[0].category)
    }

    @Test
    fun parseExternalTaskCsv_blankColumnAIsDisabledWhenTheTabUsesCheckboxes() {
        val csv = """
            TRUE,Description,Link
            TRUE,Checked task,
            ,Task whose checkbox was removed,
        """.trimIndent()

        val tasks = parseExternalTaskCsv(csv, "Imported")

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].enabled)
        assertFalse(tasks[1].enabled)
    }

    @Test
    fun parseExternalTaskCsv_tabWithNoCheckboxesImportsEverythingEnabled() {
        // An older sheet from before column A held checkboxes: no TRUE/FALSE anywhere in column A.
        val csv = """
            ,Description,Link
            ,First task,
            ,Second task,
        """.trimIndent()

        val tasks = parseExternalTaskCsv(csv, "Legacy")

        assertEquals(2, tasks.size)
        assertTrue(tasks.all { it.enabled })
    }

    @Test
    fun parseExternalTaskCsv_taskIdIsStableAcrossImports() {
        val csv = """
            TRUE,Description,Link
            TRUE,Wipe the counters,
        """.trimIndent()

        val first = parseExternalTaskCsv(csv, "Cleaning").single()
        val second = parseExternalTaskCsv(csv, "Cleaning").single()

        assertEquals(first.id, second.id)
        assertEquals("external-Cleaning-Wipe the counters", first.id)
    }

    @Test
    fun parseExternalTaskCsv_skipsRowsWithNoDescription() {
        val csv = """
            TRUE,Description,Link
            TRUE,,https://example.com
            TRUE,Real task,
        """.trimIndent()

        assertEquals(listOf("Real task"), parseExternalTaskCsv(csv, "X").map { it.description })
    }

    @Test
    fun mergeImportedManagedTasks_sheetWinsEnabledButAppFlagsSurvive() {
        val existing = listOf(
            ManagedTask(
                id = "external-Cleaning-Wipe the counters",
                description = "Wipe the counters",
                category = "Cleaning",
                durationMinutes = 5,
                builtIn = false,
                enabled = true,
                temporarilyUnavailable = true,
                neverSuggest = true
            ),
            ManagedTask(
                id = "custom-1", description = "My own task", category = "Personal",
                durationMinutes = 10, builtIn = false
            )
        )
        val imported = listOf(
            ManagedTask(
                id = "external-Cleaning-Wipe the counters",
                description = "Wipe the counters",
                category = "Cleaning",
                durationMinutes = 5,
                builtIn = false,
                enabled = false,
                temporarilyUnavailable = false,
                neverSuggest = false
            )
        )

        val merged = mergeImportedManagedTasks(imported, existing)

        val wipe = merged.single { it.id == "external-Cleaning-Wipe the counters" }
        assertFalse("sheet checkbox is authoritative for enabled", wipe.enabled)
        assertTrue("neverSuggest set in the app survives a re-sync", wipe.neverSuggest)
        assertTrue("temporarilyUnavailable set in the app survives a re-sync", wipe.temporarilyUnavailable)
        assertTrue("a category the import didn't touch is left alone", merged.any { it.id == "custom-1" })
    }

    @Test
    fun mergeImportedManagedTasks_droppedSheetRowDisappears() {
        val existing = listOf(
            ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false),
            ManagedTask("external-Cleaning-B", "B", "Cleaning", 5, false)
        )
        val imported = listOf(ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false))

        val merged = mergeImportedManagedTasks(imported, existing)

        assertEquals(listOf("external-Cleaning-A"), merged.map { it.id })
    }
}
