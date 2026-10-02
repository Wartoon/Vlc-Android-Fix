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

# Full backup/restore was originally implemented only in the phone PreferencesAdvanced fragment.
# The TV screen has its own BasePreferenceFragment and CoroutineScope. Reuse the tested archive
# implementation, but adapt Fragment-only APIs to the TV fragment's activity/scope APIs.
mobile_path = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt")
tv_path = Path("application/television/src/main/java/org/videolan/television/ui/preferences/PreferencesAdvanced.kt")
mobile = mobile_path.read_text()
tv = tv_path.read_text()

start = mobile.index("    private fun fullBackupFile()")
end = mobile.index("    override fun onSharedPreferenceChanged", start)
backup_helpers = mobile[start:end]

# TV BasePreferenceFragment is not an AndroidX Fragment: requireActivity(), requireContext() and
# Fragment.lifecycleScope are unavailable. The TV implementation already exposes a non-null
# activity and delegates CoroutineScope by MainScope().
backup_helpers = backup_helpers.replace("requireActivity()", "activity")
backup_helpers = backup_helpers.replace("requireContext()", "activity")
backup_helpers = backup_helpers.replace("lifecycleScope.launch", "launch")
# StoragePermissionsDelegate.getWritePermission and share are FragmentActivity extensions.
backup_helpers = backup_helpers.replace(
    "if (getWritePermission(Uri.fromFile(dst)))",
    "if ((activity as FragmentActivity).getWritePermission(Uri.fromFile(dst)))"
)
backup_helpers = backup_helpers.replace(
    "if (!getWritePermission(Uri.fromFile(dst)))",
    "if (!(activity as FragmentActivity).getWritePermission(Uri.fromFile(dst)))"
)
backup_helpers = backup_helpers.replace(
    "activity.share(dst)",
    "(activity as FragmentActivity).share(dst)"
)
# Material Snackbar requires an AppCompat theme, which the TV preferences activity does not use.
# The archive is already complete at this point, so use a TV-safe Toast instead of snackerConfirm.
backup_helpers = backup_helpers.replace(
    '''            if (success)
                UiTools.snackerConfirm(activity, getString(R.string.full_backup_success), confirmMessage = R.string.share, overAudioPlayer = false) {
                    (activity as FragmentActivity).share(dst)
                }
            else''',
    '''            if (success)
                Toast.makeText(activity, getString(R.string.full_backup_success), Toast.LENGTH_LONG).show()
            else'''
)

# Imports required by the shared ZIP implementation and its success/share action.
for imp in [
    "import org.videolan.vlc.util.share\n",
    "import java.io.BufferedInputStream\n",
    "import java.io.BufferedOutputStream\n",
    "import java.io.FileInputStream\n",
    "import java.io.FileOutputStream\n",
    "import java.util.zip.ZipEntry\n",
    "import java.util.zip.ZipInputStream\n",
    "import java.util.zip.ZipOutputStream\n",
]:
    if imp not in tv:
        if imp.startswith("import org.videolan"):
            tv = tv.replace("import org.videolan.vlc.util.FileUtils\n", "import org.videolan.vlc.util.FileUtils\n" + imp)
        else:
            tv = tv.replace("import java.io.File\n", "import java.io.File\n" + imp)

# Wire the two visible preferences to their actual TV actions.
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

# Add the adapted backup/restore implementation to the TV fragment.
shared_anchor = "    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {"
if shared_anchor not in tv:
    raise SystemExit("TV onSharedPreferenceChanged anchor not found")
tv = tv.replace(shared_anchor, backup_helpers + shared_anchor, 1)
tv_path.write_text(tv)
print("patched TV full backup/restore handlers")

print("TV V1 patch applied successfully")
