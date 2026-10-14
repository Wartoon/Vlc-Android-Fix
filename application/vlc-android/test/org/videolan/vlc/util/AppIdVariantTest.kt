package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.videolan.resources.BuildConfig as ResourcesBuildConfig
import org.videolan.vlc.BuildConfig as VlcBuildConfig

class AppIdVariantTest {
    @Test
    fun libraryPackageIdsMatchInstallableAppVariant() {
        val suffix = when (VlcBuildConfig.BUILD_TYPE) {
            "internalStorage" -> ".internalstorage"
            "smb" -> ".smb"
            "tvFix" -> ".tvfix"
            "debug" -> ".debug"
            else -> error("Unexpected test build type: ${VlcBuildConfig.BUILD_TYPE}")
        }
        val expected = "org.videolan.vlc$suffix"

        assertEquals(expected, VlcBuildConfig.APP_ID)
        assertEquals(expected, ResourcesBuildConfig.APP_ID)
    }
}
