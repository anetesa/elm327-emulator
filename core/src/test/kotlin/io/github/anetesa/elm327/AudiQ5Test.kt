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
    fun `before the AVS repair cylinder 2 misfires 327 times until the catalyst is warm`() {
        val before = AudiQ5.coldStart(misfires = AudiQ5.Misfires.BEFORE_AVS)
        fun current(mid: Int, t: Double) = before.monitorBytes(mid, t)!!.chunked(9).first { it[1] == 0x0C }.let { it[3] * 256 + it[4] }
        assertEquals(327, current(0xA3, 45.0))
        assertEquals(64, current(0xA2, 45.0))
        assertEquals(0, current(0xA5, 45.0))
    }

    @Test
    fun `the Viecar profile is named as the real adapter`() {
        assertEquals("Viecar", AdapterProfiles.Viecar.bluetoothName)
        assertEquals("ELM327 v1.5", AdapterProfiles.Viecar.banner)
    }
}
