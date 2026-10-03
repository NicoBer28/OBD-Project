package com.example.obd_app

/**
 * Un dato que se le puede pedir al auto: qué trama mandar y cómo leer la respuesta.
 *
 * @param clave nombre del valor en cada muestra guardada. `s`, `f` y `m` tienen campo propio
 *   en el servidor; cualquier otra viaja en `raw` con ese nombre. No usar `t`, `la` ni `lo`.
 * @param pid un byte en el modo 01; dos en un PID propio del fabricante (modo 22).
 * @param bytes cuántos bytes de datos trae la respuesta (los A, B, C... de la fórmula).
 * @param formula recibe esos bytes como enteros sin signo.
 */
class Senal(
    val clave: String,
    private val pid: List<Int>,
    private val bytes: Int,
    private val servicio: Int = 0x01,
    canId: Long = 0x7DF,
    private val formula: (IntArray) -> Int,
) {
    val peticion: TramaCan = TramaCan(
        canId = canId,
        dlc = 8,
        data = ByteArray(8) { RELLENO.toByte() }.also { data ->
            data[0] = (1 + pid.size).toByte()
            data[1] = servicio.toByte()
            pid.forEachIndexed { i, byte -> data[2 + i] = byte.toByte() }
        },
    )

    /** El valor que trae [trama], o null si no es la respuesta a esta señal. */
    fun leer(trama: TramaCan): Int? {
        if (trama.canId !in IDS_RESPUESTA) return null

        val data = IntArray(trama.data.size) { trama.data[it].toInt() and 0xFF }
        // data[0] = cuántos bytes siguen (servicio + PID + datos). Mayor a 7 es una respuesta
        // partida en varias tramas, que el ESP32 no arma.
        val largo = data[0]
        val inicioDatos = 2 + pid.size
        if (largo > 7 || 1 + largo < inicioDatos + bytes) return null
        if (data[1] != servicio + RESPUESTA_OK) return null
        for (i in pid.indices) if (data[2 + i] != pid[i]) return null

        return formula(data.copyOfRange(inicioDatos, inicioDatos + bytes))
    }

    private companion object {
        const val RELLENO = 0xCC
        const val RESPUESTA_OK = 0x40
        val IDS_RESPUESTA = 0x7E8L..0x7EFL
    }
}

/** Catálogo de datos que la app sabe pedir. Para sumar uno alcanza con agregarlo acá. */
object Senales {
    /** km/h */
    val VELOCIDAD = Senal("s", pid = listOf(0x0D), bytes = 1) { it[0] }

    val RPM = Senal("rpm", pid = listOf(0x0C), bytes = 2) { (it[0] * 256 + it[1]) / 4 }

    /** Porcentaje del tanque. */
    val NAFTA = Senal("f", pid = listOf(0x2F), bytes = 1) { it[0] * 100 / 255 }

    /** Voltaje de la batería de 12 V, en milivoltios. Con el motor en marcha es el del alternador. */
    val VOLTAJE_BATERIA = Senal("batteryMv", pid = listOf(0x42), bytes = 2) { it[0] * 256 + it[1] }

    /** km */
    val ODOMETRO = Senal("m", pid = listOf(0xA6), bytes = 4) {
        (((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10).toInt()
    }
}

/**
 * Qué se le pide al auto. El ESP32 recorre en ronda la lista que se le manda, así que cuanto
 * más corta, más seguido llega cada dato.
 *
 * @param continuo lo que hace falta medir todo el tiempo.
 * @param puntual lo que alcanza con leer cada tanto: se pide al conectar y se refresca cada
 *   un rato; el resto del tiempo no ocupa lugar en la ronda.
 */
class PerfilLectura(val continuo: List<Senal>, val puntual: List<Senal>) {
    val completo: List<Senal> = continuo + puntual

    companion object {
        val POR_DEFECTO = PerfilLectura(
            continuo = listOf(Senales.VELOCIDAD, Senales.RPM),
            puntual = listOf(Senales.NAFTA, Senales.VOLTAJE_BATERIA, Senales.ODOMETRO),
        )

        /** El perfil de un auto. Hoy es el mismo para todos; acá va la elección por modelo. */
        fun para(carId: String?): PerfilLectura = POR_DEFECTO
    }
}
