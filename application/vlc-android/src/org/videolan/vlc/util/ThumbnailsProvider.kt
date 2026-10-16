package org.videolan.vlc.util


import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.graphics.Canvas
import android.graphics.Rect
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.Medialibrary.MEDIALIB_FOLDER_NAME
import org.videolan.medialibrary.interfaces.media.Folder
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.VideoGroup
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AppContextProvider
import org.videolan.tools.BitmapCache
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.tools.sanitizePath
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.gui.helpers.AudioUtil.readCoverBitmap
import org.videolan.vlc.gui.helpers.BitmapUtil
import org.videolan.vlc.gui.helpers.UiTools
import java.io.File
import java.util.ArrayDeque
import kotlin.math.min

object ThumbnailGenerationPolicy {
    fun shouldRequestNetworkThumbnail(isLocalFile: Boolean, explicitlyOpenedFolder: Boolean, isAggregateCover: Boolean = false): Boolean =
        !isLocalFile && explicitlyOpenedFolder && !isAggregateCover

    fun shouldIndexNetworkThumbnailMedia(
        isLocalFile: Boolean,
        mediaId: Long,
        isVideo: Boolean,
        explicitlyOpenedFolder: Boolean,
        isAggregateCover: Boolean = false
    ): Boolean = mediaId <= 0L && isVideo &&
            shouldRequestNetworkThumbnail(isLocalFile, explicitlyOpenedFolder, isAggregateCover)
}

object ThumbnailCachePolicy {
    fun shouldClearGeneratedFlag(isThumbnailGenerated: Boolean, cachedThumbnailUnavailable: Boolean) =
        isThumbnailGenerated && cachedThumbnailUnavailable

    fun needsResolutionUpgrade(width: Int, height: Int, targetSize: Int) =
        targetSize > 0 && maxOf(width, height) < targetSize

    fun needsResolutionReduction(width: Int, height: Int, targetSize: Int) =
        targetSize > 0 && maxOf(width, height) > targetSize

    fun needsResolutionAdjustment(width: Int, height: Int, targetSize: Int) =
        needsResolutionUpgrade(width, height, targetSize) ||
                needsResolutionReduction(width, height, targetSize)

    fun refreshTargetWidth(isTv: Boolean, mobileWidthPx: Int, tvWidthPx: Int) =
        if (isTv) tvWidthPx else mobileWidthPx
}

object ThumbnailsProvider {

    @Suppress("unused")
    private const val TAG = "VLC/ThumbnailsProvider"

    private var appDir: File? = null
    private var cacheDir: String? = null
    private const val MAX_IMAGES = 4
    private const val REFRESH_BATCH_SIZE = 64
    private val lock = Any()
    // One extra frame-selection attempt per dark local video, bounded in memory and work.
    private val checkedDarkVideos = LinkedHashSet<String>()
    private const val MAX_DARK_CHECKS = 256

    private fun defaultRefreshWidth(): Int {
        val resources = AppContextProvider.appContext.resources
        return ThumbnailCachePolicy.refreshTargetWidth(
            Settings.showTvUi,
            resources.getDimensionPixelSize(R.dimen.audio_browser_item_size),
            resources.getDimensionPixelSize(R.dimen.tv_grid_card_thumb_width)
        )
    }

    private data class FrameQuality(val dark: Float, val colored: Float) {
        // Colorful scene content is preferable to black fades and white intro text.
        val score: Float get() = (1f - dark) * 0.45f + colored * 0.55f
        val likelyIntroOrBlank: Boolean get() =
            dark >= 0.75f || (dark >= 0.35f && colored < 0.12f)
    }

