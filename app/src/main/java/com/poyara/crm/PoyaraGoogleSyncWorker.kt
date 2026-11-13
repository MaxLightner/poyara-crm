package com.poyara.crm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Optional account-scoped local Google Contacts bridge.
 * Android/Google's Contacts sync adapter owns actual cloud synchronization;
 * this worker mirrors only tagged POYARA records on the device.
 */
internal class PoyaraGoogleSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            GoogleContactsBridge.PREFS, Context.MODE_PRIVATE)
        val account = prefs.getString("account", "").orEmpty()
        if (account.isBlank()) return Result.success()
        return try {
            val db = CrmDb(applicationContext)
            try { GoogleContactsBridge(applicationContext, db).sync(account) }
            finally { db.close() }
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PoyaraGoogleSyncWorker>(
                15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "poyara_google_contacts_sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
