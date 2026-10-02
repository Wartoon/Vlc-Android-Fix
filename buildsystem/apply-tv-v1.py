#!/usr/bin/env python3
# VLC TV Fix V1 reproducible build patch.
from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"Expected source block not found in {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, count))
    print(f"patched {path}")


def replace_if_needed(path, old, new, label):
    p = Path(path)
    text = p.read_text()
    if new in text:
        print(f"{label} already present")
        return
    if old not in text:
        raise SystemExit(f"Expected source block not found for {label}")
    p.write_text(text.replace(old, new, 1))
    print(f"patched {label}")

# Independently installable complete VLC TV test build.
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
replace("application/vlc-android/res/xml/preferences.xml", 'android:defaultValue="-1"\n                android:entries="@array/hardware_acceleration_list"', 'android:defaultValue="2"\n                android:entries="@array/hardware_acceleration_list"')
replace("application/vlc-android/res/xml/preferences_adv.xml", 'android:defaultValue="0"\n            android:key="network_caching"', 'android:defaultValue="5000"\n            android:key="network_caching"')
replace("application/vlc-android/res/xml/preferences_adv.xml", 'android:defaultValue="true"\n            android:key="prefer_smbv1"', 'android:defaultValue="false"\n            android:key="prefer_smbv1"')
replace("application/resources/src/main/java/org/videolan/resources/VLCOptions.kt", 'pref.getInt(KEY_NETWORK_CACHING_VALUE, 0).coerceIn(0, 60000)', 'pref.getInt(KEY_NETWORK_CACHING_VALUE, 5000).coerceIn(0, 60000)')
replace("application/resources/src/main/java/org/videolan/resources/VLCOptions.kt", 'pref.getBoolean(KEY_PREFER_SMBV1, true)', 'pref.getBoolean(KEY_PREFER_SMBV1, false)')
replace("application/resources/src/main/java/org/videolan/resources/VLCOptions.kt", 'prefs.getString(KEY_HARDWARE_ACCELERATION, "$HW_ACCELERATION_AUTOMATIC")', 'prefs.getString(KEY_HARDWARE_ACCELERATION, "$HW_ACCELERATION_FULL")')

# Persist normal TV video position on pause/exit.
replace("application/vlc-android/src/org/videolan/vlc/media/PlaylistManager.kt", '            if (getCurrentMedia()?.isPodcast == true || playAsAudio) saveMediaMeta()', '            if (getCurrentMedia()?.type == MediaWrapper.TYPE_VIDEO || getCurrentMedia()?.isPodcast == true || playAsAudio) saveMediaMeta()')

# Refresh folder after returning from playback. Combined with the DiffUtil fix below this
# makes freshly persisted progress visible without leaving/re-entering the folder.
replace_if_needed(
    "application/television/src/main/java/org/videolan/television/ui/browser/FileBrowserTvFragment.kt",
    '''        if (currentItem == null) (viewModel.provider as BrowserProvider).browseRoot()
        else if (restarted) refresh()''',
    '''        if (currentItem == null) (viewModel.provider as BrowserProvider).browseRoot()
        else refresh()''',
    "TV folder refresh"
)

# The progress bar is rendered from displayTime, but upstream DiffUtil compared only title and
# description. Include every playback field that changes the rendered progress/seen state.
replace_if_needed(
    "application/television/src/main/java/org/videolan/television/ui/FileTvItemAdapter.kt",
    '''        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].description == newList[newItemPosition].description
                    && oldList[oldItemPosition].title == newList[newItemPosition].title
        }''',
    '''        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return oldItem.description == newItem.description
                    && oldItem.title == newItem.title
                    && oldItem.time == newItem.time
                    && oldItem.displayTime == newItem.displayTime
                    && oldItem.length == newItem.length
                    && oldItem.seen == newItem.seen
        }''',
    "TV progress DiffUtil"
)

# SMB "go to containing folder": preserve the original encoded MRL exactly.
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

# Reuse the tested full-backup implementation from mobile and adapt it to the TV preference base.
mobile_path = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt")
tv_path = Path("application/television/src/main/java/org/videolan/television/ui/preferences/PreferencesAdvanced.kt")
mobile = mobile_path.read_text()
tv = tv_path.read_text()
start = mobile.index("    private fun fullBackupFile()")
end = mobile.index("    override fun onSharedPreferenceChanged", start)
helpers = mobile[start:end]
helpers = helpers.replace("requireActivity()", "activity")
helpers = helpers.replace("requireContext()", "activity")
helpers = helpers.replace("lifecycleScope.launch", "launch")
helpers = helpers.replace("if (getWritePermission(Uri.fromFile(dst)))", "if ((activity as FragmentActivity).getWritePermission(Uri.fromFile(dst)))")
helpers = helpers.replace("if (!getWritePermission(Uri.fromFile(dst)))", "if (!(activity as FragmentActivity).getWritePermission(Uri.fromFile(dst)))")
helpers = helpers.replace("activity.share(dst)", "(activity as FragmentActivity).share(dst)")
helpers = helpers.replace(
    '''            if (success)
                UiTools.snackerConfirm(activity, getString(R.string.full_backup_success), confirmMessage = R.string.share, overAudioPlayer = false) {
                    (activity as FragmentActivity).share(dst)
                }
            else''',
    '''            if (success)
                Toast.makeText(activity, getString(R.string.full_backup_success), Toast.LENGTH_LONG).show()
            else'''
)

for imp in [
    "import java.io.BufferedInputStream\n", "import java.io.BufferedOutputStream\n",
    "import java.io.FileInputStream\n", "import java.io.FileOutputStream\n",
    "import java.util.zip.ZipEntry\n", "import java.util.zip.ZipInputStream\n",
    "import java.util.zip.ZipOutputStream\n",
]:
    if imp not in tv:
        tv = tv.replace("import java.io.File\n", "import java.io.File\n" + imp)

anchor = '''            "restore_settings" -> {
                val filePickerIntent = Intent(activity, FilePickerActivity::class.java)
                filePickerIntent.putExtra(KEY_PICKER_TYPE, PickerType.SETTINGS.ordinal)
                startActivityForResult(filePickerIntent, FILE_PICKER_RESULT_CODE)
                return true
            }
'''
if anchor not in tv:
    raise SystemExit("TV restore_settings click handler anchor not found")
tv = tv.replace(anchor, anchor + '''            "full_backup" -> {
                createFullBackup()
                return true
            }
            "full_restore" -> {
                showFullRestoreSelection()
                return true
            }
''', 1)

shared_anchor = "    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {"
if shared_anchor not in tv:
    raise SystemExit("TV onSharedPreferenceChanged anchor not found")
tv = tv.replace(shared_anchor, helpers + shared_anchor, 1)
tv_path.write_text(tv)
print("patched TV full backup/restore handlers")

print("TV V1 patch applied successfully")