    private fun frameQuality(bitmap: Bitmap): FrameQuality {
        if (bitmap.width <= 0 || bitmap.height <= 0) return FrameQuality(0f, 0f)
        var dark = 0
        var colored = 0
        try {
            for (y in 1..7) for (x in 1..7) {
                val pixel = bitmap.getPixel(bitmap.width * x / 8, bitmap.height * y / 8)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val brightness = (red * 3 + green * 6 + blue) / 10
                if (brightness < 28) dark++
                if (maxOf(red, green, blue) - minOf(red, green, blue) > 40 && brightness > 50)
                    colored++
            }
        } catch (_: IllegalStateException) {
            // Unsupported (e.g. hardware) bitmap: do not classify it as a bad frame.
            return FrameQuality(0f, 0f)
        }
        return FrameQuality(dark / 49f, colored / 49f)
    }

    /**
     * Select real episode content rather than warning cards, fades and intros.
     * For episodes of >=20 min the earliest candidate is at minute five;
     * shorter clips start after 25% of their runtime instead.
     * Automatic requests only inspect extra frames if the original looks poor.
     */
    private fun betterLocalVideoFrame(media: MediaWrapper, original: Bitmap?, manual: Boolean = false, targetSize: Int = 384): Bitmap? {
        if (!media.uri.scheme.isSchemeFile() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1)
            return null
        val initialQuality = original?.let(::frameQuality)
        if (!manual && initialQuality?.likelyIntroOrBlank != true) return null
        if (!manual) synchronized(lock) {
            val key = media.uri.toString()
            if (key in checkedDarkVideos || checkedDarkVideos.size >= MAX_DARK_CHECKS) return null
            checkedDarkVideos.add(key)
        }
        val sourcePath = media.uri.path ?: return null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourcePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return null
            if (durationMs <= 0L) return null
            val earliestMs = minOf(300_000L, durationMs / 4).coerceAtLeast(1L)
            val requestedSize = targetSize.coerceIn(1, 1024)
            val referenceWidth = original?.width ?: if (manual) requestedSize else 320
            val referenceHeight = original?.height ?: if (manual) requestedSize * 9 / 16 else 180
            val scale = minOf(1f, requestedSize.toFloat() / maxOf(referenceWidth, referenceHeight))
            val targetWidth = (referenceWidth * scale).toInt().coerceAtLeast(1)
            val targetHeight = (referenceHeight * scale).toInt().coerceAtLeast(1)
            var best: Bitmap? = null
            var bestScore = if (manual) -1f else initialQuality?.score ?: 0f
            val times = longArrayOf(earliestMs, durationMs / 2, durationMs * 7 / 10).distinct()
            for (timeMs in times) {
                val frame = try {
                    retriever.getScaledFrameAtTime(timeMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
                } catch (_: RuntimeException) {
                    null
                } ?: continue
                val quality = frameQuality(frame)
                if (quality.score > bestScore) {
                    best = frame
                    bestScore = quality.score
                }
                // One good scene suffices; avoid expensive extra decoding.
                if (quality.score >= 0.72f && !quality.likelyIntroOrBlank) break
            }
            return best?.takeIf {
                bestScore >= 0.30f && (manual || bestScore >= (initialQuality?.score ?: 0f) + 0.08f)
            }
        } catch (_: RuntimeException) {
            return null
        } finally {
            try { retriever.release() } catch (_: RuntimeException) {}
        }
    }

    private fun isOurLocalVideoThumbnail(media: MediaWrapper): Boolean {
        if (media.type != MediaWrapper.TYPE_VIDEO || !media.uri.scheme.isSchemeFile() || media.id <= 0L) return false
        val folder = AppContextProvider.appContext.getExternalFilesDir(null) ?: return false
        val artwork = Uri.decode(media.artworkMrl ?: return false).removePrefix("file://")
        return artwork == "${folder.absolutePath}$MEDIALIB_FOLDER_NAME/${media.id}.jpg"
    }

    private fun improveCachedVideoThumbnail(media: MediaWrapper, original: Bitmap, path: String, width: Int): Bitmap {
        val better = betterLocalVideoFrame(media, original) ?: return original
        if (media.id > 0L && File(path).isFile && BitmapUtil.saveOnDisk(better, path)) {
            media.setThumbnail(path)
            media.artworkURL = path
        }
        BitmapCache.replaceBitmapInMemCache(getMediaCacheKey(true, media, width.toString()), better)
        BitmapCache.replaceBitmapInMemCache(path + "_$width", better)
        return better
    }


