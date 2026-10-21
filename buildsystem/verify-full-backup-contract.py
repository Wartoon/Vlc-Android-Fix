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
storage = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/FullBackupStorage.kt").read_text()
protection = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/FullBackupProtection.kt").read_text()
restore_validation = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/RestoreDatabaseValidation.kt").read_text()
bundled_database_reader = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/BundledBackupDatabaseReader.kt").read_text()
protection_tests = Path("application/vlc-android/test/org/videolan/vlc/gui/preferences/FullBackupProtectionTest.kt").read_text()
auto_tests = Path("application/vlc-android/test/org/videolan/vlc/gui/preferences/AutoFullBackupTest.kt").read_text()
dual = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt").read_text()
shared_tv = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt").read_text()
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
    "TV phone-style restore validates databases and installs through rollback transaction": "if (restoreMediaDb) validateRestoreDatabase(mediaDb)" in shared_tv and "if (restoreAppDb) validateRestoreDatabaseDirectory(savedAppDb)" in shared_tv and "FullBackupRestoreTransaction.replace(" in shared_tv and "FullBackupAppStateSnapshot.capture(context)" in shared_tv and "rollbackAfterInstall" in shared_tv,
    "all TV restore routes read manifests with a fixed size bound": "MAX_FULL_BACKUP_MANIFEST_BYTES" in restore_validation and "readFullBackupManifest(manifest)" in shared_tv and "readFullBackupManifest(manifest)" in tv and "manifestReadRejectsContentOver64KiB" in protection_tests,
    "restore database validation uses bundled SQLite on supported Android versions": "Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP" in restore_validation and 'Class.forName("org.videolan.vlc.gui.preferences.BundledBackupDatabaseReader")' in restore_validation and "fun validate(file: File)" in bundled_database_reader and 'db.prepare("PRAGMA quick_check")' in bundled_database_reader,
    "standalone TV database restores validate media databases and bound app database ZIP extraction": all(token in shared_tv and token in tv for token in ("validateRestoreDatabase(src)", "extractAppDatabaseBackup(src, stagedAppDb)")) and "validateRestoreDatabaseDirectory(destination)" in restore_validation and "MAX_STANDALONE_DATABASE_BACKUP_BYTES" in restore_validation,
    "standalone TV restores preserve interrupted restore rollback files": "if (previous.exists()) throw IOException(\"Previous restore backup still exists\")" in shared_tv and "if (previous.exists()) throw IOException(\"Previous restore backup still exists\")" in tv and "if (restoreBackups.any { it.exists() }) throw IOException(\"Previous restore backup still exists\")" in shared_tv and "if (restoreBackups.any { it.exists() }) throw IOException(\"Previous restore backup still exists\")" in tv,
    "TV manual backup uses the shared worker and confirms reduced libraries": "AutoFullBackup.requestNow(app)" in tv and "acceptReducedLibrary = true" in tv and "pendingFullBackupReduction(app, dst)" in tv,
    "manual reduction checks use a consistent database snapshot with WAL rows": "createConsistentSqliteSnapshot(mediaDb, stagedDatabase)" in protection and "backupIncludesCommittedWalRowsInOneValidatedFile" in Path("application/app/src/androidTest/java/org/videolan/vlc/gui/preferences/BundledBackupDatabaseReaderTest.kt").read_text(),
    "Dual manifest only declares optional components with files": all(f"if (has{component})" in engine for component in ("Artwork", "Subtitles", "Credentials")),
    "Dual restore snapshots and defers auto-backup": not dual_uses_transaction or (
        "FullBackupAppStateSnapshot.capture(context)" in dual and "rollbackAfterInstall" in dual and "AutoFullBackup.deferAfterRestore(context)" in dual
    ),
    "TV restore snapshots and defers auto-backup": not tv_uses_transaction or (
        "FullBackupAppStateSnapshot.capture(context)" in tv and "rollbackAfterInstall" in tv and "AutoFullBackup.deferAfterRestore(context)" in tv
    ),
    "manual requests are not recorded or announced as automatic": "isAutomaticBackupRequest(forced)" in worker and "if (forced)" in worker,
    "duplicate manual requests do not cancel work and worker cancellation propagates": "ExistingWorkPolicy.KEEP, request" in worker and "catch (e: CancellationException)" in worker and "throw e" in worker,
    "manual backup requests with different reduction decisions remain queued separately": "manualBackupWorkName(acceptReducedLibrary)" in worker and "USER_REQUEST_ACCEPT_REDUCED" in worker and "manualRequestsWithDifferentReductionDecisionsDoNotReplaceEachOther" in auto_tests,
    "pre-Android 5 database backups fail clearly without infinite automatic retries": "Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP" in protection and "FullBackupUnsupported" in protection and "catch (e: FullBackupUnsupported)" in worker and "Result.failure()" in worker,
    "full backups exclude transient SQLite shared-memory files before validation and restore": 'it.name.endsWith("-shm")' in engine and "removeSqliteSharedMemoryFiles(staging)" in shared_tv and "removeSqliteSharedMemoryFiles(staging)" in tv and 'name.endsWith("-shm")' in restore_validation,
    "backup and restore UI coroutines preserve cancellation": "catch (e: CancellationException)" in dual and "throw e" in dual and "catch (e: CancellationException)" in tv and "throw e" in tv,
    "standalone TV database backup coroutines preserve cancellation": all(
        "catch (e: CancellationException)" in tv.split(start, 1)[1].split(end, 1)[0]
        for start, end in (("\"dump_media_db\" ->", "\"dump_app_db\" ->"), ("\"dump_app_db\" ->", "\"export_settings\" ->"))
    ),
    "full restore cannot report success when no selected component can be installed": all("restoreTargets.isEmpty() && !restoreSettings" in code for code in (shared_tv, tv)),
    "database snapshots checkpoint WAL under a writer lock and preserve VLC native collation": "createConsistentSqliteSnapshot" in engine and "replaceWithConsistentSqliteBackup(db, dst)" in tv and "writeConsistentAppDatabaseBackup(db, dst)" in tv and '"PRAGMA wal_checkpoint(TRUNCATE)"' in bundled_database_reader and '"BEGIN IMMEDIATE"' in bundled_database_reader and "backupPreservesMediaDatabaseWithCustomFilenameCollation" in Path("application/app/src/androidTest/java/org/videolan/vlc/gui/preferences/BundledBackupDatabaseReaderTest.kt").read_text(),
    "standalone restore refuses unreadable nested source directories": 'source.listFiles() ?: throw IOException("Cannot list restore source' in tv,
    "full backup validation rejects the same unsafe path forms as restore": "validateFullBackupEntryPath(name)" in protection and "backupEntryPathsRejectUnsafeSeparatorsAndEmptySegments" in protection_tests and "name.contains('\\\\')" in restore_validation and "entry.name.contains(92.toChar())" in dual and "entry.name.contains(92.toChar())" in tv,
    "TV module imports its public backup destination helper": "fun tvFullBackupFile(storageRoot: File)" in storage and "import org.videolan.vlc.gui.preferences.tvFullBackupFile" in tv,
    "TV daily-backup toggle schedules immediately and refreshes its summary": "AUTO_FULL_BACKUP_ENABLED ->" in tv and "AutoFullBackup.configure(context)" in tv and "AutoFullBackup.summary(context)" in tv,
    "legacy TV backup migration validates database contents without rotating saved generations": "inspectFullBackupLibrary(staged, parent, requirePlayback = true)" in storage and "verifyAndReplaceFullBackup(staged, destination)" in storage,
}

failed = [name for name, passed in checks.items() if not passed]
if failed:
    raise SystemExit("FAIL: " + "; ".join(failed))
print(f"PASS: {len(checks)} full-backup and restore regression guards")
