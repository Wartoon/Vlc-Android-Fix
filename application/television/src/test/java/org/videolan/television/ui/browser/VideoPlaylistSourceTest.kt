package org.videolan.television.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem

class VideoPlaylistSourceTest {
    @Test
    fun actionIsAvailableForMediaLibraryFoldersAndFileDirectories() {
        assertTrue(shouldShowVideoPlaylistAction(MediaLibraryItem.TYPE_FOLDER))
        assertTrue(shouldShowVideoPlaylistAction(MediaLibraryItem.TYPE_MEDIA, MediaWrapper.TYPE_DIR))
    }

    @Test
    fun actionIsHiddenForIndividualVideosAndOtherItemTypes() {
        assertFalse(shouldShowVideoPlaylistAction(MediaLibraryItem.TYPE_MEDIA, MediaWrapper.TYPE_VIDEO))
        assertFalse(shouldShowVideoPlaylistAction(MediaLibraryItem.TYPE_MEDIA, MediaWrapper.TYPE_AUDIO))
        assertFalse(shouldShowVideoPlaylistAction(MediaLibraryItem.TYPE_VIDEO_GROUP))
        assertFalse(shouldShowVideoPlaylistAction(MediaLibraryItem.TYPE_PLAYLIST))
    }
}
