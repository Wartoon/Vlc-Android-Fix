package org.videolan.vlc.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEventListenerOwnerTest {
    @Test
    fun cleanupFromOlderBrowseCannotDetachNewerListener() {
        val owner = BrowserEventListenerOwner<Any>()
        val older = Any()
        val newer = Any()
        var installed: Any? = null

        owner.replace(older) { installed = it }
        owner.replace(newer) { installed = it }

        assertFalse(owner.clearIfCurrent(older) { installed = null })
        assertSame(newer, installed)
    }

    @Test
    fun activeBrowseCanClearItsOwnListener() {
        val owner = BrowserEventListenerOwner<Any>()
        val active = Any()
        var installed: Any? = active

        owner.replace(active) { installed = it }

        assertTrue(owner.clearIfCurrent(active) { installed = null })
        assertSame(null, installed)
    }
}
