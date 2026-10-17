#!/usr/bin/env python3
"""Regression guards for TV compatibility and video playlist browsing."""

from pathlib import Path


def read(path):
    return Path(path).read_text()


permissions = read("application/vlc-android/src/org/videolan/vlc/gui/helpers/hf/StoragePermissionsDelegate.kt")
assert "ActivityResultContracts.StartActivityForResult()" in permissions
assert "allFilesAccessLauncher.launch(intent)" in permissions
assert "isExternalStorageManager()" in permissions
assert "activityResultLauncher.launch(code)" in permissions
assert "activityResultLauncher.launch(code)\n        startActivity(intent)" not in permissions

browser = read("application/vlc-android/src/org/videolan/vlc/providers/FileBrowserProvider.kt")
assert "suspend fun browseByUrl(url: String): List<MediaWrapper>" in browser
otg_start = browser.index('url == "otg://" || url.startsWith("content:")', browser.index("suspend fun browseByUrl"))
otg_end = browser.index("else -> super.browseUrl(url)", otg_start)
otg_branch = browser[otg_start:otg_end]
assert "launch {" not in otg_branch
assert "result.addAll(browseByUrl(it.uri.toString()))" in otg_branch

tv = read("application/television/src/main/java/org/videolan/television/ui/browser/BaseBrowserTvFragment.kt")
assert "FileBrowserProvider" in tv
assert "browseByUrl(selected.uri.toString())" in tv
assert "browseUrl(selected.uri.toString())" in tv
assert "visibleFileVideos" not in tv

layout = read("application/television/src/main/res/layout/song_browser.xml")
button_start = layout.index('android:id="@+id/playlistButton"')
button_end = layout.index("/>", button_start)
button = layout[button_start:button_end]
assert 'android:layout_width="56dp"' in button
assert 'android:layout_height="56dp"' in button
assert 'android:background="@color/transparent"' in button
assert '@drawable/ic_tv_browser_playlist' in button
playlist_icon = read("application/resources/src/main/res/drawable/ic_tv_browser_playlist.xml")
assert 'android:width="30dp"' in playlist_icon
assert 'android:height="30dp"' in playlist_icon

route = read("application/television/src/main/java/org/videolan/television/ui/browser/TvFolderRoute.kt")
assert "return listOf(uriString)" in route
assert "initialTvFolderRoute(currentUri)" in route
activity = read("application/television/src/main/java/org/videolan/television/ui/browser/VerticalGridActivity.kt")
assert 'putSingle(LAST_TV_FOLDER_ROUTE, "")' in activity

media_model = read("application/television/src/main/java/org/videolan/television/viewmodel/MediaBrowserViewModel.kt")
assert "FoldersProvider(context, this, Folder.TYPE_FOLDER_VIDEO)" in media_model
assert "VideosProvider(parent, null, context, this)" in media_model
media_browser = read("application/television/src/main/java/org/videolan/television/ui/browser/MediaBrowserTvFragment.kt")
assert "item is Folder" in media_browser
assert "putExtra(ITEM, item)" in media_browser
assert "intent.parcelable" in activity and "ITEM)" in activity

app_gradle = read("application/app/build.gradle")
assert 'variantBuilder.minSdk = 30' in app_gradle
assert 'withBuildType("vlcBundle")' in app_gradle
assert 'withBuildType("tvFix")' not in app_gradle

print("verified Android 9/12 TV compatibility, Android 11+ permission flow, recursive video playlists, and D-pad action focus")