    @WorkerThread
    private fun resolveMediaForThumbnail(media: MediaWrapper, explicitlyOpenedFolder: Boolean): MediaWrapper {
        if (!ThumbnailGenerationPolicy.shouldIndexNetworkThumbnailMedia(
                isLocalFile = media.uri.scheme.isSchemeFile(),
                mediaId = media.id,
                isVideo = media.type == MediaWrapper.TYPE_VIDEO,
                explicitlyOpenedFolder = explicitlyOpenedFolder
            )) return media
        val medialibrary = Medialibrary.getInstance()
        return medialibrary.getMedia(media.uri)?.takeIf { it.id > 0L }
            ?: medialibrary.addMedia(media.uri.toString(), media.length)?.takeIf { it.id > 0L }
            ?: media
    }

    data class ThumbnailRefreshResult(val scanned: Int, val replaced: Int, val requested: Int)
    private enum class ThumbnailRefreshChange { UNCHANGED, REPLACED, REQUESTED }

    /**
     * Explicit bulk refresh checks all videos in the selected folder (including
     * subfolders), group or playlist, without replacing good thumbnails.
     * Paged queries and coroutine cancellation keep the work manageable.
     */
    suspend fun refreshSelectedThumbnails(item: MediaLibraryItem, width: Int = defaultRefreshWidth()): ThumbnailRefreshResult {
        val requestedWidth = width.coerceIn(1, 1024)
        var scanned = 0
        var replaced = 0
        var requested = 0

        suspend fun process(media: MediaWrapper?, onlyBad: Boolean) {
            if (media == null || media.type != MediaWrapper.TYPE_VIDEO) return
            currentCoroutineContext().ensureActive()
            val refreshMedia = resolveMediaForThumbnail(media, explicitlyOpenedFolder = true)
            scanned++
            when (refreshSingleVideoThumbnail(refreshMedia, requestedWidth, onlyBad)) {
                ThumbnailRefreshChange.REPLACED -> replaced++
                ThumbnailRefreshChange.REQUESTED -> {
                    requested++
                    // Native network extraction is asynchronous: avoid flooding its queue.
                    delay(150L)
                }
                ThumbnailRefreshChange.UNCHANGED -> Unit
            }
        }

        when (item) {
            is MediaWrapper -> process(item, false)
            is Folder -> {
                val pending = ArrayDeque<Folder>()
                val visited = HashSet<String>()
                pending.add(item)
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val folder = pending.removeFirst()
                    if (!visited.add(folder.id.toString() + ":" + folder.mMrl)) continue
                    var offset = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val page = folder.media(Folder.TYPE_FOLDER_VIDEO,
                            Medialibrary.SORT_DEFAULT, true, false, false, REFRESH_BATCH_SIZE, offset)
                        if (page.isEmpty()) break
                        for (media in page) process(media, true)
                        offset += page.size
                        if (page.size < REFRESH_BATCH_SIZE) break
                    }
                    offset = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val page = folder.subfolders(Medialibrary.SORT_DEFAULT, true,
                            false, false, REFRESH_BATCH_SIZE, offset)
                        if (page.isEmpty()) break
                        for (child in page) if (child != null) pending.add(child)
                        offset += page.size
                        if (page.size < REFRESH_BATCH_SIZE) break
                    }
                    BitmapCache.removeBitmapKeysStartingWith("folder:${folder.mMrl.sanitizePath()}:")
                }
            }
            is VideoGroup -> {
                var offset = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = item.media(Medialibrary.SORT_DEFAULT, true, false, false,
                        REFRESH_BATCH_SIZE, offset)
                    if (page.isEmpty()) break
                    for (media in page) process(media, true)
                    offset += page.size
                    if (page.size < REFRESH_BATCH_SIZE) break
                }
                BitmapCache.removeBitmapKeysStartingWith("videogroup:${item.title}:")
            }
            is org.videolan.medialibrary.interfaces.media.Playlist -> {
                var offset = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = item.getPagedTracks(REFRESH_BATCH_SIZE, offset, false, false)
                    if (page.isEmpty()) break
                    for (media in page) process(media, true)
                    offset += page.size
                    if (page.size < REFRESH_BATCH_SIZE) break
                }
                BitmapCache.removeBitmapKeysStartingWith("playlist:${item.id}_")
            }
        }
        return ThumbnailRefreshResult(scanned, replaced, requested)
    }

    suspend fun refreshSelectedThumbnails(mediaItems: List<MediaWrapper>, width: Int = defaultRefreshWidth()): ThumbnailRefreshResult {
        val requestedWidth = width.coerceIn(1, 1024)
        var scanned = 0
        var replaced = 0
        var requested = 0
        for (media in mediaItems) {
            if (media.type != MediaWrapper.TYPE_VIDEO) continue
            currentCoroutineContext().ensureActive()
            val refreshMedia = resolveMediaForThumbnail(media, explicitlyOpenedFolder = true)
            scanned++
            when (refreshSingleVideoThumbnail(refreshMedia, requestedWidth, onlyBad = true)) {
                ThumbnailRefreshChange.REPLACED -> replaced++
                ThumbnailRefreshChange.REQUESTED -> {
                    requested++
                    delay(150L)
                }
                ThumbnailRefreshChange.UNCHANGED -> Unit
            }
        }
        return ThumbnailRefreshResult(scanned, replaced, requested)
    }

    /**
     * Never download remote artwork just to decide if a refresh is necessary.
     * The indexed medialibrary JPEG path also handles media without artworkMrl.
     */
    private fun localArtworkForInspection(media: MediaWrapper, width: Int): Bitmap? {
        val artwork = media.artworkMrl
        val path = if (!artwork.isNullOrBlank()) {
            if (!artwork.startsWith("/") && !artwork.startsWith("file:")) return null
            Uri.decode(artwork).removePrefix("file://")
        } else {
            if (media.id <= 0L) return null
            val dir = AppContextProvider.appContext.getExternalFilesDir(null) ?: return null
            File(dir.absolutePath + MEDIALIB_FOLDER_NAME, "${media.id}.jpg").absolutePath
        }
        if (!File(path).isFile) return null
        return readCoverBitmap(path, width)
    }

    @WorkerThread
    private fun refreshSingleVideoThumbnail(media: MediaWrapper, width: Int, onlyBad: Boolean): ThumbnailRefreshChange {
        if (media.type != MediaWrapper.TYPE_VIDEO) return ThumbnailRefreshChange.UNCHANGED
        val oldArtwork = media.artworkMrl
        // Preserve externally supplied artwork URLs.
        if (!oldArtwork.isNullOrBlank() &&
            !oldArtwork.startsWith("/") && !oldArtwork.startsWith("file:")) {
            return ThumbnailRefreshChange.UNCHANGED
        }
        val existing = localArtworkForInspection(media, width)
        val existingQuality = existing?.let(::frameQuality)
        val needsResolutionAdjustment = existing?.let {
            ThumbnailCachePolicy.needsResolutionAdjustment(it.width, it.height, width)
        } == true
        if (onlyBad && existingQuality != null && !existingQuality.likelyIntroOrBlank && !needsResolutionAdjustment)
            return ThumbnailRefreshChange.UNCHANGED

        if (media.uri.scheme.isSchemeFile()) {
            val selected = betterLocalVideoFrame(media, existing, manual = true, targetSize = width)
                ?: return ThumbnailRefreshChange.UNCHANGED
            val selectedQuality = frameQuality(selected)
            // Preserve genuinely better existing frames and do not install blank replacements.
            if (existingQuality != null &&
                selectedQuality.score < existingQuality.score + if (onlyBad && !needsResolutionAdjustment) 0.06f else -0.04f)
                return ThumbnailRefreshChange.UNCHANGED
            val existingMaxDimension = existing?.let { maxOf(it.width, it.height) } ?: 0
            if (needsResolutionAdjustment && maxOf(selected.width, selected.height) == existingMaxDimension)
                return ThumbnailRefreshChange.UNCHANGED
            val oldKey = getMediaThumbnailPath(true, media)
            if (media.id > 0L) {
                val dir = AppContextProvider.appContext.getExternalFilesDir(null)
                    ?: return ThumbnailRefreshChange.UNCHANGED
                val target = File(dir.absolutePath + MEDIALIB_FOLDER_NAME, "${media.id}.jpg")
                if (target.parentFile?.isDirectory != true &&
                    target.parentFile?.mkdirs() != true) return ThumbnailRefreshChange.UNCHANGED
                if (!BitmapUtil.saveOnDisk(selected, target.absolutePath))
                    return ThumbnailRefreshChange.UNCHANGED
                media.setThumbnail(target.absolutePath)
                media.artworkURL = target.absolutePath
            }
            oldArtwork?.let {
                BitmapCache.removeBitmapFromMemCache(it)
                BitmapCache.removeBitmapKeysStartingWith(it + "_")
            }
            oldKey?.let {
                BitmapCache.removeBitmapFromMemCache(it)
                BitmapCache.removeBitmapKeysStartingWith(it + "_")
            }
            getMediaThumbnailPath(true, media)?.let {
                BitmapCache.removeBitmapFromMemCache(it)
                BitmapCache.removeBitmapKeysStartingWith(it + "_")
            }
            BitmapCache.addBitmapToMemCache(getMediaCacheKey(true, media, width.toString()), selected)
            return ThumbnailRefreshChange.REPLACED
        }

        // Only an explicit refresh may start a native SMB extraction.
        if (media.id <= 0L) return ThumbnailRefreshChange.UNCHANGED
        media.removeThumbnail()
        media.artworkURL = null
        oldArtwork?.let {
            BitmapCache.removeBitmapFromMemCache(it)
            BitmapCache.removeBitmapKeysStartingWith(it + "_")
        }
        media.requestThumbnail(width, 0.52f)
        return ThumbnailRefreshChange.REQUESTED
    }

    @WorkerThread
    fun getFolderThumbnail(folder: Folder, width: Int): Bitmap? {
        val media = folder.media(Folder.TYPE_FOLDER_VIDEO, Medialibrary.SORT_DEFAULT, true, true, false,4, 0).filterNotNull()
        return getComposedImage("folder:${folder.mMrl.sanitizePath()}", media, width)
    }

    @WorkerThread
    fun getVideoGroupThumbnail(group: VideoGroup, width: Int): Bitmap? {
        val media = group.media(Medialibrary.SORT_DEFAULT, true, true, false, 4, 0).filterNotNull()
        return getComposedImage("videogroup:${group.title}", media, width)
    }

    @WorkerThread
    fun getMediaThumbnail(item: MediaWrapper, width: Int, allowNetworkRequests: Boolean = true): Bitmap? {
        val thumbnailMedia = resolveMediaForThumbnail(item, explicitlyOpenedFolder = allowNetworkRequests)
        return if (isMediaVideo(thumbnailMedia) || isOurLocalVideoThumbnail(thumbnailMedia))
            getVideoThumbnail(thumbnailMedia, width, allowNetworkRequests)
        else
            readCoverBitmap(Uri.decode(thumbnailMedia.artworkMrl), width)
    }

    fun isMediaVideo(item: MediaWrapper) = item.type == MediaWrapper.TYPE_VIDEO && item.artworkMrl.isNullOrEmpty()

    private fun getMediaThumbnailPath(isMedia: Boolean, item: MediaLibraryItem): String? {
        if (isMedia && isMediaVideo(item as MediaWrapper)) {
            if (item.id == 0L) return item.uri.toString()
            if (appDir == null) appDir = AppContextProvider.appContext.getExternalFilesDir(null)
            val hasCache = appDir != null && appDir!!.exists()
            if (hasCache && cacheDir == null) cacheDir = appDir!!.absolutePath + MEDIALIB_FOLDER_NAME
            // Keep a stable in-memory cache key if app-scoped disk storage is unavailable.
            return cacheDir?.let { "$it/${item.id}.jpg" } ?: item.uri.toString()
        }
        return item.artworkMrl
    }

    fun getMediaCacheKey(isMedia: Boolean, item: MediaLibraryItem, width: String = "") = if (width.isEmpty()) getMediaThumbnailPath(isMedia, item) else "${getMediaThumbnailPath(isMedia, item)}_$width"

    @WorkerThread
    fun getVideoThumbnail(media: MediaWrapper, width: Int, allowNetworkRequests: Boolean = true, isAggregateCover: Boolean = false): Bitmap? {
        val filePath = media.uri.path ?: return null
        if (appDir == null) appDir = AppContextProvider.appContext.getExternalFilesDir(null)
        val hasCache = appDir?.exists() == true
        val thumbPath = getMediaThumbnailPath(true, media) ?: return null
        // Memory-cached thumbnails remain usable even when external disk storage is unavailable.
        val cacheKey = getMediaCacheKey(true, media, width.toString())
        val cacheBM = BitmapCache.getBitmapFromMemCache(cacheKey)
        if (cacheBM != null) return improveCachedVideoThumbnail(media, cacheBM, thumbPath, width)
        var cachedThumbnailUnavailable = false
        if (hasCache && media.id > 0L) {
            if (File(thumbPath).isFile) {
                readCoverBitmap(thumbPath, width)?.let { cached ->
                    val selected = improveCachedVideoThumbnail(media, cached, thumbPath, width)
                    BitmapCache.addBitmapToMemCache(cacheKey, selected)
                    return selected
                }
            }
            // Missing or undecodable cache files must not suppress thumbnail generation.
            cachedThumbnailUnavailable = true
        }
        if (media.isThumbnailGenerated) {
            if (!ThumbnailCachePolicy.shouldClearGeneratedFlag(media.isThumbnailGenerated, cachedThumbnailUnavailable))
                return null
            media.removeThumbnail()
        }
        var bitmap = synchronized(lock) {
            if (media.uri.scheme.isSchemeFile()) ThumbnailUtils.createVideoThumbnail(filePath, MediaStore.Video.Thumbnails.MINI_KIND) else null
        }
        bitmap?.let { bmp ->
            bmp.config?.let { config ->
                val emptyBitmap = Bitmap.createBitmap(bmp.width, bmp.height, config)
                if (bmp.sameAs(emptyBitmap)) { // myBitmap is empty/blank3
                    bitmap = null
                }
            }
        }
        if (bitmap != null) {
            bitmap = betterLocalVideoFrame(media, bitmap!!) ?: bitmap
            BitmapCache.addBitmapToMemCache(cacheKey, bitmap)
            if (hasCache && media.id > 0L) {
                BitmapUtil.saveOnDisk(bitmap!!, thumbPath)
                media.setThumbnail(thumbPath)
                media.artworkURL = thumbPath
            }
        } else if (media.id != 0L && ThumbnailGenerationPolicy.shouldRequestNetworkThumbnail(
                isLocalFile = media.uri.scheme.isSchemeFile(),
                explicitlyOpenedFolder = allowNetworkRequests,
                isAggregateCover = isAggregateCover
            )) {
            media.requestThumbnail(width, 0.4f)
        }
        return bitmap
    }

    suspend fun getPlaylistOrGenreImage(key: String, mediaList: List<MediaWrapper>, width: Int, iconAddition: Bitmap? = null): Bitmap? {
        // to force the thumbnail regeneration on change, we append the ids of the media that will be used to the cache key
        val saltedKey = key + ":" + width + getArtworkListForPlaylistOrGenre(mediaList).joinToString("_", ":") { "${it.id}:${it.artworkURL.orEmpty()}" }
        if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "Salted key from $key is $saltedKey")
        return (BitmapCache.getBitmapFromMemCache(saltedKey) ?: composePlaylistOrGenreImage(mediaList, width, iconAddition))?.also {
            BitmapCache.addBitmapToMemCache(saltedKey, it)
        }
    }

    /**
     * Retrieve the images to be used for the playlist/genre's thumbnail
     * @param mediaList the media list for the playlist or genre
     * @return a sanitied list of media to be used for the playlist thumbnail composition
     */
    private fun getArtworkListForPlaylistOrGenre(mediaList: List<MediaWrapper>):ArrayList<MediaWrapper> {
        if (mediaList.isEmpty()) return arrayListOf()
        val url = mediaList[0].artworkURL
        val isAllSameImage = !mediaList.any { it.artworkURL != url }
        if (isAllSameImage) return arrayListOf(mediaList[0])
        val artworks = ArrayList<MediaWrapper>()
        for (mediaWrapper in mediaList) {

            val artworkAlreadyHere = artworks.any { it.artworkURL == mediaWrapper.artworkURL }

            if (!artworkAlreadyHere && !mediaWrapper.artworkURL.isNullOrBlank()) {
                artworks.add(mediaWrapper)
            }
            if (artworks.size > 3) {
                break
            }
        }

        if (artworks.size == 2) {
            artworks.add(artworks[1])
            artworks.add(artworks[0])
        } else if (artworks.size == 3) {
            artworks.add(artworks[0])
        }
        return artworks
    }

    /**
     * Compose 1 image from tracks of a Playlist or a genre
     * @param mediaList The track list of the playlist or genre
     * @return a Bitmap object
     */
    private suspend fun composePlaylistOrGenreImage(mediaList: List<MediaWrapper>, width: Int, iconAddition: Bitmap?): Bitmap? {
        val artworks = getArtworkListForPlaylistOrGenre(mediaList)
        if (artworks.isEmpty()) return null

        val sameImage = if (artworks.size == 1) obtainBitmap(artworks[0], width, allowNetworkRequests = false)
                ?: return null else null

        val cs = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val comboImage = Canvas(cs)

        if (sameImage != null) {
            /* Scale the cover art, as obtainBitmap may return a larger or smaller image size */
            comboImage.drawBitmap(sameImage, Rect(0, 0, sameImage.width, sameImage.height), Rect(0, 0, width, width), null)
        } else {
            val images = ArrayList<Bitmap>(4)
            artworks.forEach {
                val image = obtainBitmap(it, width / 2, allowNetworkRequests = false)
                if (image != null) {
                    images.add(image)
                }
                if (images.size >= 4) {
                    return@forEach
                }

            }


            for (i in 0..3) {
                if (images.size < i + 1) {
                    images.add(UiTools.getDefaultAudioDrawable(AppContextProvider.appContext).bitmap)
                    /* Place the first image on the diagonal */
                    if (images.size == 3) {
                        images.add(images[0])
                    }
                }
            }

            comboImage.drawBitmap(images[0], Rect(0, 0, images[0].width, images[0].height), Rect(0, 0, width / 2, width / 2), null)
            comboImage.drawBitmap(images[1], Rect(0, 0, images[1].width, images[1].height), Rect(width / 2, 0, width, width / 2), null)
            comboImage.drawBitmap(images[2], Rect(0, 0, images[2].width, images[2].height), Rect(0, width / 2, width / 2, width), null)
            comboImage.drawBitmap(images[3], Rect(0, 0, images[3].width, images[3].height), Rect(width / 2, width / 2, width, width), null)
        }

        iconAddition?.let {
            comboImage.drawBitmap(iconAddition, (comboImage.width.toFloat() - iconAddition.width) / 2, (comboImage.height.toFloat() - iconAddition.height) / 2, null)
        }

        return cs
    }

    suspend fun obtainBitmap(item: MediaLibraryItem, width: Int, allowNetworkRequests: Boolean = true) = withContext(Dispatchers.IO) {
        when (item) {
            is MediaWrapper -> getMediaThumbnail(item, width, allowNetworkRequests)
            is Folder -> getFolderThumbnail(item, width)
            is VideoGroup -> getVideoGroupThumbnail(item, width)
            else -> readCoverBitmap(Uri.decode(item.artworkMrl), width)
        }
    }


    @WorkerThread
    fun getComposedImage(key: String, mediaList: List<MediaWrapper>, width: Int): Bitmap? {
        // Invalidate folder/group covers when their source thumbnails or requested size change.
        val cacheKey = "$key:$width:" + mediaList.take(MAX_IMAGES).joinToString("_") { "${it.id}:${it.artworkURL.orEmpty()}" }
        var composedImage = BitmapCache.getBitmapFromMemCache(cacheKey)
        if (composedImage == null) {
            composedImage = composeImage(mediaList, width)
            if (composedImage != null) BitmapCache.addBitmapToMemCache(cacheKey, composedImage)
        }
        return composedImage
    }

    /**
     * Compose 1 image from combined media thumbnails
     * @param mediaList The media list from which will extract thumbnails
     * @return a Bitmap object
     */
    private fun composeImage(mediaList: List<MediaWrapper>, imageWidth: Int): Bitmap? {
        val sourcesImages = arrayOfNulls<Bitmap>(min(MAX_IMAGES, mediaList.size))
        var count = 0
        var minWidth = Integer.MAX_VALUE
        var minHeight = Integer.MAX_VALUE
        for (media in mediaList) {
            val bm = getVideoThumbnail(media, imageWidth, allowNetworkRequests = false, isAggregateCover = true)
            if (bm != null) {
                val width = bm.width
                val height = bm.height
                sourcesImages[count++] = bm
                minWidth = min(minWidth, width)
                minHeight = min(minHeight, height)
                if (count == MAX_IMAGES) break
            }
        }
        if (count == 0) return null

        return if (count == 1) sourcesImages[0] else composeCanvas(sourcesImages.filterNotNull().toTypedArray(), count, minWidth, minHeight)
    }

    private fun composeCanvas(sourcesImages: Array<Bitmap>, count: Int, minWidth: Int, minHeight: Int): Bitmap {
        val overlayWidth: Int
        val overlayHeight: Int
        when (count) {
            4 -> {
                overlayWidth = 2 * minWidth
                overlayHeight = 2 * minHeight
            }
            else -> {
                overlayWidth = minWidth
                overlayHeight = minHeight
            }
        }
        val bmOverlay = Bitmap.createBitmap(overlayWidth, overlayHeight, sourcesImages[0].config!!)

        val canvas = Canvas(bmOverlay)
        when (count) {
            2 -> {
                for (i in 0 until count)
                    sourcesImages[i] = BitmapUtil.centerCrop(sourcesImages[i], minWidth / 2, minHeight)
                canvas.drawBitmap(sourcesImages[0], 0f, 0f, null)
                canvas.drawBitmap(sourcesImages[1], (minWidth / 2).toFloat(), 0f, null)
            }
            3 -> {
                sourcesImages[0] = BitmapUtil.centerCrop(sourcesImages[0], minWidth / 2, minHeight / 2)
                sourcesImages[1] = BitmapUtil.centerCrop(sourcesImages[1], minWidth / 2, minHeight / 2)
                sourcesImages[2] = BitmapUtil.centerCrop(sourcesImages[2], minWidth, minHeight / 2)
                canvas.drawBitmap(sourcesImages[0], 0f, 0f, null)
                canvas.drawBitmap(sourcesImages[1], (minWidth / 2).toFloat(), 0f, null)
                canvas.drawBitmap(sourcesImages[2], 0f, (minHeight / 2).toFloat(), null)
            }
            4 -> {
                for (i in 0 until count)
                    sourcesImages[i] = BitmapUtil.centerCrop(sourcesImages[i], minWidth, minHeight)
                canvas.drawBitmap(sourcesImages[0], 0f, 0f, null)
                canvas.drawBitmap(sourcesImages[1], minWidth.toFloat(), 0f, null)
                canvas.drawBitmap(sourcesImages[2], 0f, minHeight.toFloat(), null)
                canvas.drawBitmap(sourcesImages[3], minWidth.toFloat(), minHeight.toFloat(), null)
            }
        }
        return bmOverlay
    }
}
