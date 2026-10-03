package com.example.obd_app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocoloEspTest {
    private fun bytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun trama(hex: String): TramaCan = ProtocoloEsp.decodificar(bytes(hex)).single()

    // ---- formato del cable ----

    @Test
    fun `una peticion se codifica como el BleCanPacket del firmware`() {
        assertArrayEquals(
            bytes("DF 07 00 00 08 02 01 0C CC CC CC CC CC"),
            ProtocoloEsp.codificar(listOf(Senales.RPM.peticion)),
        )
    }

    @Test
    fun `una lista son las tramas pegadas, en orden`() {
        val lista = ProtocoloEsp.codificar(PerfilLectura.POR_DEFECTO.completo.map { it.peticion })

        assertEquals(5 * ProtocoloEsp.BYTES_POR_TRAMA, lista.size)
        assertEquals(listOf(0x0D, 0x0C, 0x2F, 0x42, 0xA6), ProtocoloEsp.decodificar(lista).map { it.data[2].toInt() and 0xFF })
    }

    @Test
    fun `un aviso con varias tramas se separa`() {
        val tramas = ProtocoloEsp.decodificar(
            bytes("E8 07 00 00 08 04 41 0C 1A F8 00 00 00  E9 07 00 00 08 03 41 0D 3C 00 00 00 00")
        )

        assertEquals(listOf(0x7E8L, 0x7E9L), tramas.map { it.canId })
        assertEquals(listOf(8, 8), tramas.map { it.dlc })
    }

    @Test
    fun `los bytes que no completan una trama se ignoran`() {
        assertEquals(1, ProtocoloEsp.decodificar(bytes("E8 07 00 00 08 03 41 0D 3C 00 00 00 00  E8 07 00")).size)
        assertEquals(0, ProtocoloEsp.decodificar(bytes("01 3C 00 00")).size) // paquete del firmware viejo
        assertEquals(0, ProtocoloEsp.decodificar(ByteArray(0)).size)
    }

    // ---- señales ----

    @Test
    fun `cada senal lee su respuesta`() {
        assertEquals(60, Senales.VELOCIDAD.leer(trama("E8 07 00 00 08 03 41 0D 3C 00 00 00 00")))
        assertEquals(1726, Senales.RPM.leer(trama("E8 07 00 00 08 04 41 0C 1A F8 00 00 00")))
        assertEquals(50, Senales.NAFTA.leer(trama("E8 07 00 00 08 03 41 2F 80 00 00 00 00")))
        assertEquals(14000, Senales.VOLTAJE_BATERIA.leer(trama("E8 07 00 00 08 04 41 42 36 B0 00 00 00")))
        assertEquals(10000, Senales.ODOMETRO.leer(trama("E8 07 00 00 08 06 41 A6 00 01 86 A0 00")))
    }

    @Test
    fun `responde cualquier centralita del auto`() {
        assertEquals(60, Senales.VELOCIDAD.leer(trama("EF 07 00 00 08 03 41 0D 3C 00 00 00 00")))
    }

    @Test
    fun `una trama que no es su respuesta no se lee`() {
        // otro PID
        assertNull(Senales.VELOCIDAD.leer(trama("E8 07 00 00 08 04 41 0C 1A F8 00 00 00")))
        // tráfico del bus que no es de diagnóstico, aunque los bytes coincidan
        assertNull(Senales.VELOCIDAD.leer(trama("20 01 00 00 08 03 41 0D 3C 00 00 00 00")))
        // la propia petición
        assertNull(Senales.VELOCIDAD.leer(Senales.VELOCIDAD.peticion))
        // respuesta de error del auto (7F = servicio no soportado)
        assertNull(Senales.VELOCIDAD.leer(trama("E8 07 00 00 08 03 7F 01 12 00 00 00 00")))
        // respuesta con menos datos de los que necesita la fórmula
        assertNull(Senales.RPM.leer(trama("E8 07 00 00 08 03 41 0C 1A 00 00 00 00")))
        // primera trama de una respuesta larga, que el ESP32 no arma
        assertNull(Senales.VELOCIDAD.leer(trama("E8 07 00 00 08 10 14 41 0D 3C 00 00 00")))
    }

    @Test
    fun `un PID de fabricante de dos bytes entra en el mismo formato`() {
        val senal = Senal("x", pid = listOf(0xF4, 0x0D), bytes = 1, servicio = 0x22, canId = 0x7E0) { it[0] }

        assertArrayEquals(bytes("E0 07 00 00 08 03 22 F4 0D CC CC CC CC"), ProtocoloEsp.codificar(listOf(senal.peticion)))
        assertEquals(60, senal.leer(trama("E8 07 00 00 08 04 62 F4 0D 3C 00 00 00")))
    }
}
