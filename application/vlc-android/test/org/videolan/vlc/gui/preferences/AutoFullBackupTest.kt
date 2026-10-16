package org.videolan.vlc.gui.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun aClockSetBackwardsDoesNotTriggerRepeatedBackups() {
        assertFalse(fullBackupIsDue(true, 50000L, 49999L))
    }
}
