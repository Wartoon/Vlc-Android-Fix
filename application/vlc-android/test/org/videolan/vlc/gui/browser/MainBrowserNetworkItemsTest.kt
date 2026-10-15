package org.videolan.vlc.gui.browser

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.medialibrary.media.DummyItem
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.MediaWrapperImpl

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MainBrowserNetworkItemsTest {

    @Test
    fun sectionHeadersAreNotShownBetweenMainPageNetworkCards() {
        val favorite = MediaWrapperImpl(Uri.parse("smb://server/favorite"))
        val discoveredShare = MediaWrapperImpl(Uri.parse("smb://server/share"))
        val providerList: List<MediaLibraryItem> = listOf(
            DummyItem("Network favorites"),
            favorite,
            DummyItem("Shared folders"),
            discoveredShare
        )

        val cards = providerList.withoutNetworkSectionHeaders()

        assertEquals(2, cards.size)
        assertSame(favorite, cards[0])
        assertSame(discoveredShare, cards[1])
        assertEquals(4, providerList.size) // Network browser sections remain unchanged.
    }

    @Test
    fun sectionsAreRemovedEvenWhenNoSharesWereDiscovered() {
        val favorite = MediaWrapperImpl(Uri.parse("smb://server/favorite"))
        val cards: List<MediaLibraryItem> = listOf(
            DummyItem("Network favorites"),
            favorite,
            DummyItem("Shared folders")
        )

        assertEquals(listOf(favorite), cards.withoutNetworkSectionHeaders())
    }

    @Test
    fun emptyListIsStillEmpty() {
        val items = emptyList<MediaLibraryItem>()
        assertEquals(emptyList<MediaLibraryItem>(), items.withoutNetworkSectionHeaders())
    }
}
