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
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executor

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.example.obd_app/ble_channel"
    private val COMPANION_REQUEST_CODE = 1001
    // UUID del servicio de tu ESP32
    private val OBD_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    fun iniciarVinculacionOBD() {
        val deviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        // Filtramos para que SOLO muestre dispositivos que tengan tu UUID
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(OBD_SERVICE_UUID))
                    .build()
            )
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false) // Ponelo en true si querés que vincule el primero que encuentre sin preguntar
            .build()

        val executor = Executor { it.run() }

        deviceManager.associate(request, executor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                // Esto abre la ventanita de Android donde el usuario selecciona el ESP32
                startIntentSenderForResult(intentSender, COMPANION_REQUEST_CODE, null, 0, 0, 0)
            }

            override fun onFailure(error: CharSequence?) {
                Log.e("OBD-C", "Error al buscar el dispositivo: $error")
            }
        })
    }

    // Android nos avisa acá cuando el usuario eligió el dispositivo en la ventanita
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == COMPANION_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val associationInfo = data?.getParcelableExtra<AssociationInfo>(CompanionDeviceManager.EXTRA_ASSOCIATION)
            
            associationInfo?.let {
                val macMayusculas = it.deviceMacAddress.toString()

                Log.i("OBD-C", "¡Vinculado exitosamente! MAC: ${macMayusculas}")
                
                // ¡LA MAGIA OCURRE ACÁ!
                // Le decimos a Android: "A partir de ahora, si ves esta MAC en el aire, despertá mi app"
                val deviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
                deviceManager.startObservingDevicePresence(macMayusculas)
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // 1. Configuramos el MethodChannel (La Vinculación Inicial)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "iniciarVinculacion") {
                iniciarVinculacionOBD()
                result.success("Ventana nativa abierta")
            } else {
                result.notImplemented()
            }
        }

        // 2. Configuramos Pigeon (El Puente de Datos Continuo hacia Flutter)
        ObdEventBridge.flutterApi = ObdFlutterApi(flutterEngine.dartExecutor.binaryMessenger)
}

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        // Si el usuario mata la interfaz gráfica, desconectamos el puente
        ObdEventBridge.flutterApi = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}