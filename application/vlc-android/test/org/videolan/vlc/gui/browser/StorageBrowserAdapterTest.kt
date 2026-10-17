package org.videolan.vlc.gui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBrowserAdapterTest {
    @Test fun storageCheckboxCanReceiveDpadFocusOnlyOnTvWhenEnabled() {
        assertTrue(shouldFocusStorageCheckbox(isTv = true, enabled = true))
        assertFalse(shouldFocusStorageCheckbox(isTv = false, enabled = true))
        assertFalse(shouldFocusStorageCheckbox(isTv = true, enabled = false))
    }
}
