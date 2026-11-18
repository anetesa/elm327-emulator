package io.github.anetesa.elm327

import kotlin.test.Test
import kotlin.test.assertEquals

class AudiQ5Test {

    private val car = AudiQ5.coldStart()

    @Test
    fun `the lamp is on with three stored misfire codes and the EVAP leak stays permanent`() {
        assertEquals(listOf(0x83), car.pidBytes(0x01, 10.0)?.take(1), "MIL on, 3 codes")
        assertEquals(listOf(0x0300, 0x0301, 0x0302), car.stored)
        assertEquals(listOf(0x0456, 0x0300, 0x0302), car.permanent)
    }

    @Test
    fun `catalyst, EVAP and oxygen sensor monitors are not ready`() {
        assertEquals(0x25, car.pidBytes(0x01, 10.0)?.get(3))
    }

    @Test
    fun `the freeze frame is the cold start of the scan`() {
        assertEquals(listOf(0x03, 0x00), car.freezeBytes(0x02), "stored by P0300")
        assertEquals(listOf(93), car.freezeBytes(0x0E), "timing -17.5 degrees")
        assertEquals(listOf(147), car.freezeBytes(0x07), "long-term trim +14.8 %")
    }

    @Test
    fun `cylinders 1 and 2 misfire during the first seconds, then the counters stop`() {
        fun current(mid: Int, t: Double) = car.monitorBytes(mid, t)!!.chunked(9).first { it[1] == 0x0C }.let { it[3] * 256 + it[4] }
        assertEquals(0, current(0xA2, 0.0))
        assertEquals(22, current(0xA2, 30.0))
        assertEquals(23, current(0xA3, 30.0))
        assertEquals(0, current(0xA4, 30.0))
    }

    @Test
    fun `on the first start after clearing codes cylinder 2 misfires 327 times until the catalyst is warm`() {
        val before = AudiQ5.coldStart(misfires = AudiQ5.Misfires.AFTER_CLEAR)
        fun current(mid: Int, t: Double) = before.monitorBytes(mid, t)!!.chunked(9).first { it[1] == 0x0C }.let { it[3] * 256 + it[4] }
        assertEquals(327, current(0xA3, 45.0))
        assertEquals(64, current(0xA2, 45.0))
        assertEquals(0, current(0xA5, 45.0))
    }

    @Test
    fun `the engine unit gives the manufacturer code to UDS 19 02 sent to it, not to the functional address`() {
        val elm = Elm327(AdapterProfiles.Genuine, AudiQ5.coldStart()) { 10.0 }
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100").forEach(elm::execute)
        assertEquals(true, elm.execute("1902FF").contains("NO DATA"), "7DF does not get it")
        elm.execute("ATSH7E0")
        val reply = elm.execute("1902FF").replace(Regex("[\\r>]"), "")
        assertEquals(true, reply.contains("11A60009"), reply)
    }

    @Test
    fun `the Viecar profile is named as the real adapter`() {
        assertEquals("Viecar", AdapterProfiles.Viecar.bluetoothName)
        assertEquals("ELM327 v1.5", AdapterProfiles.Viecar.banner)
    }
}
