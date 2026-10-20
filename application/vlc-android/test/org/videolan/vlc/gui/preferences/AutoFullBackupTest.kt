package org.videolan.vlc.gui.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.videolan.tools.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

class AutoFullBackupTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun disabledNeverSchedulesBackup() {
        assertFalse(fullBackupIsDue(false, 0L, 0L))
        assertFalse(fullBackupIsDue(false, 1L, day + 1L))
    }

    @Test fun enabledWithoutPreviousBackupIsDue() {
        assertTrue(fullBackupIsDue(true, 0L, 1L))
    }

    @Test fun manualAndAutomaticSuccessUseTheSameDailyInterval() {
        val lastSuccessfulBackup = 500L
        assertFalse(fullBackupIsDue(true, lastSuccessfulBackup, lastSuccessfulBackup + day - 1L))
        assertTrue(fullBackupIsDue(true, lastSuccessfulBackup, lastSuccessfulBackup + day))
    }

    @Test fun onDemandBackupBypassesDisabledToggleAndDailyInterval() {
        assertTrue(shouldRunFullBackup(true, false))
        assertTrue(shouldRunFullBackup(true, true))
        assertFalse(shouldRunFullBackup(false, false))
        assertTrue(shouldRunFullBackup(false, true))
    }

    @Test fun enabledScheduledBackupRetriesWhenPeriodicRunIsEarly() {
        assertTrue(shouldRetryScheduledFullBackup(true, false))
        assertFalse(shouldRetryScheduledFullBackup(true, true))
        assertFalse(shouldRetryScheduledFullBackup(false, false))
    }

    @Test fun aClockSetBackwardsDoesNotTriggerRepeatedBackups() {
        assertFalse(fullBackupIsDue(true, 50000L, 49999L))
    }

    @Test fun forcedRequestIsManualAndScheduledRequestIsAutomatic() {
        assertFalse(isAutomaticBackupRequest(forced = true))
        assertTrue(isAutomaticBackupRequest(forced = false))
    }

    @Test fun internalBackupWorkerStateIsExcludedFromSettingsRestore() {
        val restoreBlacklist = Settings.getRestoreBlacklist()
        assertTrue("vlc_last_full_backup_success" in restoreBlacklist)
        assertTrue("vlc_last_auto_full_backup_success" in restoreBlacklist)
        assertTrue("vlc_last_auto_full_backup_error" in restoreBlacklist)
        assertTrue(PlaybackStateBackup.RESTORE_PENDING in restoreBlacklist)
    }

    @Test fun sharedFullBackupPreferencesAreVisibleForAllSupportedPackages() {
        assertTrue(sharedBackupActionsVisible("org.videolan.vlc.tvfix"))
        assertTrue(sharedBackupActionsVisible("org.videolan.vlc.internalstorage"))
        assertTrue(sharedBackupActionsVisible("org.videolan.vlc.smb"))
        assertFalse(sharedBackupActionsVisible("org.videolan.vlc.unsupported"))
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(manifest = Config.NONE, sdk = [28])
    class BackupSuccessStateTest {
        @Test fun manualSuccessDoesNotChangeAutomaticBackupTimestamp() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = Settings.getInstance(context)
            prefs.edit().clear()
                .putLong("vlc_last_auto_full_backup_success", 1234L)
                .commit()

            AutoFullBackup.recordSuccess(context, automatic = false)

            assertEquals(1234L, prefs.getLong("vlc_last_auto_full_backup_success", 0L))
            assertTrue(prefs.getLong("vlc_last_full_backup_success", 0L) > 0L)
        }

        @Test fun automaticSuccessUpdatesAutomaticBackupTimestamp() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = Settings.getInstance(context)
            prefs.edit().clear()
                .putLong("vlc_last_auto_full_backup_success", 1234L)
                .commit()

            AutoFullBackup.recordSuccess(context, automatic = true)

            assertTrue(prefs.getLong("vlc_last_auto_full_backup_success", 0L) > 1234L)
        }
    }
}
