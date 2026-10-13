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
    fun refreshedThumbnailMediaIsReusedWhenTheOldListItemBindsAgain() {
        val overrides = ThumbnailMediaOverrides<String>()
        val uri = "smb://server/episode.mkv"
        val oldListItem = "unindexed-media"
        val refreshedMedia = "indexed-media-with-thumbnail"

        overrides.remember(uri, refreshedMedia)

        org.junit.Assert.assertEquals(refreshedMedia, overrides.resolve(uri, oldListItem))
        org.junit.Assert.assertEquals(oldListItem, overrides.resolve("smb://server/other.mkv", oldListItem))
        overrides.clear()
        org.junit.Assert.assertEquals(oldListItem, overrides.resolve(uri, oldListItem))
    }
    @Test
    fun staleQueuedFocusGainIsIgnoredButTheLatestFocusedRowIsDispatched() {
        assertFalse(shouldDispatchTvFocusGain(true, false, 4L, 5L))
        assertFalse(shouldDispatchTvFocusGain(true, true, 4L, 5L))
        assertFalse(shouldDispatchTvFocusGain(false, false, 5L, 5L))
        assertTrue(shouldDispatchTvFocusGain(true, true, 5L, 5L))
    }

    @Test
    fun recycledHolderClearsItsBoundItemBeforeResettingTheCover() {
        val calls = mutableListOf<String>()
        var boundItem: String? = "episode"

        resetRecycledMediaItem(boundItem, { item ->
            boundItem = item
            calls += "clear"
        }) {
            calls += if (boundItem == null) "reset-cover" else "reset-cover-with-bound-item"
        }

        assertTrue(boundItem == null)
        org.junit.Assert.assertEquals(listOf("clear", "reset-cover"), calls)
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
