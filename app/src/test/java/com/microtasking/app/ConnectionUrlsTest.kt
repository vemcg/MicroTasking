// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-26, after version v0.2.0-86 main 2026-09-26
package com.microtasking.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionUrlsTest {
    private val sheetUrl = "https://docs.google.com/spreadsheets/d/1YZNQxZlzj8Xj4Bya2v8YEiq01n6jseD3JZSr7BlThJo/edit?usp=sharing"
    private val webAppUrl = "https://script.google.com/macros/s/AKfycbxAbC-123_xyz/exec"

    @Test
    fun blank_isEmpty_soNoErrorOrLinkShows() {
        assertEquals(UrlCheck.Empty, checkSheetUrl(""))
        assertEquals(UrlCheck.Empty, checkSheetUrl("   "))
        assertEquals(UrlCheck.Empty, checkWebAppUrl(""))
    }

    @Test
    fun sheetUrl_valid_linkTextIsJustTheId_targetIsTheFullUrl() {
        assertEquals(
            UrlCheck.Valid("1YZNQxZlzj8Xj4Bya2v8YEiq01n6jseD3JZSr7BlThJo", sheetUrl),
            checkSheetUrl("  $sheetUrl  ")
        )
    }

    @Test
    fun sheetUrl_wrongKindOfUrl_isInvalid() {
        assertEquals(UrlCheck.Invalid, checkSheetUrl(webAppUrl))
        assertEquals(UrlCheck.Invalid, checkSheetUrl("not a url"))
        assertEquals("has the marker but no /d/<id>", UrlCheck.Invalid, checkSheetUrl("https://docs.google.com/spreadsheets/"))
    }

    @Test
    fun webAppUrl_valid_linkTextIsJustTheId() {
        assertEquals(UrlCheck.Valid("AKfycbxAbC-123_xyz", webAppUrl), checkWebAppUrl(webAppUrl))
    }

    @Test
    fun webAppUrl_workspaceForm_isAlsoValid() {
        val workspace = "https://script.google.com/a/macros/example.com/s/AKfycbWORK/exec"
        assertEquals(UrlCheck.Valid("AKfycbWORK", workspace), checkWebAppUrl(workspace))
    }

    @Test
    fun webAppUrl_wrongKindOfUrl_isInvalid() {
        assertEquals(UrlCheck.Invalid, checkWebAppUrl(sheetUrl))
        assertEquals(UrlCheck.Invalid, checkWebAppUrl("https://script.google.com/home"))
    }

    @Test
    fun missingScheme_stillValid_andLinkGetsHttps() {
        assertEquals(
            UrlCheck.Valid("AKfycbxAbC-123_xyz", "https://script.google.com/macros/s/AKfycbxAbC-123_xyz/exec"),
            checkWebAppUrl("script.google.com/macros/s/AKfycbxAbC-123_xyz/exec")
        )
    }
}
