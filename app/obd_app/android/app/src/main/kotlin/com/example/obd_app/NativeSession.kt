package com.example.obd_app

import android.content.Context

/**
 * Lo que Flutter le entrega al código nativo para poder subir datos sin la app abierta.
 *
 * TEMPORAL (Fase 1): guarda el access token de la sesión de Flutter, que dura 15 minutos.
 * El nativo NUNCA lo refresca: el refresh token es de un solo uso y solo lo maneja Dart.
 * Si el token vence, el SyncWorker deja todo en la cola local hasta que Flutter mande
 * uno nuevo (cada vez que la app se abre o renueva la sesión).
 *
 * En la Fase 2 el access token se reemplaza por el token de dispositivo del backend.
 */
data class NativeSession(
    val baseUrl: String,
    val accessToken: String?,
    val carId: String?,
    val userId: String?,
) {
    companion object {
        private const val PREFS = "obd_native_session"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_ACCESS_TOKEN = "accessToken"
        private const val KEY_CAR_ID = "carId"
        private const val KEY_USER_ID = "userId"

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Null si Flutter todavía no entregó nada (nunca se abrió la app tras instalarla). */
        fun read(context: Context): NativeSession? {
            val p = prefs(context)
            val baseUrl = p.getString(KEY_BASE_URL, null) ?: return null
            return NativeSession(
                baseUrl = baseUrl,
                accessToken = p.getString(KEY_ACCESS_TOKEN, null),
                carId = p.getString(KEY_CAR_ID, null),
                userId = p.getString(KEY_USER_ID, null),
            )
        }

        fun save(
            context: Context,
            baseUrl: String,
            accessToken: String?,
            carId: String?,
            userId: String?,
        ) {
            prefs(context).edit()
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_CAR_ID, carId)
                .putString(KEY_USER_ID, userId)
                .apply()
        }

        /** Logout: se olvidan las credenciales pero se conserva la URL del servidor. */
        fun clearCredentials(context: Context) {
            prefs(context).edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_CAR_ID)
                .remove(KEY_USER_ID)
                .apply()
        }
    }
}
