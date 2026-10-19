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
dual = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt").read_text()
tv = Path("application/television/src/main/java/org/videolan/television/ui/preferences/PreferencesAdvanced.kt").read_text()
dual_uses_transaction = "FullBackupRestoreTransaction.replace" in dual
tv_uses_transaction = "FullBackupRestoreTransaction.replace" in tv

checks = {
    "restore transaction invokes state rollback": "rollbackAfterInstall" in transaction,
    "legacy trailing callback still maps to afterInstall": transaction_signature.index("rollbackAfterInstall") < transaction_signature.index("afterInstall"),
    "staging operation is recorded before copy": transaction.find("operations += operation") < transaction.find("copy(source, operation.staged)"),
    "preference and equalizer snapshots are implemented": "FullBackupAppStateSnapshot" in state_text and "EqualizerUtil.getEqualizerExport" in state_text,
    "status keys are excluded from preference import/export": all(key in settings for key in (
        "vlc_last_full_backup_success", "vlc_last_auto_full_backup_success", "vlc_last_auto_full_backup_error"
    )) and "filter { it.key !in Settings.getRestoreBlacklist() }" in parser,
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
