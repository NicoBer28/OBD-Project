package com.example.obd_app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.example.obd_app/ble_channel"
    private val COMPANION_REQUEST_CODE = 1001

    // UUID del servicio de tu ESP32
    private val OBD_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    // Room no se puede usar en el hilo principal. Las respuestas a Flutter sí van por él.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Vinculación en curso: el selector del sistema responde más tarde, en onActivityResult.
    @Volatile private var pendingCarId: String? = null
    private var pendingResult: MethodChannel.Result? = null

    private val deviceManager: CompanionDeviceManager
        get() = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

    private val dao: ObdDao
        get() = AppDatabase.getDatabase(this).obdDao()

    companion object {
        // Vincular y reconciliar tocan las mismas asociaciones: que no se pisen.
        private val asociacionesMutex = Mutex()
    }

    //se ejecuta cuando queres vincular un auto con su ESP32, abre la ventanita de Android para que el usuario seleccione el dispositivo
    private fun iniciarVinculacionOBD(carId: String, result: MethodChannel.Result) {
        completarVinculacion { it.error("REEMPLAZADO", "Se inició otra vinculación", null) }
        pendingCarId = carId
        pendingResult = result

        // Filtramos para que solo muestre dispositivos que tengan el uuid que declaramos
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(OBD_SERVICE_UUID))
                    .build()
            )
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        try {
            deviceManager.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    try {
                        startIntentSenderForResult(intentSender, COMPANION_REQUEST_CODE, null, 0, 0, 0)
                    } catch (e: Exception) {
                        Log.e("OBD-C", "No se pudo abrir el selector de dispositivos: ${e.message}")
                        completarVinculacion { it.error("ERROR", e.message, null) }
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e("OBD-C", "Error al buscar el dispositivo: $error")
                    completarVinculacion { it.error("ERROR", error?.toString(), null) }
                }
            })
        } catch (e: Exception) {
            Log.e("OBD-C", "No se pudo iniciar la vinculación: ${e.message}")
            completarVinculacion { it.error("ERROR", e.message, null) }
        }
    }

    // Responde la vinculación pendiente (si hay) y limpia el estado. Siempre en el hilo principal.
    private fun completarVinculacion(responder: (MethodChannel.Result) -> Unit) {
        val result = pendingResult
        pendingResult = null
        pendingCarId = null
        if (result == null) return
        try {
            responder(result)
        } catch (e: Exception) {
            // El motor de Flutter pudo haberse recreado mientras el selector estaba abierto.
            Log.w("OBD-C", "No se pudo responder la vinculación a Flutter: ${e.message}")
        }
    }

    // se ejecuta cuando se cerró la ventanita de Android, con o sin dispositivo elegido
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != COMPANION_REQUEST_CODE) return

        if (resultCode != Activity.RESULT_OK) {
            completarVinculacion { it.error("CANCELADO", "El usuario cerró el selector", null) }
            return
        }

        val associationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            data?.getParcelableExtra<AssociationInfo>(CompanionDeviceManager.EXTRA_ASSOCIATION)
        }
        val mac = associationInfo?.deviceMacAddress?.toString()?.uppercase()
        val carId = pendingCarId

        if (associationInfo == null || mac == null) {
            completarVinculacion { it.error("ERROR", "El sistema no devolvió el dispositivo elegido", null) }
            return
        }
        if (carId == null) {
            // La Activity se recreó con el selector abierto y ya no se sabe para qué auto era.
            // La asociación queda huérfana; la reconciliación la quita.
            Log.w("OBD-C", "Vinculación de $mac sin auto pendiente. Se descarta.")
            scope.launch { reconciliarAsociaciones() }
            return
        }

        scope.launch {
            val respuesta = try {
                guardarVinculacion(carId, mac, associationInfo.id)
            } catch (e: Exception) {
                Log.e("OBD-C", "No se pudo guardar la vinculación: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) {
                completarVinculacion {
                    if (respuesta != null) it.success(respuesta)
                    else it.error("ERROR", "No se pudo guardar la vinculación", null)
                }
            }
        }
    }

    private suspend fun guardarVinculacion(carId: String, mac: String, associationId: Int): Map<String, Any?> =
        asociacionesMutex.withLock {
            // Un ESP32 por auto: si este auto tenía otro, se suelta.
            for (vieja in dao.getAssociationsByCar(carId)) {
                if (vieja.mac == mac) continue
                soltarAsociacion(vieja.mac, vieja.associationId)
                dao.deleteAssociationByMac(vieja.mac)
            }

            // Y si este ESP32 era de otro auto, pasa a ser de este (se le avisa al usuario).
            val reemplazoCarId = dao.getAssociationByMac(mac)?.carId?.takeIf { it != carId }

            dao.upsertAssociation(Association(mac, associationId, carId, System.currentTimeMillis()))
            Log.i("OBD-C", "¡Vinculado exitosamente! MAC: $mac → auto $carId")

            // le dice a android que a partir de ahora, si detecta esta MAC en el aire, que despierte la app
            deviceManager.startObservingDevicePresence(mac)
            ObdCompanionService.asociacionesCambiaron()

            mapOf(
                "carId" to carId,
                "mac" to mac,
                "associationId" to associationId,
                "deviceLabel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "reemplazoCarId" to reemplazoCarId,
            )
        }

    // Android deja de despertar la app por ese ESP32 y olvida la asociación.
    private fun soltarAsociacion(mac: String, associationId: Int) {
        try {
            deviceManager.stopObservingDevicePresence(mac)
        } catch (e: Exception) {
            Log.w("OBD-C", "No se pudo dejar de observar $mac: ${e.message}")
        }
        try {
            deviceManager.disassociate(associationId)
        } catch (e: Exception) {
            Log.w("OBD-C", "No se pudo desasociar $mac: ${e.message}")
        }
    }

    // Deja iguales las asociaciones del sistema y las de Room (que son las que dicen de qué auto es cada ESP32).
    private suspend fun reconciliarAsociaciones() = asociacionesMutex.withLock {
        val delSistema = deviceManager.myAssociations
        val filas = dao.getAssociations()

        // Con el selector abierto puede haber una asociación recién creada cuya fila todavía
        // no se guardó: no es huérfana.
        if (pendingCarId == null) {
            for (info in delSistema) {
                val mac = info.deviceMacAddress?.toString()?.uppercase()
                if (mac != null && filas.any { it.mac == mac }) continue
                // Creada antes de que existiera la tabla: no se sabe de qué auto es. El
                // usuario tiene que vincular de nuevo.
                Log.w("OBD-C", "Asociación huérfana ($mac): se desasocia.")
                if (mac != null) soltarAsociacion(mac, info.id) else deviceManager.disassociate(info.id)
            }
        }

        // Las que el usuario quitó desde Ajustes del sistema.
        val macsDelSistema = delSistema.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }.toSet()
        var huboCambios = false
        for (fila in filas) {
            if (fila.mac in macsDelSistema) continue
            Log.w("OBD-C", "La asociación de ${fila.mac} ya no existe en el sistema: se borra.")
            dao.deleteAssociationByMac(fila.mac)
            huboCambios = true
        }
        if (huboCambios) ObdCompanionService.asociacionesCambiaron()
    }

    private suspend fun desvincular(asociaciones: List<Association>) = asociacionesMutex.withLock {
        for (a in asociaciones) {
            soltarAsociacion(a.mac, a.associationId)
            dao.deleteAssociationByMac(a.mac)
        }
        ObdCompanionService.asociacionesCambiaron()
    }

    // Corre [block] fuera del hilo principal y le responde a Flutter en el principal.
    private fun responderEnIO(result: MethodChannel.Result, block: suspend () -> Any?) {
        scope.launch {
            val respuesta = runCatching { block() }
            withContext(Dispatchers.Main) {
                try {
                    respuesta.fold(
                        onSuccess = { result.success(it) },
                        onFailure = {
                            Log.e("OBD-C", "Falló una llamada de Flutter: ${it.message}")
                            result.error("ERROR", it.message, null)
                        },
                    )
                } catch (e: Exception) {
                    Log.w("OBD-C", "No se pudo responder a Flutter: ${e.message}")
                }
            }
        }
    }

    //configura el MethodChannel por el que Flutter le pide cosas al nativo
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                // No responde enseguida: lo hace onActivityResult cuando el usuario elige (o cancela).
                "iniciarVinculacion" -> {
                    val carId = call.argument<String>("carId")
                    if (carId.isNullOrBlank()) {
                        result.error("ARGUMENTO", "Falta carId", null)
                        return@setMethodCallHandler
                    }
                    iniciarVinculacionOBD(carId, result)
                }

                // Flutter avisa a qué servidor y con qué usuario se sube. Se llama al entrar.
                "configurar" -> {
                    val baseUrl = call.argument<String>("baseUrl")
                    if (baseUrl.isNullOrBlank()) {
                        result.error("ARGUMENTO", "Falta baseUrl", null)
                        return@setMethodCallHandler
                    }
                    NativeSession.saveConfig(this, baseUrl, call.argument<String>("userId"))
                    responderEnIO(result) {
                        reconciliarAsociaciones()
                        null
                    }
                }

                // TEMPORAL (Fase 1), se elimina en la Fase 2: el access token de la sesión de
                // Flutter, para que el SyncWorker pueda subir con la app cerrada.
                "setSessionToken" -> {
                    val token = call.argument<String>("accessToken")
                    NativeSession.saveAccessToken(this, token)
                    // Token nuevo: subir ya lo que haya quedado en cola (aunque hubiera un backoff largo).
                    if (token != null) SyncScheduler.requestSync(this, replace = true)
                    result.success(null)
                }

                "obtenerAsociaciones" -> responderEnIO(result) {
                    reconciliarAsociaciones()
                    dao.getAssociations().map {
                        mapOf("carId" to it.carId, "mac" to it.mac, "associationId" to it.associationId)
                    }
                }

                "estadoSincronizacion" -> responderEnIO(result) {
                    mapOf(
                        "pendingChunks" to dao.countPendingChunks(),
                        "pendingTrips" to dao.countPendingTrips(),
                        "failedChunks" to dao.countFailedChunks(),
                    )
                }

                // Este celular deja de registrar ese auto. Lo que ya se grabó igual se sube.
                "desvincularAuto" -> {
                    val carId = call.argument<String>("carId")
                    if (carId.isNullOrBlank()) {
                        result.error("ARGUMENTO", "Falta carId", null)
                        return@setMethodCallHandler
                    }
                    responderEnIO(result) {
                        desvincular(dao.getAssociationsByCar(carId))
                        null
                    }
                }

                // Logout: no queda ningún ESP32 vinculado y el nativo deja de poder subir.
                "cerrarSesion" -> {
                    val descartarPendientes = call.argument<Boolean>("descartarPendientes") ?: false
                    responderEnIO(result) {
                        desvincular(dao.getAssociations())
                        NativeSession.clearCredentials(this)
                        if (descartarPendientes) {
                            dao.deleteAllChunks()
                            dao.deleteAllTrips()
                        }
                        null
                    }
                }

                else -> result.notImplemented()
            }
        }

        SyncScheduler.schedulePeriodic(this)

        ObdEventBridge.flutterApi = ObdFlutterApi(flutterEngine.dartExecutor.binaryMessenger)
    }

    // Si el usuario mata la interfaz gráfica, desconectamos el puente
    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        ObdEventBridge.flutterApi = null
        // Que ninguna vinculación quede esperando una respuesta que ya no va a llegar.
        completarVinculacion { it.error("CANCELADO", "La pantalla se cerró", null) }
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
