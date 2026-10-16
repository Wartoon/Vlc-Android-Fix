package org.videolan.television.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.ThumbnailCachePolicy

class ThumbnailCachePolicyTest {
    @Test
    fun generatedFlagIsClearedWhenTheDiskThumbnailIsUnavailable() {
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
 
    @Test
    fun refreshUsesTheOrdinaryVlcThumbnailDimensions() {
        assertEquals(120, ThumbnailCachePolicy.refreshTargetWidth(false, 120, 576))
        assertEquals(576, ThumbnailCachePolicy.refreshTargetWidth(true, 120, 576))
    }

    @Test
    fun refreshRebuildsThumbnailsThatDoNotMatchTheTargetSize() {
        assertTrue(ThumbnailCachePolicy.needsResolutionAdjustment(1024, 576, 576))
        assertTrue(ThumbnailCachePolicy.needsResolutionAdjustment(320, 180, 576))
        assertFalse(ThumbnailCachePolicy.needsResolutionAdjustment(576, 324, 576))
    }
}

