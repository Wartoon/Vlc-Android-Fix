#!/usr/bin/env python3
"""Regression guards for the shared TV and Dual full-backup restore paths."""
from pathlib import Path

transaction = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/FullBackupRestoreTransaction.kt").read_text()
transaction_signature = transaction.split("suspend fun replace(", 1)[1].split(") {", 1)[0]
state = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/FullBackupAppStateSnapshot.kt")
state_text = state.read_text() if state.exists() else ""
settings = Path("application/tools/src/main/java/org/videolan/tools/Settings.kt").read_text()
parser = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/search/PreferenceParser.kt").read_text()
worker = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/AutoFullBackup.kt").read_text()
engine = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/FullBackupEngine.kt").read_text()
protection = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/FullBackupProtection.kt").read_text()
restore_validation = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/RestoreDatabaseValidation.kt").read_text()
protection_tests = Path("application/vlc-android/test/org/videolan/vlc/gui/preferences/FullBackupProtectionTest.kt").read_text()
auto_tests = Path("application/vlc-android/test/org/videolan/vlc/gui/preferences/AutoFullBackupTest.kt").read_text()
dual = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt").read_text()
tv = Path("application/television/src/main/java/org/videolan/television/ui/preferences/PreferencesAdvanced.kt").read_text()
dual_uses_transaction = "FullBackupRestoreTransaction.replace" in dual
tv_uses_transaction = "FullBackupRestoreTransaction.replace" in tv

checks = {
    "restore transaction invokes state rollback": "rollbackAfterInstall" in transaction,
    "legacy trailing callback still maps to afterInstall": transaction_signature.index("rollbackAfterInstall") < transaction_signature.index("afterInstall"),
    "staging operation is recorded before copy": transaction.find("operations += operation") < transaction.find("copy(source, operation.staged)"),
    "unreadable restore directories fail closed": "source.listFiles()" in transaction and "Cannot list restore source" in transaction,
    "preference and equalizer snapshots are implemented": "FullBackupAppStateSnapshot" in state_text and "EqualizerUtil.getEqualizerExport" in state_text,
    "TV module can access its restore snapshot": not tv_uses_transaction or "internal class FullBackupAppStateSnapshot" not in state_text,
    "status keys are excluded from preference import/export": all(key in settings for key in (
        "vlc_last_full_backup_success", "vlc_last_auto_full_backup_success", "vlc_last_auto_full_backup_error",
        "vlc_full_backup_playback_pending"
    )) and "filter { it.key !in Settings.getRestoreBlacklist() }" in parser,
    "candidate backups include and validate app databases": "validateAppDbBackupEntries(appDbEntries)" in protection and "validateRestoreDatabaseDirectory(appDbDirectory)" in protection and "listBackupChildren(appDbDir)" in engine,
    "Dual Room database names without extensions are accepted and restored": all(name in restore_validation for name in ("vlc_database", "moviepedia_database")) and "isRestoreDatabaseFileName(fileName)" in protection and "isRestoreDatabaseFileName(it.name)" in restore_validation and "backupAcceptsDualRoomDatabaseNamesWithoutExtensions" in protection_tests and "restoreRecognizesDualRoomDatabaseNamesWithoutExtensions" in protection_tests,
    "TV and Dual shared settings expose validated backup actions": 'packageName in setOf(' in engine and all(package in engine for package in ('"org.videolan.vlc.tvfix"', '"org.videolan.vlc.internalstorage"', '"org.videolan.vlc.smb"')) and "sharedFullBackupPreferencesAreVisibleForAllSupportedPackages" in auto_tests,
    "TV manual backup uses the shared worker and confirms reduced libraries": "AutoFullBackup.requestNow(app)" in tv and "acceptReducedLibrary = true" in tv and "pendingFullBackupReduction(app, dst)" in tv,
    "manual reduction checks include the live SQLite WAL": "copyBackupDatabaseWithWal(mediaDb, stagedDatabase)" in protection and 'source.name + "-wal"' in protection and "currentDatabaseValidationCopiesCommittedWalAlongsideMainDatabase" in protection_tests,
    "Dual manifest only declares optional components with files": all(f"if (has{component})" in engine for component in ("Artwork", "Subtitles", "Credentials")),
    "Dual restore snapshots and defers auto-backup": not dual_uses_transaction or (
        "FullBackupAppStateSnapshot.capture(context)" in dual and "rollbackAfterInstall" in dual and "AutoFullBackup.deferAfterRestore(context)" in dual
    ),
    "TV restore snapshots and defers auto-backup": not tv_uses_transaction or (
        "FullBackupAppStateSnapshot.capture(context)" in tv and "rollbackAfterInstall" in tv and "AutoFullBackup.deferAfterRestore(context)" in tv
    ),
    "manual requests are not recorded or announced as automatic": "isAutomaticBackupRequest(forced)" in worker and "if (forced)" in worker,
}

failed = [name for name, passed in checks.items() if not passed]
if failed:
    raise SystemExit("FAIL: " + "; ".join(failed))
print(f"PASS: {len(checks)} full-backup and restore regression guards")
