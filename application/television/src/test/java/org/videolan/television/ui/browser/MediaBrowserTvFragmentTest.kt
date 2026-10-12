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
}
