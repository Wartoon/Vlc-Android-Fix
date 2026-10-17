package org.videolan.television.ui.browser

internal data class TvFolderBackTarget(val uri: String, val remainingRoute: List<String>)

internal fun initialTvFolderRoute(uriString: String): List<String> {
    // A path opened from a favorite or intent is the user's starting location,
    // not a history of folders they visited in this browser session.
    return listOf(uriString)
}

internal fun updateTvFolderRoute(route: List<String>, currentUri: String?, enteringFolder: Boolean): List<String> {
    if (currentUri == null) return emptyList()
    if (enteringFolder) return if (route.lastOrNull() == currentUri) route else route + currentUri

    val currentIndex = route.indexOfLast { it == currentUri }
    return if (currentIndex >= 0) route.take(currentIndex + 1) else initialTvFolderRoute(currentUri)
}

internal fun previousTvFolder(route: List<String>, currentUri: String): TvFolderBackTarget? {
    val currentIndex = route.indexOfLast { it == currentUri }
    if (currentIndex <= 0) return null
    return TvFolderBackTarget(route[currentIndex - 1], route.take(currentIndex))
}
