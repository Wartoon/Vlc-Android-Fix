package org.videolan.vlc.gui.preferences

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.resources.AppContextProvider
import org.videolan.tools.Settings
import org.videolan.vlc.R
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal const val AUTO_FULL_BACKUP_ENABLED = "vlc_auto_full_backup_enabled"
private const val LAST_FULL_BACKUP_SUCCESS = "vlc_last_full_backup_success"
private const val LAST_AUTO_FULL_BACKUP_SUCCESS = "vlc_last_auto_full_backup_success"
private const val LAST_AUTO_FULL_BACKUP_ERROR = "vlc_last_auto_full_backup_error"
private const val DAY_MILLIS = 24L * 60 * 60 * 1000
internal const val FORCE_FULL_BACKUP_NOW = "vlc_force_full_backup_now"
internal const val ACCEPT_REDUCED_LIBRARY = "vlc_accept_reduced_library"

/** Pure scheduling decision; successful manual backups reset the same 24h clock. */
internal fun fullBackupIsDue(enabled: Boolean, lastSuccess: Long, now: Long): Boolean =
    enabled && (lastSuccess <= 0L || (now >= lastSuccess && now - lastSuccess >= DAY_MILLIS))

/** An explicitly requested backup is independent of the daily toggle and interval. */
internal fun shouldRunFullBackup(force: Boolean, due: Boolean): Boolean = force || due

/**
 * A periodic attempt before the full interval elapses must retry, otherwise
 * WorkManager consumes that day's period without creating a backup.
 */
internal fun shouldRetryScheduledFullBackup(enabled: Boolean, due: Boolean): Boolean = enabled && !due

/**
 * No alarm or wake-up exact-time guarantee. WorkManager schedules a daily
 * opportunity; opening/leaving the app also requests one when overdue.
 * All work uses the same ZIP filename and mutex as a manual backup.
 */
object AutoFullBackup {
    private const val PERIODIC = "vlc_full_backup_daily"
    private const val OPPORTUNITY = "vlc_full_backup_opportunity"
    private const val USER_REQUEST = "vlc_full_backup_now"
    private fun conditions(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    fun isEnabled(context: Context): Boolean =
        Settings.getInstance(context).getBoolean(AUTO_FULL_BACKUP_ENABLED, false)

    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = Settings.getInstance(context)
        return fullBackupIsDue(p.getBoolean(AUTO_FULL_BACKUP_ENABLED, false),
            p.getLong(LAST_FULL_BACKUP_SUCCESS, 0L), now)
    }

    fun configure(context: Context) {
        val app = context.applicationContext
        val manager = WorkManager.getInstance(app)
        if (!isEnabled(app)) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(OPPORTUNITY)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<AutoFullBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(conditions()).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        checkOpportunity(app)
    }

    fun checkOpportunity(context: Context) {
        if (!isDue(context)) return
        val request = OneTimeWorkRequestBuilder<AutoFullBackupWorker>()
            .setConstraints(conditions())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(OPPORTUNITY, ExistingWorkPolicy.KEEP, request)
    }

    /** Same WorkManager worker as the daily backup, but on-demand even if disabled. */
    fun requestNow(context: Context, acceptReducedLibrary: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<AutoFullBackupWorker>()
            .setInputData(workDataOf(FORCE_FULL_BACKUP_NOW to true,
                ACCEPT_REDUCED_LIBRARY to acceptReducedLibrary))
            .setConstraints(conditions())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(USER_REQUEST, ExistingWorkPolicy.REPLACE, request)
    }

    fun deferAfterRestore(context: Context) {
        // A just-restored library must settle before its first automatic backup.
        Settings.getInstance(context).edit {
            putLong(LAST_FULL_BACKUP_SUCCESS, System.currentTimeMillis())
        }
    }

    fun recordSuccess(context: Context, automatic: Boolean) {
        val prefs = Settings.getInstance(context)
        val time = System.currentTimeMillis()
        prefs.edit {
            putLong(LAST_FULL_BACKUP_SUCCESS, time)
            if (automatic) {
                putLong(LAST_AUTO_FULL_BACKUP_SUCCESS, time)
                remove(LAST_AUTO_FULL_BACKUP_ERROR)
            }
        }
    }

    fun automaticBackupSucceeded(context: Context) {
        val activity = AppContextProvider.currentActivity ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, R.string.auto_full_backup_success, Toast.LENGTH_SHORT).show()
        }
    }

    fun automaticBackupFailed(context: Context, error: Exception) {
        Log.e("AutoFullBackup", "Scheduled full backup failed", error)
        Settings.getInstance(context).edit { putBoolean(LAST_AUTO_FULL_BACKUP_ERROR, true) }
    }

    fun onActivityResumed(activity: Activity) {
        val prefs = Settings.getInstance(activity)
        if (prefs.getBoolean(LAST_AUTO_FULL_BACKUP_ERROR, false)) {
            prefs.edit { remove(LAST_AUTO_FULL_BACKUP_ERROR) }
            Toast.makeText(activity, R.string.auto_full_backup_failure, Toast.LENGTH_LONG).show()
        }
        checkOpportunity(activity)
    }

    fun summary(context: Context): String {
        val last = Settings.getInstance(context).getLong(LAST_AUTO_FULL_BACKUP_SUCCESS, 0L)
        if (last <= 0L) return context.getString(R.string.auto_full_backup_waiting)
        val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
        return context.getString(R.string.auto_full_backup_last_success, formatted)
    }
}

class AutoFullBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val forced = inputData.getBoolean(FORCE_FULL_BACKUP_NOW, false)
        val trigger = when {
            forced -> "manual-request"
            else -> "scheduled"
        }
        Log.i("AutoFullBackup", "Backup worker started (trigger=$trigger, workId=${id})")
        val enabled = AutoFullBackup.isEnabled(app)
        val due = AutoFullBackup.isDue(app)
        if (!shouldRunFullBackup(forced, due)) {
            if (shouldRetryScheduledFullBackup(enabled, due)) {
                Log.i("AutoFullBackup", "Scheduled backup checked before 24h interval elapsed; retrying (workId=${id})")
                return Result.retry()
            }
            return Result.success()
        }
        return try {
            FullBackupEngine.create(app, automatic = true, force = forced,
                allowReduction = forced && inputData.getBoolean(ACCEPT_REDUCED_LIBRARY, false))
            AutoFullBackup.automaticBackupSucceeded(app)
            Result.success()
        } catch (e: FullBackupLibraryReduced) {
            AutoFullBackup.automaticBackupFailed(app, e)
            Result.failure()
        } catch (_: FullBackupDeferred) {
            // Playback, scanning, a concurrent manual backup, or a recent
            // successful backup are not failures. WorkManager retries later.
            if (shouldRunFullBackup(forced, AutoFullBackup.isDue(app))) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            // WorkManager cancellation is not a backup failure; preserve cancellation semantics.
            throw e
        } catch (e: Exception) {
            AutoFullBackup.automaticBackupFailed(app, e)
            Result.retry()
        }
    }
}
