package org.videolan.television.ui.browser

import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem

internal fun shouldShowVideoPlaylistAction(itemType: Int, mediaType: Int? = null): Boolean =
    itemType == MediaLibraryItem.TYPE_FOLDER ||
            (itemType == MediaLibraryItem.TYPE_MEDIA && mediaType == MediaWrapper.TYPE_DIR)
