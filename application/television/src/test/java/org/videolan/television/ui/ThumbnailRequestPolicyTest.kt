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
    fun indexesUnindexedRemoteVideoOnlyWhenItsFolderIsExplicitlyOpened() {
        assertTrue(ThumbnailGenerationPolicy.shouldIndexNetworkThumbnailMedia(
            isLocalFile = false,
            mediaId = 0L,
            isVideo = true,
            explicitlyOpenedFolder = true
        ))
        assertFalse(ThumbnailGenerationPolicy.shouldIndexNetworkThumbnailMedia(
            isLocalFile = false,
            mediaId = 0L,
            isVideo = true,
            explicitlyOpenedFolder = false
        ))
        assertFalse(ThumbnailGenerationPolicy.shouldIndexNetworkThumbnailMedia(
            isLocalFile = false,
            mediaId = 0L,
            isVideo = false,
            explicitlyOpenedFolder = true
        ))
        assertFalse(ThumbnailGenerationPolicy.shouldIndexNetworkThumbnailMedia(
            isLocalFile = true,
            mediaId = 0L,
            isVideo = true,
            explicitlyOpenedFolder = true
        ))
    }

    @Test
    fun thumbnailReadyMatchesOnlyTheReturnedMedia() {
        val smbUri = "smb://server/Family Guy/episode.mkv"
        assertTrue(isThumbnailEventForMedia(73L, smbUri, 73L, smbUri))
        assertTrue(isThumbnailEventForMedia(0L, smbUri, 73L, smbUri))
        assertFalse(isThumbnailEventForMedia(72L, "smb://server/other.mkv", 73L, smbUri))
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
