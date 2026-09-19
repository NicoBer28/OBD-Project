package com.example.obd_app

//es el buzón entre Kotlin y Flutter
//Si la app de Flutter está abierta, el buzón está lleno y Kotlin le manda los datos
//Si la app de Flutter está cerrada, el buzón queda vacío y Kotlin simplemente ignora el envío a la pantalla (pero sigue leyendo el hardware).
object ObdEventBridge {
    var flutterApi: ObdFlutterApi? = null
}