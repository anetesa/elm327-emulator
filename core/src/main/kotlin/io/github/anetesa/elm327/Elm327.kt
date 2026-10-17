package io.github.anetesa.elm327

import java.util.Locale
import kotlin.time.TimeSource

/** The emulated link went down (see [AdapterProfile.dropAfterCommands]). */
class LinkDroppedException(message: String) : Exception(message)

/**
 * An ELM327 command interpreter with a car behind it. It takes one command at a time and returns
 * exactly what the chip would send back: the echo, the reply lines and the `>` prompt, honouring
 * the echo (ATE), linefeed (ATL), space (ATS) and header (ATH) settings.
 *
 * OBD replies are built as messages from the engine control unit and then formatted for the bus:
 * ISO 15765-4 single and multi-frame (ISO-TP) on CAN, one line per message on K-Line.
 */
class Elm327(
    private val profile: AdapterProfile,
    private val vehicle: Vehicle,
    private val elapsedSeconds: () -> Double = monotonicSeconds(),
) {
    private var echo = true
    private var linefeeds = false
    private var spaces = true
    private var headers = false

    /** "0" is automatic search; otherwise the protocol number set with ATSP. */
    private var protocol = "0"
    private var connected = false
    private var commands = 0

    /** Runs one command (without the trailing carriage return) and returns the adapter's reply. */
    fun execute(command: String): String {
        commands++
        val limit = profile.dropAfterCommands
        if (limit != null && commands > limit) throw LinkDroppedException("Link dropped after $limit commands")
        val typed = command.trim()
        val echoed = echo
        val reply = respond(typed.uppercase().replace(" ", ""))
        val lines = reply.render().let { text ->
            if (text.sumOf { it.length } > profile.bufferLimitChars) listOf(BUFFER_FULL) else text
        }
        val eol = if (linefeeds) "\r\n" else "\r"
        return (if (echoed) typed + eol else "") + lines.joinToString(eol) + eol + eol + ">"
    }

    private sealed interface Reply {
        class Text(vararg val lines: String) : Reply

        /** Messages from the engine control unit, as payload bytes. */
        class Ecu(val messages: List<List<Int>>) : Reply {
            constructor(vararg bytes: Int) : this(listOf(bytes.toList()))
        }

        /** Shown before the reply while the adapter looks for the car's protocol. */
        class Searching(val then: Reply) : Reply
    }

    private fun Reply.render(): List<String> = when (this) {
        is Reply.Text -> lines.toList()
        is Reply.Ecu -> messages.flatMap { if (vehicle.bus == Bus.CAN) canFrames(it) else listOf(kLine(it)) }
        is Reply.Searching -> listOf(SEARCHING) + then.render()
    }

    private fun respond(cmd: String): Reply = when {
        cmd.startsWith("AT") -> at(cmd)
        cmd.startsWith("ST") -> Reply.Text(if (cmd == "STI") profile.stnId ?: "?" else "?")
        !cmd.isHex() || cmd.length % 2 != 0 -> Reply.Text("?")
        !busMatches() -> Reply.Text(SEARCHING, UNABLE_TO_CONNECT)
        else -> connect(obd(cmd))
    }

    /** The first request after a reset searches for the protocol; later ones answer at once. */
    private fun connect(reply: Reply): Reply {
        if (connected || protocol != "0") return reply
        // OBD on UDS cars do not answer the classic 01 00 the search is made of
        if (vehicle.standard == ObdStandard.J1979_2 && reply is Reply.Text) return Reply.Text(SEARCHING, UNABLE_TO_CONNECT)
        connected = true
        return Reply.Searching(reply)
    }

    private fun busMatches() = when (protocol) {
        "0" -> true
        in CAN_PROTOCOLS -> vehicle.bus == Bus.CAN
        else -> vehicle.bus == Bus.K_LINE
    }

    private fun at(cmd: String): Reply {
        if (cmd in profile.rejectedAt) return Reply.Text("?")
        val arg = cmd.drop(2)
        return when {
            arg == "Z" || arg == "WS" -> {
                reset()
                Reply.Text(profile.banner)
            }
            arg == "D" -> {
                reset()
                OK
            }
            arg == "I" -> Reply.Text(profile.banner)
            arg == "@1" -> Reply.Text(profile.description ?: "?")
            arg == "RV" -> Reply.Text(String.format(Locale.ROOT, "%.1fV", VOLTAGE + (commands % 3) * 0.1))
            arg == "DP" -> Reply.Text((if (protocol == "0") "AUTO, " else "") + vehicle.bus.protocolName)
            arg == "DPN" -> Reply.Text((if (protocol == "0") "A" else "") + vehicle.bus.protocolNumber)
            arg.startsWith("SP") || arg.startsWith("TP") -> {
                protocol = arg.drop(2).removePrefix("A").ifEmpty { "0" }
                connected = false
                OK
            }
            arg.length == 2 && arg[1] in "01" -> setFlag(arg[0], arg[1] == '1')
            else -> OK
        }
    }

    private fun setFlag(flag: Char, on: Boolean): Reply {
        when (flag) {
            'E' -> echo = on
            'L' -> linefeeds = on
            'S' -> spaces = on
            'H' -> headers = on
        }
        return OK
    }

    private fun reset() {
        echo = true
        linefeeds = false
        spaces = true
        headers = false
        protocol = "0"
        connected = false
    }

    private fun obd(cmd: String): Reply {
        val bytes = cmd.chunked(2).map { it.toInt(HEX) }
        if (vehicle.standard == ObdStandard.J1979_2) return uds(cmd, bytes)
        val mode = bytes[0]
        val pid = bytes.getOrNull(1)
        return when {
            mode == 0x01 && pid != null -> mode01(bytes.drop(1))
            mode == 0x02 && bytes.size == 3 && pid != null -> vehicle.freezeBytes(pid)?.let { Reply.Ecu(listOf(listOf(0x42, pid, 0x00) + it)) } ?: NO_DATA
            cmd == "03" -> dtcs(0x43, vehicle.stored)
            cmd == "07" -> dtcs(0x47, vehicle.pending)
            cmd == "0A" -> dtcs(0x4A, vehicle.permanent)
            cmd == "04" -> {
                vehicle.clearCodes()
                Reply.Ecu(0x44)
            }
            mode == 0x06 && bytes.size == 2 && pid != null -> mode06(pid)
            cmd == "0902" -> vin()
            mode == 0x09 && bytes.size == 2 && pid != null -> mode09(pid)
            else -> NO_DATA
        }
    }

    private fun mode01(requested: List<Int>): Reply {
        // Fake clones cut the request; K-Line does not support multi-PID requests at all
        val groupAllowed = profile.multiPid && vehicle.bus == Bus.CAN
        val parts = requested.take(if (groupAllowed) MAX_PIDS else 1)
            .mapNotNull { pid -> vehicle.pidBytes(pid, elapsedSeconds())?.let { listOf(pid) + it } }
        if (parts.isEmpty()) return NO_DATA
        return Reply.Ecu(listOf(listOf(0x41) + parts.flatten()))
    }

    /** Mode 06 in the CAN format of SAE J1979; the K-Line format differs and is not emulated. */
    private fun mode06(mid: Int): Reply {
        if (vehicle.bus != Bus.CAN) return NO_DATA
        val bytes = vehicle.monitorBytes(mid, elapsedSeconds()) ?: return NO_DATA
        return Reply.Ecu(listOf(listOf(0x46) + (if (mid % 0x20 == 0) listOf(mid) else emptyList()) + bytes))
    }

    private fun dtcs(mode: Int, codes: List<Int>): Reply {
        val bytes = codes.flatMap(::word)
        return when (vehicle.bus) {
            Bus.CAN -> Reply.Ecu(listOf(listOf(mode, codes.size) + bytes))
            // K-Line: three codes per message, padded with zeros
            Bus.K_LINE -> Reply.Ecu(
                bytes.chunked(KLINE_DTC_BYTES).ifEmpty { listOf(emptyList()) }.map { chunk ->
                    listOf(mode) + chunk + List(KLINE_DTC_BYTES - chunk.size) { 0 }
                },
            )
        }
    }

    private fun vin(): Reply {
        val ascii = vehicle.vin?.map { it.code } ?: return NO_DATA
        return when (vehicle.bus) {
            Bus.CAN -> Reply.Ecu(listOf(listOf(0x49, 0x02, 0x01) + ascii))
            // K-Line: five messages of four bytes, the first one starts with three padding bytes
            Bus.K_LINE -> Reply.Ecu(
                (List(KLINE_VIN_PAD) { 0 } + ascii).chunked(KLINE_LINE_BYTES).mapIndexed { i, chunk ->
                    listOf(0x49, 0x02, i + 1) + chunk
                },
            )
        }
    }

    /** Mode 09: calibration ids (04), their check numbers (06), in-use ratios (08), control unit name (0A). */
    private fun mode09(type: Int): Reply {
        val calibrations = vehicle.calibrations
        val (count, bytes) = when (type) {
            0x04 -> calibrations.size to calibrations.flatMap { ascii(it.first, CALID_BYTES) }
            0x06 -> calibrations.size to calibrations.flatMap { bytes32(it.second) }
            0x08 -> vehicle.inUseRatios.size to vehicle.inUseRatios.flatMap(::word)
            0x0A -> 1 to vehicle.ecuName?.let { ascii(it, ECU_NAME_BYTES) }.orEmpty()
            else -> 0 to emptyList()
        }
        return when {
            bytes.isEmpty() -> NO_DATA
            vehicle.bus == Bus.CAN -> Reply.Ecu(listOf(listOf(0x49, type, count) + bytes))
            else -> Reply.Ecu(bytes.chunked(KLINE_LINE_BYTES).mapIndexed { i, chunk -> listOf(0x49, type, i + 1) + chunk })
        }
    }

    /**
     * SAE J1979-2 (OBD on UDS, ISO 14229-1): 22 F4xx live data, 22 F802 VIN, 22 F810 protocol id,
     * 19 42 codes by status mask (WWH-OBD), 19 55 permanent codes, 14 FFFF33 clear.
     */
    private fun uds(cmd: String, bytes: List<Int>): Reply = when {
        cmd == "22F810" -> Reply.Ecu(0x62, 0xF8, 0x10, 0x01)
        cmd == "22F802" -> vehicle.vin?.let { vin -> Reply.Ecu(listOf(listOf(0x62, 0xF8, 0x02) + vin.map { it.code })) } ?: NO_DATA
        cmd.startsWith("22F4") -> udsLive(bytes.drop(1).chunked(2).filter { it.size == 2 && it[0] == 0xF4 }.map { it[1] })
        cmd.startsWith("194233") -> udsByMask(if (bytes.getOrNull(3) == STATUS_CONFIRMED) vehicle.stored else vehicle.pending)
        cmd == "195533" -> Reply.Ecu(listOf(listOf(0x59, 0x55, 0x33, 0xFF, 0x01) + vehicle.permanent.flatMap { dtc3(it) + STATUS_CONFIRMED }))
        cmd == "14FFFF33" -> {
            vehicle.clearCodes()
            Reply.Ecu(0x54)
        }
        else -> NO_DATA
    }

    private fun udsLive(requested: List<Int>): Reply {
        val parts = requested.take(if (profile.multiPid) MAX_PIDS else 1)
            .mapNotNull { pid -> vehicle.pidBytes(pid, elapsedSeconds())?.let { listOf(0xF4, pid) + it } }
        return if (parts.isEmpty()) NO_DATA else Reply.Ecu(listOf(listOf(0x62) + parts.flatten()))
    }

    private fun udsByMask(codes: List<Int>): Reply = Reply.Ecu(
        listOf(listOf(0x59, 0x42, 0x33, 0xFF, 0xFF, 0x01) + codes.flatMap { listOf(SEVERITY_CHECK_SOON) + dtc3(it) + STATUS_CONFIRMED }),
    )

    /** A three-byte SAE J2012 code: the two code bytes and the failure type (00 — none). */
    private fun dtc3(code: Int) = word(code) + 0x00

    /**
     * ISO-TP as ELM327 prints it. Headers off: a single frame is just the payload; a longer message
     * is its length and numbered lines "0:", "1:" … Headers on: every CAN frame with its ID 7E8,
     * protocol control byte and padding to eight bytes.
     */
    private fun canFrames(payload: List<Int>): List<String> {
        if (headers) {
            if (payload.size <= SINGLE_FRAME_MAX) return listOf(canFrame(listOf(payload.size) + payload))
            val first = listOf(FIRST_FRAME or (payload.size shr 8), payload.size and 0xFF) + payload.take(FIRST_FRAME_DATA)
            val rest = payload.drop(FIRST_FRAME_DATA).chunked(CONSECUTIVE_FRAME_DATA).mapIndexed { i, chunk ->
                canFrame(listOf(CONSECUTIVE_FRAME or ((i + 1) % HEX)) + chunk)
            }
            return listOf(canFrame(first)) + rest
        }
        if (payload.size <= SINGLE_FRAME_MAX) return listOf(hex(payload))
        val separator = if (spaces) " " else ""
        val lines = mutableListOf(payload.size.toString(HEX).uppercase().padStart(3, '0'))
        lines += "0:$separator" + hex(payload.take(FIRST_FRAME_DATA))
        payload.drop(FIRST_FRAME_DATA).chunked(CONSECUTIVE_FRAME_DATA).forEachIndexed { i, chunk ->
            lines += ((i + 1) % HEX).toString(HEX).uppercase() + ":" + separator + hex(chunk)
        }
        return lines
    }

    private fun canFrame(data: List<Int>): String =
        ECU_CAN_ID + (if (spaces) " " else "") + hex(data + List(CAN_FRAME_BYTES - data.size) { 0 })

    /** ISO 9141-2 message: with headers on, the priority/target/source bytes and the checksum. */
    private fun kLine(payload: List<Int>): String {
        if (!headers) return hex(payload)
        val message = KLINE_HEADER + payload
        return hex(message + (message.sum() and 0xFF))
    }

    private fun hex(bytes: List<Int>) = bytes.joinToString(if (spaces) " " else "") { it.toString(HEX).uppercase().padStart(2, '0') }

    private fun String.isHex() = isNotEmpty() && all { it in '0'..'9' || it in 'A'..'F' }

    private companion object {
        val OK = Reply.Text("OK")
        val NO_DATA = Reply.Text("NO DATA")
        const val SEARCHING = "SEARCHING..."
        const val UNABLE_TO_CONNECT = "UNABLE TO CONNECT"
        const val BUFFER_FULL = "BUFFER FULL"
        val CAN_PROTOCOLS = setOf("6", "7", "8", "9")
        const val ECU_CAN_ID = "7E8"
        val KLINE_HEADER = listOf(0x48, 0x6B, 0x10)
        const val VOLTAGE = 13.9
        const val HEX = 16
        const val MAX_PIDS = 6
        const val CAN_FRAME_BYTES = 8
        const val SINGLE_FRAME_MAX = 7
        const val FIRST_FRAME = 0x10
        const val CONSECUTIVE_FRAME = 0x20
        const val FIRST_FRAME_DATA = 6
        const val CONSECUTIVE_FRAME_DATA = 7
        const val KLINE_DTC_BYTES = 6
        const val KLINE_VIN_PAD = 3
        const val KLINE_LINE_BYTES = 4
        const val CALID_BYTES = 16
        const val ECU_NAME_BYTES = 20
        const val STATUS_CONFIRMED = 0x08
        const val SEVERITY_CHECK_SOON = 0x40
    }
}

/** ASCII padded with zeros to [length] bytes, as calibration ids and the unit name are sent. */
private fun ascii(text: String, length: Int): List<Int> {
    val codes = text.map { it.code }.take(length)
    return codes + List(length - codes.size) { 0 }
}

private fun bytes32(v: Long) = (3 downTo 0).map { (v shr (it * 8) and 0xFF).toInt() }

private fun monotonicSeconds(): () -> Double {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds / 1000.0 }
}
