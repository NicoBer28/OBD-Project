package com.example.obd_app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Una trama CAN tal como viaja por Bluetooth: el `BleCanPacket` del firmware.
 * [data] tiene siempre 8 bytes; [dlc] dice cuántos son válidos.
 */
class TramaCan(val canId: Long, val dlc: Int, val data: ByteArray)

/**
 * Formato de lo que se intercambia con el ESP32, en los dos sentidos: una o más tramas CAN
 * pegadas, de 13 bytes cada una (`can_id` uint32 little-endian + `dlc` + 8 bytes de datos).
 *
 * - App → ESP32: la lista de peticiones. El ESP32 la guarda y la repite en ronda hasta que
 *   le llegue otra.
 * - ESP32 → App: las tramas que recibió del auto, crudas y sin filtrar.
 */
object ProtocoloEsp {
    const val BYTES_POR_TRAMA = 13
    private const val BYTES_DE_DATOS = 8

    fun codificar(tramas: List<TramaCan>): ByteArray {
        val buffer = ByteBuffer.allocate(tramas.size * BYTES_POR_TRAMA).order(ByteOrder.LITTLE_ENDIAN)
        for (trama in tramas) {
            buffer.putInt(trama.canId.toInt())
            buffer.put(trama.dlc.toByte())
            buffer.put(trama.data.copyOf(BYTES_DE_DATOS))
        }
        return buffer.array()
    }

    /** Solo las tramas enteras: si sobran bytes (notificación cortada), se ignoran. */
    fun decodificar(bytes: ByteArray): List<TramaCan> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val cantidad = bytes.size / BYTES_POR_TRAMA
        val tramas = ArrayList<TramaCan>(cantidad)
        repeat(cantidad) {
            val canId = buffer.int.toLong() and 0xFFFFFFFFL
            val dlc = buffer.get().toInt() and 0xFF
            val data = ByteArray(BYTES_DE_DATOS).also { buffer.get(it) }
            tramas.add(TramaCan(canId, dlc, data))
        }
        return tramas
    }
}
