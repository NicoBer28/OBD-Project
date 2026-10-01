package com.example.obd_app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Único lugar que encola trabajo de sincronización.
 *
 * - [requestSync]: "hay datos nuevos, subí cuando haya internet". Trabajo ÚNICO: llamarlo
 *   50 veces no crea 50 workers.
 * - [schedulePeriodic]: red de seguridad cada 15 min, por si algún aviso se perdió.
 */
object SyncScheduler {
    private const val UNIQUE_NOW = "obd-sync"
    private const val UNIQUE_PERIODIC = "obd-sync-periodic"

    private val conInternet = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * @param replace true cuando cambió la sesión (llegó un token nuevo): reemplaza un trabajo
     * que pudiera estar esperando un backoff largo, para que suba ya.
     */
    fun requestSync(context: Context, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(conInternet)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_NOW,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(conInternet)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
