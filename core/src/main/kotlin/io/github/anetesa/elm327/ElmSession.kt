package io.github.anetesa.elm327

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What happened on a link, for the log. */
sealed interface SessionEvent {
    data class Command(val command: String, val reply: String) : SessionEvent

    data class Dropped(val reason: String) : SessionEvent
}

/**
 * One connected client, whatever the transport (Bluetooth Classic, BLE, TCP). Bytes from the
 * client are collected into commands up to the carriage return; each reply is sent back after the
 * adapter's delay, in the adapter's chunk size. Every session gets a fresh adapter and car, so codes
 * cleared by the previous client are back.
 */
class ElmSession(
    scope: CoroutineScope,
    private val profile: AdapterProfile,
    vehicle: Vehicle,
    private val send: suspend (ByteArray) -> Unit,
    private val onEvent: (SessionEvent) -> Unit = {},
    /** Called when the emulated link drops: the transport should disconnect the client. */
    private val onDropped: () -> Unit = {},
) {
    private val elm = Elm327(profile, vehicle)
    private val commands = Channel<String>(Channel.UNLIMITED)
    private val line = StringBuilder()
    private var last: String? = null

    private val job: Job = scope.launch {
        for (command in commands) {
            delay(profile.commandDelayMs)
            val reply = try {
                elm.execute(command)
            } catch (e: LinkDroppedException) {
                onEvent(SessionEvent.Dropped(e.message.orEmpty()))
                onDropped()
                break
            }
            onEvent(SessionEvent.Command(command, reply))
            reply.encodeToByteArray().asList().chunked(profile.chunkSize).forEachIndexed { i, chunk ->
                if (i > 0 && profile.chunkDelayMs > 0) delay(profile.chunkDelayMs)
                send(chunk.toByteArray())
            }
        }
    }

    /**
     * Bytes from the client, in any pieces. A command ends with '\r' ('\n' is ignored, as by the
     * chip); an empty command repeats the previous one, as ELM327 does.
     */
    fun receive(bytes: ByteArray) {
        for (b in bytes) {
            when (val c = (b.toInt() and BYTE_MASK).toChar()) {
                '\r' -> {
                    val command = line.toString().trim().ifEmpty { last }
                    line.clear()
                    if (command != null) {
                        last = command
                        commands.trySend(command)
                    }
                }
                '\n' -> Unit
                else -> line.append(c)
            }
        }
    }

    fun close() {
        commands.close()
        job.cancel()
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
