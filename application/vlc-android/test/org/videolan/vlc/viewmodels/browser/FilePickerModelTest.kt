package org.videolan.vlc.viewmodels.browser

import android.net.Uri
import android.os.Looper
import com.jraska.livedata.test
import io.mockk.every
import io.mockk.mockk
import junit.framework.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.robolectric.Shadows
import org.junit.rules.TemporaryFolder
import org.videolan.libvlc.interfaces.ILibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser.EventListener
import org.videolan.libvlc.stubs.StubMedia
import org.videolan.libvlc.util.MediaBrowser
import org.videolan.medialibrary.stubs.StubMediaWrapper
import org.videolan.vlc.BaseTest
import org.videolan.vlc.providers.BrowserProvider
import org.videolan.tools.CoroutineContextProvider
import org.videolan.vlc.util.TestCoroutineContextProvider
import java.io.File
import java.util.concurrent.TimeUnit

class FilePickerModelTest : BaseTest() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mockedLibVlc: ILibVLC = mockk(relaxed = true)
    private lateinit var dummyUrl: String
    private lateinit var mediaBrowser: MediaBrowser
    private var eventListener: EventListener? = null

    private lateinit var browserModel: BrowserModel
    private lateinit var browserProvider: BrowserProvider

    private val countVideos = 2
    private val countDirs = 4

    private fun idleMainLooper() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    init {
        BrowserProvider.overrideCreator = true
        BrowserProvider.registerCreator {
            mediaBrowser = mockk(relaxed = true)
            every { mediaBrowser.changeEventListener(any()) } answers { eventListener = args[0] as? EventListener }
            every { mediaBrowser.browse(any<Uri>(), any<Int>()) } answers {
                val root = File((args[0] as Uri).path ?: "")
                root.listFiles()?.forEachIndexed { index, file ->
                    val media = StubMedia(mockedLibVlc, Uri.fromFile(file).toString()).apply {
                        if (file.isDirectory || !file.name.endsWith(".mp4")) type = IMedia.Type.Directory
                    }
                    eventListener?.onMediaAdded(index, media)
                }
                eventListener?.onBrowseEnd()
                Unit
            }
            mediaBrowser
        }
        BrowserProvider.registerCreator(clazz = CoroutineContextProvider::class.java) { org.videolan.vlc.util.TestCoroutineContextProvider() }
        BrowserProvider.overrideCreator = false
    }

    override fun beforeTest() {
        super.beforeTest()
        dummyUrl = temporaryFolder.root.absolutePath

        browserModel = BrowserModel(application, dummyUrl, TYPE_PICKER, false, coroutineContextProvider = TestCoroutineContextProvider())
        browserProvider = browserModel.provider
        idleMainLooper()

        setupTestFiles()
    }

    private fun setupTestFiles() {
        (0 until countDirs).map { temporaryFolder.newFile("dir$it") }
        (0 until countVideos).map { temporaryFolder.newFile("video$it.mp4") }
    }

    private fun addFileToProvider(i: Int, file: File) {
        if (file.name.endsWith(".mp4")) return
        val t = StubMedia(mockedLibVlc, "file://${file.path}").apply { if (!file.name.endsWith(".mp4")) type = IMedia.Type.Directory }
        browserProvider.addMedia(StubMediaWrapper(t))
    }

    @Test
    fun whenBrowseRootAndRootHasFiles_getListOfDirectories() {
        temporaryFolder.root.listFiles().mapIndexed(this::addFileToProvider)

        val testResult = browserModel.dataset.test()
                .value()

        assertEquals(countDirs, testResult.size)
        assert(testResult[0].title.startsWith("dir"))
    }

    @Test
    fun whenBrowseRootAndRootEmpty_checkTheFolderIsEmpty() {
        temporaryFolder.root.listFiles()?.forEach { it.deleteRecursively() }
        val observer = browserModel.dataset.test()
        browserModel.refresh()
        idleMainLooper()
        observer.awaitValue(3, TimeUnit.SECONDS)
                .assertValue { it.isEmpty() }
    }

    @Test
    fun whenBrowseRootAndBrowseEndEventTriggered_ensureLoadingIsSetToFalse() {
        val observer = browserModel.loading.test()
        browserModel.refresh()
        idleMainLooper()
        observer.awaitValue(3, TimeUnit.SECONDS)
                .awaitValue(3, TimeUnit.SECONDS)
                .awaitValue(3, TimeUnit.SECONDS)
                .assertValue(false)
    }

    @Test
    fun whenRootHasFilesAndRefreshCalled_getUpdatedListOfDirectories() {
        temporaryFolder.root.listFiles().mapIndexed(this::addFileToProvider)

        var result = browserModel.dataset.test()
                .value()

        assertEquals(countDirs, result.size)

        browserModel.refresh()
        idleMainLooper()

        val newDirectory = temporaryFolder.newFile("dir${countDirs + 1}")
        addFileToProvider(countDirs, newDirectory)

        result = browserModel.dataset.test()
                .value()

        assertEquals(countDirs + 1, result.size)
    }
}
