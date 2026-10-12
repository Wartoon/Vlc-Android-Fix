package org.videolan.television.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.ThumbnailGenerationPolicy

class ThumbnailRequestPolicyTest {
    @Test
    fun doesNotRequestRemoteThumbnailOutsideAnOpenedVideoFolder() {
        assertFalse(ThumbnailGenerationPolicy.shouldRequestNetworkThumbnail(
            isLocalFile = false,
            explicitlyOpenedFolder = false
        ))
    }

    @Test
    fun requestsRemoteThumbnailInsideAnOpenedVideoFolder() {
        assertTrue(ThumbnailGenerationPolicy.shouldRequestNetworkThumbnail(
            isLocalFile = false,
            explicitlyOpenedFolder = true
        ))
    }

    @Test
    fun neverRequestsNetworkThumbnailForLocalFiles() {
        assertFalse(ThumbnailGenerationPolicy.shouldRequestNetworkThumbnail(
            isLocalFile = true,
            explicitlyOpenedFolder = true
        ))
    }

    @Test
    fun aggregateCoversDoNotReceivePermissionToRequestRemoteThumbnails() {
        assertFalse(ThumbnailGenerationPolicy.shouldRequestNetworkThumbnail(
            isLocalFile = false,
            explicitlyOpenedFolder = true,
            isAggregateCover = true
        ))
    }
}
