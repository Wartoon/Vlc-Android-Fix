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
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.Medialibrary.MEDIALIB_FOLDER_NAME
import org.videolan.medialibrary.interfaces.media.Folder
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.VideoGroup
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AppContextProvider
import org.videolan.tools.BitmapCache
import org.videolan.tools.sanitizePath
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.gui.helpers.AudioUtil.readCoverBitmap
import org.videolan.vlc.gui.helpers.BitmapUtil
import org.videolan.vlc.gui.helpers.UiTools
import java.io.File
import kotlin.math.min

object ThumbnailsProvider {

    @Suppress("unused")
    private const val TAG = "VLC/ThumbnailsProvider"

    private var appDir: File? = null
    private var cacheDir: String? = null
    private const val MAX_IMAGES = 4
    private val lock = Any()
    // One extra frame-selection attempt per dark local video, bounded in memory and work.
    private val checkedDarkVideos = LinkedHashSet<String>()
    private const val MAX_DARK_CHECKS = 256

    private fun blackFraction(bitmap: Bitmap): Float {
        if (bitmap.width <= 0 || bitmap.height <= 0) return 0f
        var dark = 0
        try {
            for (y in 1..7) for (x in 1..7) {
                val pixel = bitmap.getPixel(bitmap.width * x / 8, bitmap.height * y / 8)
                val brightness = (Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel)) / 10
                if (brightness < 28) dark++
            }
        } catch (_: IllegalStateException) {
            return 0f // Do not attempt CPU sampling of hardware bitmaps.
        }
        return dark / 49f
    }

    /** Retry only substantially black frames, preserving good existing thumbnails. */
    private fun betterLocalVideoFrame(media: MediaWrapper, original: Bitmap): Bitmap? {
        if (!media.uri.scheme.isSchemeFile() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val initialBlack = blackFraction(original)
        if (initialBlack < 0.8f) return null
        synchronized(lock) {
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
            val originalMax = maxOf(original.width, original.height)
            if (originalMax <= 0) return null
            val scale = minOf(1f, 384f / originalMax)
            val targetWidth = (original.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (original.height * scale).toInt().coerceAtLeast(1)
            var best: Bitmap? = null
            var bestBlack = initialBlack
            for (position in floatArrayOf(0.22f, 0.52f, 0.76f)) {
                val frame = try {
                    retriever.getScaledFrameAtTime((durationMs * 1000 * position).toLong(),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
                } catch (_: RuntimeException) {
                    null
                } ?: continue
                val fraction = blackFraction(frame)
                if (fraction < bestBlack) {
                    best = frame
                    bestBlack = fraction
                }
                if (bestBlack < 0.35f) break
            }
            // Never overwrite a valid dark scene for a merely marginal improvement.
            return best?.takeIf { bestBlack <= initialBlack - 0.2f }
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
    fun getMediaThumbnail(item: MediaWrapper, width: Int): Bitmap? {
        return if (isMediaVideo(item) || isOurLocalVideoThumbnail(item))
            getVideoThumbnail(item, width)
        else
            readCoverBitmap(Uri.decode(item.artworkMrl), width)
    }

    fun isMediaVideo(item: MediaWrapper) = item.type == MediaWrapper.TYPE_VIDEO && item.artworkMrl.isNullOrEmpty()

    private fun getMediaThumbnailPath(isMedia: Boolean, item: MediaLibraryItem): String? {
        if (isMedia && isMediaVideo(item as MediaWrapper)) {
            if (item.id == 0L) return item.uri.toString()
            if (appDir == null) appDir = AppContextProvider.appContext.getExternalFilesDir(null)
            val hasCache = appDir != null && appDir!!.exists()
            if (hasCache && cacheDir == null) cacheDir = appDir!!.absolutePath + MEDIALIB_FOLDER_NAME
            return if (hasCache) StringBuilder(cacheDir!!).append('/').append(item.id).append(".jpg").toString() else null
        }
        return item.artworkMrl
    }

    fun getMediaCacheKey(isMedia: Boolean, item: MediaLibraryItem, width: String = "") = if (width.isEmpty()) getMediaThumbnailPath(isMedia, item) else "${getMediaThumbnailPath(isMedia, item)}_$width"

    @WorkerThread
    fun getVideoThumbnail(media: MediaWrapper, width: Int): Bitmap? {
        val filePath = media.uri.path ?: return null
        if (appDir == null) appDir = AppContextProvider.appContext.getExternalFilesDir(null)
        val hasCache = appDir?.exists() == true
        val thumbPath = getMediaThumbnailPath(true, media) ?: return null
        // Memory-cached thumbnails remain usable even when external disk storage is unavailable.
        val cacheKey = getMediaCacheKey(true, media, width.toString())
        val cacheBM = BitmapCache.getBitmapFromMemCache(cacheKey)
        if (cacheBM != null) return improveCachedVideoThumbnail(media, cacheBM, thumbPath, width)
        if (hasCache && media.id > 0L && File(thumbPath).isFile) {
            readCoverBitmap(thumbPath, width)?.let { cached ->
                val selected = improveCachedVideoThumbnail(media, cached, thumbPath, width)
                BitmapCache.addBitmapToMemCache(cacheKey, selected)
                return selected
            }
            // A stale or unreadable cached JPEG must not suppress thumbnail generation.
        }
        if (media.isThumbnailGenerated) return null
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
        } else if (media.id != 0L) {
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

        val sameImage = if (artworks.size == 1) obtainBitmap(artworks[0], width)
                ?: return null else null

        val cs = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val comboImage = Canvas(cs)

        if (sameImage != null) {
            /* Scale the cover art, as obtainBitmap may return a larger or smaller image size */
            comboImage.drawBitmap(sameImage, Rect(0, 0, sameImage.width, sameImage.height), Rect(0, 0, width, width), null)
        } else {
            val images = ArrayList<Bitmap>(4)
            artworks.forEach {
                val image = obtainBitmap(it, width / 2)
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

    suspend fun obtainBitmap(item: MediaLibraryItem, width: Int) = withContext(Dispatchers.IO) {
        when (item) {
            is MediaWrapper -> getMediaThumbnail(item, width)
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
            val bm = getVideoThumbnail(media, imageWidth)
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
