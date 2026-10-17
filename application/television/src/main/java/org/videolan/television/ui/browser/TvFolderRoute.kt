package org.videolan.television.ui.browser

import java.net.URI

internal data class TvFolderBackTarget(val uri: String, val remainingRoute: List<String>)

internal fun initialTvFolderRoute(uriString: String): List<String> {
    val uri = runCatching { URI(uriString) }.getOrNull() ?: return listOf(uriString)
    if (!uri.scheme.equals("smb", ignoreCase = true)) return listOf(uriString)

    val authority = uri.rawAuthority ?: return listOf(uriString)
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return listOf(uriString)
    val baseUri = "${uri.scheme}://$authority"
    return segments.mapIndexed { index, _ ->
        "$baseUri/${segments.take(index + 1).joinToString("/")}"
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
