package org.videolan.television

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.videolan.television.ui.browser.restoredFocusPosition

class ExampleUnitTest {
    @Test
    fun restoredPositionMovesToLastItemWhenListShrinks() {
        assertEquals(4, restoredFocusPosition(savedPosition = 7, itemCount = 5))
    }

    @Test
    fun restoredPositionKeepsExistingItem() {
        assertEquals(2, restoredFocusPosition(savedPosition = 2, itemCount = 5))
    }

    @Test
    fun noPositionIsRestoredForEmptyListOrMissingSelection() {
        assertNull(restoredFocusPosition(savedPosition = 2, itemCount = 0))
        assertNull(restoredFocusPosition(savedPosition = -1, itemCount = 5))
    }
}
