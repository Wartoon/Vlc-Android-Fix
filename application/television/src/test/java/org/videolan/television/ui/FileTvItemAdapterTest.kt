package org.videolan.television.ui

import org.junit.Assert.assertFalse
import org.junit.Test

class FileTvItemAdapterTest {
    private val original = FileTvItemContentSignature(
        description = "Video",
        title = "Video",
        time = 0L,
        displayTime = 0L,
        length = 120_000L,
        seen = 0L,
        width = 640,
        height = 360
    )

    @Test
    fun widthChangeIsAContentChange() {
        assertFalse(areFileTvItemContentsTheSame(original, original.copy(width = 1920)))
    }

    @Test
    fun heightChangeIsAContentChange() {
        assertFalse(areFileTvItemContentsTheSame(original, original.copy(height = 1080)))
    }
}
