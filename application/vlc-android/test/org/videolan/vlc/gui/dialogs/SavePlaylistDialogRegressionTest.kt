package org.videolan.vlc.gui.dialogs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SavePlaylistDialogRegressionTest {
    @Test
    fun newTracksAreCalculatedAgainstEachTargetPlaylist() {
        val incoming = arrayOf("one", "two", "three")

        val firstPlaylist = nonDuplicateItems(arrayOf("one"), incoming) { a, b -> a == b }
        val secondPlaylist = nonDuplicateItems(arrayOf("two"), incoming) { a, b -> a == b }

        assertArrayEquals(arrayOf("two", "three"), firstPlaylist)
        assertArrayEquals(arrayOf("one", "three"), secondPlaylist)
    }

    @Test
    fun fullyDuplicatedPlaylistHasAnEmptyAddNewSet() {
        val incoming = arrayOf("one", "two")

        val newItems = nonDuplicateItems(arrayOf("one", "two"), incoming) { a, b -> a == b }

        assertArrayEquals(emptyArray<String>(), newItems)
    }

    @Test
    fun savedPlaylistSelectionRestoresByItemPosition() {
        val positions = selectedItemIndices(
            listOf("first", "second", "third"),
            listOf("third", "first")
        )

        assertEquals(listOf(2, 0), positions)
    }
}
