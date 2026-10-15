package org.videolan.vlc.viewmodels

import androidx.core.net.toUri
import com.jraska.livedata.test
import junit.framework.TestCase.assertEquals
import org.junit.Test
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.stubs.StubMediaWrapper
import org.videolan.vlc.BaseTest
import org.videolan.vlc.util.TestCoroutineContextProvider
import org.videolan.vlc.util.TestUtil


class StreamsModelTest : BaseTest() {
    private var networkHistory: List<MediaWrapper> = emptyList()
    private lateinit var streamsModel: StreamsModel

    override fun beforeTest() {
        super.beforeTest()
        streamsModel = StreamsModel(
                application,
                coroutineContextProvider = TestCoroutineContextProvider(),
                networkHistoryProvider = { networkHistory })
    }

    @Test
    fun whenRefreshCalled_ListIsUpdated() {
        val fakeMediaStrings = TestUtil.createNetworkUris(2)

        streamsModel.dataset.test()
                .awaitValue()
                .assertValue(emptyList<MediaWrapper>())

        val expected = fakeMediaStrings.map { StubMediaWrapper(it.toUri()) }
        networkHistory = expected

        streamsModel.refresh()
        Thread.sleep(200)

        val testResult = streamsModel.dataset
                .test()
                .awaitValue()
                .assertHasValue()
                .value()

        assertEquals(expected.size, testResult.size)
        assertEquals(expected[0], testResult[0])
        assertEquals(expected[1], testResult[1])
    }

    @Test
    fun whenRenameCalledAtPos_MediaTitleIsUpdated() {
        val fakeMediaStrings = TestUtil.createNetworkUris(2)
        val expected = fakeMediaStrings.map { StubMediaWrapper(it.toUri()) }
        networkHistory = expected

        streamsModel.refresh()
        streamsModel.dataset.test()
                .awaitValue()
                .assertHasValue()

        val pos = 0
        val media = expected[pos]
        val newMediaTitle = "${media.title}~new"

        streamsModel.rename(media, newMediaTitle)
        Thread.sleep(200)

        val testResult = streamsModel.dataset.test()
                .awaitValue()
                .assertHasValue()
                .value()

        assertEquals(newMediaTitle, testResult[pos].title)
    }
}
