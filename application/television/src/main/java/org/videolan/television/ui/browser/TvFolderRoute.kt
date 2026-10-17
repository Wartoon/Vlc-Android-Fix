package org.videolan.television.ui.browser

import android.net.Uri

internal data class TvFolderBackTarget(val uri: String, val remainingRoute: List<String>)

internal fun initialTvFolderRoute(uriString: String): List<String> {
    val uri = Uri.parse(uriString)
    if (!uri.scheme.equals("smb", ignoreCase = true)) return listOf(uriString)

    val segments = uri.pathSegments
    if (segments.isEmpty()) return listOf(uriString)
    return segments.mapIndexed { index, _ ->
        uri.buildUpon()
                .encodedPath("/" + segments.take(index + 1).joinToString("/") { Uri.encode(it) })
                .build()
                .toString()
    }
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
