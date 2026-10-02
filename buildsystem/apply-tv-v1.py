#!/usr/bin/env python3
# VLC TV Fix V1 reproducible build patch.
from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"Expected source block not found in {path}: {old[:80]!r}")
    text = text.replace(old, new, count)
    p.write_text(text)
    print(f"patched {path}")

# One independently installable TV test build. It is still the complete VLC app.
replace("application/app/build.gradle", '''        dev {
            initWith debug
            matchingFallbacks = ['debug']
        }
        internalStorage {''', '''        dev {
            initWith debug
            matchingFallbacks = ['debug']
        }
        tvFix {
            initWith debug
            applicationIdSuffix ".tvfix"
            matchingFallbacks = ['debug']
            resValue "string", "app_name", "VLC TV Fix"
            resValue "string", "tv_provider_authority", "org.videolan.vlc.tvfix.tvsearch"
        }
        internalStorage {''')
replace("application/app/build.gradle", '''            if (variant.buildType.name == "internalStorage") outputName = "VLC-Internal-Storage"
            if (variant.buildType.name == "smb") outputName = "VLC-SMB"''', '''            if (variant.buildType.name == "internalStorage") outputName = "VLC-Internal-Storage"
            if (variant.buildType.name == "smb") outputName = "VLC-SMB"
            if (variant.buildType.name == "tvFix") outputName = "VLC-TV-Fix"''')

# Tested TV playback/network defaults.
replace("application/vlc-android/res/xml/preferences.xml", '''        <ListPreference
                android:defaultValue="-1"
                android:entries="@array/hardware_acceleration_list"
                android:entryValues="@array/hardware_acceleration_values"
                android:key="hardware_acceleration"''', '''        <ListPreference
                android:defaultValue="2"
                android:entries="@array/hardware_acceleration_list"
                android:entryValues="@array/hardware_acceleration_values"
                android:key="hardware_acceleration"''')
replace("application/vlc-android/res/xml/preferences_adv.xml", '''    <EditTextPreference
            android:defaultValue="0"
            android:key="network_caching"''', '''    <EditTextPreference
            android:defaultValue="5000"
            android:key="network_caching"''')
replace("application/vlc-android/res/xml/preferences_adv.xml", '''    <CheckBoxPreference
            app:singleLineTitle="false"
            android:defaultValue="true"
            android:key="prefer_smbv1"''', '''    <CheckBoxPreference
            app:singleLineTitle="false"
            android:defaultValue="false"
            android:key="prefer_smbv1"''')

# Make the defaults effective even before the preference UI has written explicit values.
replace("application/resources/src/main/java/org/videolan/resources/VLCOptions.kt", 'pref.getInt(KEY_NETWORK_CACHING_VALUE, 0).coerceIn(0, 60000)', 'pref.getInt(KEY_NETWORK_CACHING_VALUE, 5000).coerceIn(0, 60000)')
replace("application/resources/src/main/java/org/videolan/resources/VLCOptions.kt", 'pref.getBoolean(KEY_PREFER_SMBV1, true)', 'pref.getBoolean(KEY_PREFER_SMBV1, false)')
replace("application/resources/src/main/java/org/videolan/resources/VLCOptions.kt", 'prefs.getString(KEY_HARDWARE_ACCELERATION, "$HW_ACCELERATION_AUTOMATIC")', 'prefs.getString(KEY_HARDWARE_ACCELERATION, "$HW_ACCELERATION_FULL")')

# TV resume/progress: persist the current normal-video position when playback is paused/exited.
replace("application/vlc-android/src/org/videolan/vlc/media/PlaylistManager.kt", '            if (getCurrentMedia()?.isPodcast == true || playAsAudio) saveMediaMeta()', '            if (getCurrentMedia()?.type == MediaWrapper.TYPE_VIDEO || getCurrentMedia()?.isPodcast == true || playAsAudio) saveMediaMeta()')

# SMB "go to containing folder": preserve the original encoded MRL exactly.
# retrieveParent() rebuilds a Uri with Uri.Builder.authority(authority), which can encode the
# SMB host/port separator ':' as %3A. Build the parent from the existing MRL string instead,
# retaining the valid smb://host:port authority and already-encoded path components.
replace("application/television/src/main/java/org/videolan/television/ui/MediaItemDetailsFragment.kt", '''                ID_NAVIGATE_PARENT -> {
                    viewModel.media.uri.retrieveParent()?.let { item ->
                        val intent = Intent(activity, VerticalGridActivity::class.java)
                        intent.putExtra(MainTvActivity.BROWSER_TYPE, if ("file" == item.scheme) HEADER_DIRECTORIES else HEADER_NETWORK)
                        intent.putExtra(FAVORITE_TITLE, item.lastPathSegment)
                        intent.data = item
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                    }
                }''', '''                ID_NAVIGATE_PARENT -> {
                    val mediaUri = viewModel.media.uri
                    val item = if (mediaUri.scheme == "file") {
                        mediaUri.retrieveParent()
                    } else {
                        val mrl = mediaUri.toString().trimEnd('/')
                        val separator = mrl.lastIndexOf('/')
                        if (separator > mrl.indexOf("://") + 2) mrl.substring(0, separator + 1).toUri() else null
                    }
                    item?.let { parent ->
                        val intent = Intent(activity, VerticalGridActivity::class.java)
                        intent.putExtra(MainTvActivity.BROWSER_TYPE, if ("file" == parent.scheme) HEADER_DIRECTORIES else HEADER_NETWORK)
                        intent.putExtra(FAVORITE_TITLE, parent.lastPathSegment)
                        intent.data = parent
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                    }
                }''')

print("TV V1 patch applied successfully")
