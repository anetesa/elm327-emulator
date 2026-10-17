package io.github.anetesa.elm327

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Elm327Test {

    private fun elm(
        profile: AdapterProfile = AdapterProfiles.Genuine,
        bus: Bus = Bus.CAN,
        standard: ObdStandard = ObdStandard.J1979,
    ) = Elm327(profile, DemoVehicles.petrol(bus, standard)) { 10.0 }

    /** The reply lines without the echo and the prompt, as a client app sees them. */
    private fun Elm327.lines(command: String) = execute(command).removeSuffix(">").trim('\r').split("\r").filter { it.isNotEmpty() }

    /** The usual start of a client app: reset, echo off, compact output, automatic protocol. */
    private fun Elm327.quiet() = apply { listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach(::execute) }

    @Test
    fun `reset answers with the banner, echoes and ends with the prompt`() {
        assertEquals("ATZ\rELM327 v2.2\r\r>", elm().execute("ATZ"))
        assertEquals("ATE0\rOK\r\r>", elm().execute("ATE0"), "ATE0 itself is still echoed")
    }

    @Test
    fun `linefeeds, spaces and echo follow the settings`() {
        val elm = elm()
        elm.execute("ATL1")
        assertEquals("0100\r\nSEARCHING...\r\n41 00 BE 3F B8 13\r\n\r\n>", elm.execute("0100").replace(Regex("41 00 .. .. .. .."), "41 00 BE 3F B8 13"))
        elm.quiet()
        assertEquals(listOf("410C"), elm.lines("010C").map { it.take(4) }.drop(1))
    }

    @Test
    fun `the first request searches for the protocol, then the protocol is known`() {
        val elm = elm().quiet()
        assertEquals("SEARCHING...", elm.lines("010D").first())
        assertEquals(listOf("410D00"), elm.lines("010D"))
        assertEquals(listOf("A6"), elm.lines("ATDPN"))
        assertEquals(listOf("AUTO, ISO 15765-4 (CAN 11/500)"), elm.lines("ATDP"))
    }

    @Test
    fun `a manual protocol of the wrong bus cannot connect`() {
        val elm = elm(bus = Bus.K_LINE).quiet()
        elm.execute("ATSP6")
        assertEquals(listOf("SEARCHING...", "UNABLE TO CONNECT"), elm.lines("0100"))
        elm.execute("ATSP3")
        assertEquals(listOf("410D00"), elm.lines("010D"))
        assertEquals(listOf("3"), elm.lines("ATDPN"))
    }

    @Test
    fun `a long CAN reply is split into numbered ISO-TP lines`() {
        val elm = elm().quiet()
        elm.execute("0100")
        val vin = elm.lines("0902")
        assertEquals("014", vin[0])
        assertEquals("0:490201314734", vin[1])
        assertEquals(listOf("1:4A433534343452", "2:37323532333637"), vin.drop(2))
    }

    @Test
    fun `with headers on every CAN frame shows its ID, control byte and padding`() {
        val elm = elm().quiet()
        elm.execute("0100")
        elm.execute("ATH1")
        elm.execute("ATS1")
        assertEquals(listOf("7E8 03 41 0D 00 00 00 00 00"), elm.lines("010D"))
        val vin = elm.lines("0902")
        assertEquals("7E8 10 14 49 02 01 31 47 34", vin[0])
        assertEquals("7E8 21 4A 43 35 34 34 34 52", vin[1])
        assertEquals(listOf("7E8 22 37 32 35 32 33 36 37"), vin.drop(2))
    }

    @Test
    fun `K-Line answers one line per message, with header and checksum when asked`() {
        val elm = elm(bus = Bus.K_LINE).quiet()
        elm.execute("0100")
        assertEquals(5, elm.lines("0902").size, "five messages of four VIN bytes")
        elm.execute("ATH1")
        // 48 6B 10 41 0D 00 + checksum (0x48 + 0x6B + 0x10 + 0x41 + 0x0D = 0x111 → 0x11)
        assertEquals(listOf("486B10410D0011"), elm.lines("010D"))
    }

    @Test
    fun `trouble codes, the warning lamp and clearing them`() {
        val elm = elm().quiet()
        elm.execute("0100")
        assertEquals(listOf("4302017103000000".take(12)), elm.lines("03"))
        assertEquals(0x82, elm.lines("0101").single().substring(4, 6).toInt(16), "lamp on, two codes")
        assertEquals(listOf("44"), elm.lines("04"))
        assertEquals(listOf("4300"), elm.lines("03"))
        assertEquals(0x00, elm.lines("0101").single().substring(4, 6).toInt(16))
    }

    @Test
    fun `fake clones answer only the first PID and reject some AT commands`() {
        val elm = elm(AdapterProfiles.FakeV21).quiet()
        elm.execute("0100")
        assertEquals(listOf("410D00"), elm.lines("010D0C"))
        assertEquals(listOf("?"), elm.lines("ATAT1"))
        assertEquals(listOf("?"), elm.lines("AT@1"))
        val genuine = elm().quiet()
        genuine.execute("0100")
        assertTrue(genuine.lines("010D0C").single().startsWith("410D000C"))
    }

    @Test
    fun `small buffers overflow and unstable links drop`() {
        val small = elm(AdapterProfiles.SmallBuffer).quiet()
        small.execute("0100")
        assertEquals(listOf("BUFFER FULL"), small.lines("0902"))
        val unstable = elm(AdapterProfiles.Unstable)
        repeat(18) { unstable.execute("ATI") }
        assertFailsWith<LinkDroppedException> { unstable.execute("ATI") }
    }

    @Test
    fun `OBD on UDS cars ignore classic requests and answer UDS ones`() {
        val elm = elm(standard = ObdStandard.J1979_2).quiet()
        assertEquals(listOf("SEARCHING...", "UNABLE TO CONNECT"), elm.lines("0100"))
        assertEquals(listOf("SEARCHING...", "62F81001"), elm.lines("22F810"))
        assertEquals(listOf("54"), elm.lines("14FFFF33"))
    }

    @Test
    fun `only STN chips know STI, unknown commands get a question mark`() {
        assertEquals(listOf("STN1110 v4.2.0"), elm(AdapterProfiles.Stn).quiet().lines("STI"))
        assertEquals(listOf("?"), elm().quiet().lines("STI"))
        assertEquals(listOf("?"), elm().quiet().lines("HELLO"))
    }
}
