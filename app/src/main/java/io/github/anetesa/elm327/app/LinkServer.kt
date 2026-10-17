package io.github.anetesa.elm327.app

import io.github.anetesa.elm327.ElmSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class TransportKind(val title: String) {
    CLASSIC("Bluetooth Classic (SPP)"),
    BLE("Bluetooth LE"),
    WIFI("Wi‑Fi (TCP)"),
}

sealed interface ServerStatus {
    data object Off : ServerStatus

    data class Listening(val detail: String) : ServerStatus

    data class Connected(val client: String) : ServerStatus

    data class Failed(val message: String) : ServerStatus
}

/** What a transport server needs from the app: a session per client, status and log lines. */
interface ServerHost {
    fun status(kind: TransportKind, status: ServerStatus)

    fun log(kind: TransportKind, client: String, text: String)

    /** A fresh emulated adapter for [client]; [drop] disconnects the client when the link "fails". */
    fun openSession(kind: TransportKind, client: String, send: suspend (ByteArray) -> Unit, drop: () -> Unit): ElmSession
}

/** A transport the emulated adapter is reachable through. */
interface LinkServer {
    val kind: TransportKind

    fun start(scope: CoroutineScope, host: ServerHost)

    fun stop()
}

/**
 * Serves one client of a stream transport (RFCOMM, TCP) until it disconnects: reads its bytes
 * into the session and writes the session's replies back.
 */
internal suspend fun serveStream(
    kind: TransportKind,
    client: String,
    input: InputStream,
    output: OutputStream,
    host: ServerHost,
    close: () -> Unit,
) {
    host.status(kind, ServerStatus.Connected(client))
    host.log(kind, client, "connected")
    val session = host.openSession(
        kind,
        client,
        send = { bytes ->
            withContext(Dispatchers.IO) {
                try {
                    output.write(bytes)
                    output.flush()
                } catch (_: IOException) {
                    close()
                }
            }
        },
        drop = close,
    )
    try {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = withContext(Dispatchers.IO) { input.read(buffer) }
            if (read < 0) break
            session.receive(buffer.copyOf(read))
        }
    } catch (_: IOException) {
        // The client went away or the server was stopped
    } finally {
        session.close()
        close()
        host.log(kind, client, "disconnected")
    }
}

private const val BUFFER_SIZE = 1024
