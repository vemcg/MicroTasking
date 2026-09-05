// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import org.junit.Assert.assertEquals
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
    fun parseExternalTaskCsv_readsDescriptionAndLinkColumns() {
        val csv = """
            checkbox,description,link
            TRUE,Wash the dishes,https://example.com/dishes
            FALSE,Take out the trash,
            TRUE,Reply to one email,
        """.trimIndent()

        val tasks = parseExternalTaskCsv(csv, "Imported")

        assertEquals(3, tasks.size)
        assertEquals("Wash the dishes", tasks[0].description)
        assertEquals("Imported", tasks[0].category)
        assertTrue(tasks[0].enabled)
    }
}
