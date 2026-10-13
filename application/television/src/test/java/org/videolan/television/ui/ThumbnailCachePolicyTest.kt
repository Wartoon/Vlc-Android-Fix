package org.videolan.television.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.ThumbnailCachePolicy

class ThumbnailCachePolicyTest {
    @Test
    fun generatedFlagIsClearedOnlyWhenTheDiskThumbnailIsUnreadable() {
        assertTrue(ThumbnailCachePolicy.shouldClearGeneratedFlag(true, true))
        assertFalse(ThumbnailCachePolicy.shouldClearGeneratedFlag(true, false))
        assertFalse(ThumbnailCachePolicy.shouldClearGeneratedFlag(false, true))
    }

    @Test
    fun refreshDetectsImagesSmallerThanTheRequestedTarget() {
        assertTrue(ThumbnailCachePolicy.needsResolutionUpgrade(512, 288, 1024))
        assertFalse(ThumbnailCachePolicy.needsResolutionUpgrade(1024, 576, 1024))
        assertFalse(ThumbnailCachePolicy.needsResolutionUpgrade(512, 288, 320))
    }
}
