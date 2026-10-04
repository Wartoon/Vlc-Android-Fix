from pathlib import Path

TARGET = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt")

text = TARGET.read_text()


def remove_between(text: str, start_marker: str, end_marker: str, label: str) -> str:
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    if start < 0 or end < 0:
        raise SystemExit(f"{label}: markers not found")
    return text[:start] + text[end:]


# The complete backup system supersedes the old standalone media/app database
# dump/restore actions. Keep optional_features and all unrelated settings.
text = remove_between(
    text,
    '            "dump_media_db" -> {',
    '            "optional_features" -> {',
    "standalone database handlers",
)

# Standalone settings export/import is also superseded by the settings component
# inside the complete backup. Keep quick-play and unrelated preference handlers.
text = remove_between(
    text,
    '            "export_settings" -> {',
    '            KEY_QUICK_PLAY -> {',
    "legacy settings export handler",
)
text = remove_between(
    text,
    '            "restore_settings" -> {',
    '        }\n        return super.onPreferenceTreeClick(preference)',
    "legacy settings restore handler",
)

# After portable-dual-backup.py has installed the dedicated complete-backup SAF
# picker, the old SETTINGS FilePicker result branch is dead code. Retain only the
# complete-backup picker result.
activity_start = text.find('    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {')
activity_end = text.find('    private fun dualVariantForPackage(', activity_start)
if activity_start < 0 or activity_end < 0:
    raise SystemExit("legacy settings picker result: markers not found")
minimal_activity_result = '''    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null) return
        if (requestCode == 4242) {
            data.data?.let { showFullRestoreSelection(it) }
        }
    }

'''
text = text[:activity_start] + minimal_activity_result + text[activity_end:]

# The standalone restore functions are no longer reachable or user-visible.
# Safe replacement helpers immediately preceding them remain because the complete
# backup restore uses those helpers transactionally.
text = remove_between(
    text,
    '    private fun restoreMediaDatabase() {',
    '    override fun onSharedPreferenceChanged(',
    "standalone database restore functions",
)

for forbidden in (
    '"dump_media_db" ->',
    '"restore_media_db" ->',
    '"restore_app_db" ->',
    '"dump_app_db" ->',
    '"export_settings" ->',
    '"restore_settings" ->',
    'private fun restoreMediaDatabase()',
    'private fun restoreAppDatabase()',
    'FILE_PICKER_RESULT_CODE) {',
):
    if forbidden in text:
        raise SystemExit(f"legacy backup/restore remnant remains: {forbidden}")

TARGET.write_text(text)
print(f"removed superseded backup/restore handlers from {TARGET}")
