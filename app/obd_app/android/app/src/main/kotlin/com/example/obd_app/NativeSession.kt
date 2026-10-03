package com.example.obd_app

import android.content.Context

/**
 * Lo que Flutter le entrega al código nativo para poder subir datos sin la app abierta.
 * Solo datos no secretos: el token de cada auto vive cifrado en [CredentialStore].
 *
 * El auto ya no viaja acá: sale de la MAC del ESP32 (tabla `associations`). [userId] sirve
 * para decidir si se adopta un viaje que el servidor ya tenía abierto (ver SyncWorker).
 */
data class NativeSession(
    val baseUrl: String,
    val userId: String?,
) {
    companion object {
        private const val PREFS = "obd_native_session"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_USER_ID = "userId"

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Null si Flutter todavía no entregó nada (nunca se abrió la app tras instalarla). */
        fun read(context: Context): NativeSession? {
            val p = prefs(context)
            val baseUrl = p.getString(KEY_BASE_URL, null) ?: return null
            return NativeSession(
                baseUrl = baseUrl,
                userId = p.getString(KEY_USER_ID, null),
            )
        }

        fun saveConfig(context: Context, baseUrl: String, userId: String?) {
            prefs(context).edit()
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_USER_ID, userId)
                .apply()
        }

        /** Logout: se olvida el usuario pero se conserva la URL del servidor. */
        fun clearCredentials(context: Context) {
            prefs(context).edit()
                .remove(KEY_USER_ID)
                .apply()
        }
    }
}
