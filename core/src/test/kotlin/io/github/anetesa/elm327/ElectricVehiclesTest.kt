package io.github.anetesa.elm327

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Battery units answer only when the adapter is set up the way a real one must be. */
class ElectricVehiclesTest {

    private fun Elm327.lines(command: String) = execute(command).removeSuffix(">").trim('\r').split("\r").filter { it.isNotEmpty() }

    private fun Elm327.quiet() = apply { listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0").forEach(::execute) }

    private fun elm(car: Vehicle, profile: AdapterProfile = AdapterProfiles.Genuine) = Elm327(profile, car) { 10.0 }.quiet()

    /** The reply bytes of a multi-frame answer printed with headers off. */
    private fun bytes(lines: List<String>): List<Int> {
        val length = lines.first().toInt(16)
        return lines.drop(1).joinToString("") { it.substringAfter(':') }.chunked(2).map { it.toInt(16) }.take(length)
    }

    @Test
    fun `a Leaf does not answer OBD at all, so the protocol search fails`() {
        val elm = elm(ElectricVehicles.leaf2016()).apply { execute("ATSP0") }
        assertEquals(listOf("SEARCHING...", "UNABLE TO CONNECT"), elm.lines("0100"))
    }

    @Test
    fun `the Leaf battery answers on 7BB with flow control sent to 79B`() {
        val elm = elm(ElectricVehicles.leaf2016()).apply { listOf("ATSP6", "ATSH79B").forEach(::execute) }
        assertEquals(listOf("NO DATA"), elm.lines("2101"), "the adapter does not listen on 7BB yet")
        elm.execute("ATCRA7BB")
        assertEquals(2, elm.lines("2101").size, "without its flow control the unit sends only the first frame")
        listOf("ATFCSH79B", "ATFCSD300000", "ATFCSM1").forEach(elm::execute)
        val group1 = bytes(elm.lines("2101"))
        assertEquals(listOf(0x61, 0x01), group1.take(2))
        assertEquals(43, group1.size, "41 data bytes: a 30 kWh pack")
        val cells = bytes(elm.lines("2102"))
        assertEquals(2 + 196, cells.size, "96 cells and two voltages")
        assertEquals(3902, cells[2 + 37 * 2] * 256 + cells[2 + 37 * 2 + 1], "cell 38 is the weak one")
    }

    @Test
    fun `the 2019 Leaf reports its SOH`() {
        val elm = elm(ElectricVehicles.leaf2019()).apply { listOf("ATSP6", "ATSH79B", "ATCRA7BB").forEach(::execute) }
        assertEquals("6161000024540", elm.lines("2161").single().take(13) + "0")
    }

    @Test
    fun `Kona answers OBD as an electric car and its battery unit on 7E4`() {
        val elm = elm(ElectricVehicles.konaElectric()).apply { execute("ATSP0") }
        assertTrue(elm.lines("0100").last().startsWith("4100"))
        assertEquals("415108", elm.lines("0151").single(), "fuel type: electric")
        elm.execute("ATSH7E4")
        val main = bytes(elm.lines("220101"))
        assertEquals(142, main[3 + 4], "71 % state of charge, ×2")
        assertEquals(listOf("7F2231"), elm.lines("22010A"), "a 98-cell pack has no fourth page")
    }

    @Test
    fun `Ioniq 5 has six pages of 32 cells`() {
        val elm = elm(ElectricVehicles.ioniq5()).apply { listOf("ATSP0", "0100", "ATSH7E4").forEach(::execute) }
        for (page in listOf("02", "03", "04", "0A", "0B", "0C")) {
            assertEquals(listOf(0x62, 0x01, page.toInt(16), 0xFF, 0xFF, 0xFF, 0xFF), bytes(elm.lines("2201$page")).take(7), page)
        }
    }

    @Test
    fun `the ID 4 battery needs 29-bit ids and manual flow control`() {
        val elm = elm(ElectricVehicles.id4())
        listOf("ATSP6", "ATSHFC007B").forEach(elm::execute)
        assertEquals(listOf("NO DATA"), elm.lines("22028C"), "on 11-bit ids nobody answers")
        listOf("ATSP7", "ATCP17", "ATSHFC007B", "ATCRA17FE007B").forEach(elm::execute)
        assertEquals("62028CA0", elm.lines("22028C").single(), "64 % × 2.5")
        assertEquals("621E3B05C6", elm.lines("221E3B").single(), "369.6 V × 4")
        elm.execute("ATH1")
        assertTrue(elm.lines("22028C").single().startsWith("17FE007B"), "headers show the battery unit's id")
    }

    @Test
    fun `back on the functional address, OBD answers come from the engine again`() {
        val elm = elm(ElectricVehicles.konaElectric()).apply { listOf("ATSP0", "0100", "ATSH7E4", "ATCRA7EC").forEach(::execute) }
        elm.lines("220101")
        listOf("ATAR", "ATFCSM0", "ATSH7DF").forEach(elm::execute)
        assertEquals("410D00", elm.lines("010D").single())
    }
}
