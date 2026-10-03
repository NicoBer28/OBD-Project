package com.example.obd_app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * El token de dispositivo de un auto: con él el SyncWorker sube los datos de ese auto sin
 * la app abierta. Lo crea Flutter (con el JWT del usuario) y lo entrega por el canal.
 *
 * [invalid] = el servidor lo rechazó (revocado, vencido o el usuario perdió acceso al auto).
 * Los datos siguen en cola hasta que Flutter, al abrirse, cree uno nuevo.
 */
data class DeviceCredential(
    val carId: String,
    val carName: String?,
    val tokenId: String,
    val token: String,
    val expiresAtMillis: Long?, // idleExpiresAt del alta: el servidor lo corre con cada uso
    val invalid: Boolean = false,
)

/**
 * Credenciales por auto, cifradas con una clave AES-256 del Android Keystore. La clave no
 * pide autenticación del usuario, para que el worker pueda leerlas con el teléfono bloqueado.
 *
 * Si no se pueden descifrar (clave perdida, backup restaurado en otro equipo) se borran:
 * la reconciliación de Flutter las vuelve a crear.
 */
object CredentialStore {
    private const val TAG = "OBD-SYNC"
    private const val KEY_ALIAS = "obd_device_credentials"
    private const val PREFS = "obd_device_credentials"
    private const val KEY_DATA = "data"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Synchronized
    fun get(context: Context, carId: String): DeviceCredential? = leer(context)[carId]

    @Synchronized
    fun getAll(context: Context): Map<String, DeviceCredential> = leer(context)

    @Synchronized
    fun put(context: Context, credential: DeviceCredential) {
        escribir(context, leer(context) + (credential.carId to credential))
    }

    @Synchronized
    fun markInvalid(context: Context, carId: String) {
        val todas = leer(context)
        val cred = todas[carId] ?: return
        if (cred.invalid) return
        escribir(context, todas + (carId to cred.copy(invalid = true)))
    }

    @Synchronized
    fun remove(context: Context, carId: String) {
        val todas = leer(context)
        if (carId !in todas) return
        escribir(context, todas - carId)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_DATA).apply()
    }

    // ---------------------------------------------------------------- PERSISTENCIA

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun leer(context: Context): Map<String, DeviceCredential> {
        val guardado = prefs(context).getString(KEY_DATA, null) ?: return emptyMap()
        return try {
            val bytes = Base64.decode(guardado, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            val json = String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            desdeJson(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudieron descifrar las credenciales (${e.javaClass.simpleName}). Se borran.")
            clear(context)
            emptyMap()
        }
    }

    private fun escribir(context: Context, credenciales: Map<String, DeviceCredential>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, clave()) // el Keystore genera un IV aleatorio de 12 bytes
        val cifrado = cipher.doFinal(aJson(credenciales).toString().toByteArray(Charsets.UTF_8))
        val datos = cipher.iv + cifrado
        prefs(context).edit().putString(KEY_DATA, Base64.encodeToString(datos, Base64.NO_WRAP)).apply()
    }

    private fun clave(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun aJson(credenciales: Map<String, DeviceCredential>) = JSONObject().apply {
        for ((carId, c) in credenciales) {
            put(carId, JSONObject().apply {
                put("carId", c.carId)
                put("carName", c.carName ?: JSONObject.NULL)
                put("tokenId", c.tokenId)
                put("token", c.token)
                put("expiresAtMillis", c.expiresAtMillis ?: JSONObject.NULL)
                put("invalid", c.invalid)
            })
        }
    }

    private fun desdeJson(json: JSONObject): Map<String, DeviceCredential> {
        val resultado = HashMap<String, DeviceCredential>()
        for (carId in json.keys()) {
            val c = json.getJSONObject(carId)
            resultado[carId] = DeviceCredential(
                carId = c.getString("carId"),
                carName = if (c.isNull("carName")) null else c.getString("carName"),
                tokenId = c.getString("tokenId"),
                token = c.getString("token"),
                expiresAtMillis = if (c.isNull("expiresAtMillis")) null else c.getLong("expiresAtMillis"),
                invalid = c.optBoolean("invalid", false),
            )
        }
        return resultado
    }
}
