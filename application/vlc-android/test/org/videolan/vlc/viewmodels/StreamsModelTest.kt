package org.videolan.vlc.viewmodels

import com.jraska.livedata.test
import junit.framework.TestCase.assertEquals
import org.junit.Test
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.vlc.BaseTest
import org.videolan.vlc.util.TestCoroutineContextProvider
import org.videolan.vlc.util.TestUtil


class StreamsModelTest : BaseTest() {
    private val mediaLibrary: Medialibrary = Medialibrary.getInstance()
    private lateinit var streamsModel: StreamsModel

    override fun beforeTest() {
        super.beforeTest()
        mediaLibrary.clearHistory(Medialibrary.HISTORY_TYPE_GLOBAL)
        streamsModel = StreamsModel(application, coroutineContextProvider = TestCoroutineContextProvider())
    }

    @Test
    fun whenRefreshCalled_ListIsUpdated() {
        val fakeMediaStrings = TestUtil.createNetworkUris(2)

        streamsModel.dataset.test()
                .awaitValue()
                .assertValue(Medialibrary.EMPTY_COLLECTION.toMutableList())

        fakeMediaStrings.forEach { location ->
            mediaLibrary.addToHistory(location, location)
        }
        val result = mediaLibrary.history(Medialibrary.HISTORY_TYPE_NETWORK)

        streamsModel.refresh()
        Thread.sleep(200)

        val testResult = streamsModel.dataset
                .test()
                .awaitValue()
                .assertHasValue()
                .value()

        assertEquals(result.size, testResult.size)
        assertEquals(result[0], testResult[0])
        assertEquals(result[1], testResult[1])
    }

    @Test
    fun whenRenameCalledAtPos_MediaTitleIsUpdated() {
        val pos = 0
        val fakeMediaStrings = TestUtil.createNetworkUris(2)

        fakeMediaStrings.forEach { location ->
            mediaLibrary.addToHistory(location, location)
        }
        val result = mediaLibrary.history(Medialibrary.HISTORY_TYPE_NETWORK)
        val oldMediaTitle = result[pos].title
        val media = result[pos]

        streamsModel.refresh()

        streamsModel.dataset.test()
                .awaitValue()

        val newMediaTitle = "$oldMediaTitle~new"

        streamsModel.rename(media, newMediaTitle)
        Thread.sleep(200)

        val testResult = streamsModel.dataset.test()
                .awaitValue()
                .assertHasValue()
                .value()

        assertEquals(newMediaTitle, testResult[pos].title)
    }
}
