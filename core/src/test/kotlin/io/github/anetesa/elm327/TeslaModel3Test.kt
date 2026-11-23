package io.github.anetesa.elm327

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Model 3 says nothing when asked and everything when listened to. */
class TeslaModel3Test {

    private fun elm() = Elm327(AdapterProfiles.Genuine, TeslaModel3.longRange()) { 10.0 }.apply {
        listOf("ATZ", "ATE0", "ATS0", "ATSP6", "ATH1", "ATCAF0").forEach(::execute)
    }

    @Test
    fun `OBD requests get no answer`() {
        assertTrue(elm().execute("0100").contains("NO DATA"))
    }

    @Test
    fun `monitor prints the filtered message until any character stops it`() {
        val elm = elm()
        elm.execute("ATCRA292")
        val printed = elm.execute("ATMA")
        assertFalse(printed.endsWith(">"), "no prompt while monitoring")
        val frames = printed.split("\r").filter { it.isNotEmpty() }
        assertTrue(frames.all { it.startsWith("292") && it.length == 3 + 16 }, frames.toString())
        val data = frames.first().drop(3).chunked(2).map { it.toInt(16) }
        val word = data.foldIndexed(0L) { i, acc, b -> acc or (b.toLong() shl (8 * i)) }
        assertEquals(720, (word shr 10 and 0x3FF).toInt(), "72.0 % displayed")
        assertEquals(778, (word shr 40 and 0x3FF).toInt(), "77.8 kWh when new")
        assertTrue(elm.monitoring)
        assertEquals("\r>", elm.execute("\u0000"))
        assertFalse(elm.monitoring)
    }

    @Test
    fun `brick voltages come as 32 pages of three`() {
        val elm = elm()
        elm.execute("ATCRA401")
        val pages = elm.execute("ATMA").split("\r").filter { it.startsWith("401") }.map { it.substring(3, 5).toInt(16) }.toSet()
        assertEquals((0 until 32).toSet(), pages)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a lone carriage return stops the monitor and does not repeat ATMA`() = runTest {
        val replies = StringBuilder()
        val session = ElmSession(backgroundScope, AdapterProfiles.Genuine, TeslaModel3.longRange(), send = { replies.append(it.decodeToString()) })
        for (cmd in listOf("ATE0", "ATH1", "ATSP6", "ATCRA132", "ATMA")) session.receive("$cmd\r".encodeToByteArray())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(replies.endsWith("\r"), "monitoring")
        session.receive("\r".encodeToByteArray())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(replies.endsWith("\r>"), replies.takeLast(40).toString())
        session.receive("ATI\r".encodeToByteArray())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(replies.endsWith("ELM327 v2.2\r\r>"), "back to commands")
    }
}
