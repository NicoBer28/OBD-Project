package com.example.obd_app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Aviso de que un auto dejó de sincronizar porque su token de dispositivo ya no sirve.
 * Abrir la app alcanza: la reconciliación de Flutter crea un token nuevo y sube lo pendiente.
 *
 * Un ID fijo por auto, para que avisar varias veces no acumule notificaciones.
 */
object SyncAlerts {
    private const val CHANNEL_ID = "OBD_SYNC_ALERTS"

    fun mostrarReactivacion(context: Context, carId: String, carName: String?) {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("OBD-SYNC", "Sin permiso de notificaciones: no se avisa que el auto $carId dejó de sincronizar.")
            return
        }

        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sincronización", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val abrirApp = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notificacion = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("OBD")
            .setContentText("Abrí la app para reactivar la sincronización de ${carName ?: "tu auto"}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(abrirApp)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(idDe(carId), notificacion)
        } catch (e: SecurityException) {
            Log.w("OBD-SYNC", "No se pudo mostrar el aviso de reactivación: ${e.message}")
        }
    }

    fun cancelar(context: Context, carId: String) {
        NotificationManagerCompat.from(context.applicationContext).cancel(idDe(carId))
    }

    private fun idDe(carId: String) = carId.hashCode()
}
