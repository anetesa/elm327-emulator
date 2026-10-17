package io.github.anetesa.elm327

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ElmSessionTest {

    /** Lets the session answer: it works in the background scope, which advanceUntilIdle skips. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    private companion object {
        const val SETTLE_MS = 10_000L
    }

    private class Client(scope: TestScope, profile: AdapterProfile = AdapterProfiles.Genuine) {
        val chunks = mutableListOf<String>()
        val events = mutableListOf<SessionEvent>()
        var dropped = false
        val session = ElmSession(
            scope.backgroundScope,
            profile,
            DemoVehicles.petrol(),
            send = { chunks += it.decodeToString() },
            onEvent = { events += it },
            onDropped = { dropped = true },
        )
        val text get() = chunks.joinToString("")
    }

    @Test
    fun `commands arrive in any pieces and end with a carriage return`() = runTest {
        val client = Client(this)
        client.session.receive("AT".encodeToByteArray())
        client.session.receive("I\r\nATE".encodeToByteArray())
        client.session.receive("0\r".encodeToByteArray())
        settle()
        assertEquals("ATI\rELM327 v2.2\r\r>ATE0\rOK\r\r>", client.text)
    }

    @Test
    fun `an empty command repeats the previous one`() = runTest {
        val client = Client(this)
        client.session.receive("ATI\r\r".encodeToByteArray())
        settle()
        assertEquals(2, client.events.count { it is SessionEvent.Command && it.command == "ATI" })
    }

    @Test
    fun `slow BLE clones send replies in 20-byte chunks`() = runTest {
        val client = Client(this, AdapterProfiles.SlowBle)
        client.session.receive("AT@1\r".encodeToByteArray())
        settle()
        assertTrue(client.chunks.size > 1)
        assertTrue(client.chunks.all { it.length <= 20 })
        assertEquals("AT@1\rOBDII to RS232 Interpreter\r\r>", client.text)
    }

    @Test
    fun `an unstable link drops and tells the transport`() = runTest {
        val client = Client(this, AdapterProfiles.Unstable)
        client.session.receive("ATI\r".repeat(20).encodeToByteArray())
        settle()
        assertTrue(client.dropped)
        assertTrue(client.events.last() is SessionEvent.Dropped)
    }
}
