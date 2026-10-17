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
    fun directSmbFavoriteStartsAtItsOpenedFolder() {
        assertEquals(
                listOf("smb://server/share/series/season"),
                initialTvFolderRoute("smb://server/share/series/season")
        )
    }

    @Test
    fun directSmbFavoriteHasNoInventedParentHistory() {
        val route = initialTvFolderRoute("smb://server/share/series/season")

        assertEquals(null, previousTvFolder(route, "smb://server/share/series/season"))
    }

    @Test
    fun foldersVisitedAfterOpeningFavoriteRemainInBackHistory() {
        val route = updateTvFolderRoute(
                initialTvFolderRoute("smb://server/share/series/season"),
                "smb://server/share/series/season/episode-subfolder",
                enteringFolder = true
        )

        val target = previousTvFolder(route, "smb://server/share/series/season/episode-subfolder")

        assertEquals("smb://server/share/series/season", target?.uri)
        assertEquals(listOf("smb://server/share/series/season"), target?.remainingRoute)
    }
}
