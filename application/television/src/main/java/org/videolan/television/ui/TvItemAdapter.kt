package org.videolan.television.ui

import org.videolan.medialibrary.interfaces.media.MediaWrapper

internal fun isThumbnailEventForMedia(itemId: Long, itemUri: String, thumbnailId: Long, thumbnailUri: String): Boolean {
    return (itemId > 0L && thumbnailId > 0L && itemId == thumbnailId) ||
            (itemUri.isNotBlank() && itemUri == thumbnailUri)
}

internal data class ThumbnailRefreshPayload(val media: MediaWrapper)
internal class ThumbnailMediaOverrides<T : Any> {
    private val mediaByUri = HashMap<String, T>()

    fun remember(uri: String, media: T) {
        mediaByUri[uri] = media
    }

    fun resolve(uri: String, currentItem: T): T = mediaByUri[uri] ?: currentItem

    fun clear() = mediaByUri.clear()
}

interface TvItemAdapter : TvFocusableAdapter {
    fun submitList(pagedList: Any?)
    fun isEmpty() : Boolean
    var focusNext: Int
    fun displaySwitch(inGrid: Boolean)
    fun thumbnailReady(media: MediaWrapper) {}
}
