package org.videolan.television.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBrowserTvFragmentTest {
    @Test
    fun currentItemFallsBackFromSavedStateToArgumentsThenIntent() {
        assertEquals("saved", resolveMediaBrowserItem("saved", "argument", "intent"))
        assertEquals("argument", resolveMediaBrowserItem(null, "argument", "intent"))
        assertEquals("intent", resolveMediaBrowserItem(null, null, "intent"))
    }

    @Test
    fun restoredSmbFolderRouteContainsItsShareAndParentFolders() {
        assertEquals(
                listOf("smb://server/share", "smb://server/share/series", "smb://server/share/series/season"),
                initialTvFolderRoute("smb://server/share/series/season")
        )
    }

    @Test
    fun missingFragmentHistoryReturnsToPreviousSavedFolder() {
        val route = listOf("smb://server/share", "smb://server/share/series", "smb://server/share/series/season")

        val target = previousTvFolder(route, "smb://server/share/series/season")

        assertEquals("smb://server/share/series", target?.uri)
        assertEquals(route.take(2), target?.remainingRoute)
    }
}
