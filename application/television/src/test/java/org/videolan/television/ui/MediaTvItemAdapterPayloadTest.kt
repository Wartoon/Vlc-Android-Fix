package org.videolan.television.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTvItemAdapterPayloadTest {
    @Test
    fun appliesSelectionAndThumbnailPayloadsTogether() {
        val applied = mutableListOf<String>()

        val handled = dispatchMediaTvItemPayloads(
            hasThumbnailPayload = true,
            selectionPayload = "selected",
            hasUnknownPayload = false,
            applySelection = { applied += it },
            applyThumbnail = { applied += "thumbnail" }
        )

        assertTrue(handled)
        assertEquals(listOf("selected", "thumbnail"), applied)
    }

    @Test
    fun unknownPayloadFallsBackToFullBindingWithoutPartialUpdates() {
        val applied = mutableListOf<String>()

        val handled = dispatchMediaTvItemPayloads(
            hasThumbnailPayload = true,
            selectionPayload = "selected",
            hasUnknownPayload = true,
            applySelection = { applied += it },
            applyThumbnail = { applied += "thumbnail" }
        )

        assertFalse(handled)
        assertTrue(applied.isEmpty())
    }
}
