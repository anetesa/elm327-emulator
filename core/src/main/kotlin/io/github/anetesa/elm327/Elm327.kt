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

    /** ATSH: where requests go. 7DF asks every OBD unit; 7E0 is the engine unit alone (manufacturer services). */
    private var requestHeader = FUNCTIONAL_HEADER

    /** ATCRA: the only id the adapter listens to; null — automatic (the OBD replies 7E8–7EF on 11-bit CAN). */
    private var receiveFilter: String? = null

    /** ATFCSH/ATFCSM1: where the flow control frame of a long reply goes; null — automatic ("reply − 8"). */
    private var flowControlHeader: String? = null
    private var flowControlManual = false

    /** ATCP: the priority byte that heads a 29-bit id. */
    private var priority = DEFAULT_PRIORITY

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

        /**
         * A reply from another unit than the engine (the battery): its own CAN id with headers on, and only the
         * first frame of a long reply when the flow control went to the wrong id.
         */
        class Unit(val payload: List<Int>, val canId: String, val firstFrameOnly: Boolean) : Reply
    }

    private fun Reply.render(): List<String> = when (this) {
        is Reply.Text -> lines.toList()
        is Reply.Ecu -> messages.flatMap { if (vehicle.bus == Bus.CAN) canFrames(it) else listOf(kLine(it)) }
        is Reply.Searching -> listOf(SEARCHING) + then.render()
        is Reply.Unit -> canFrames(payload, canId).let { if (firstFrameOnly) it.take(if (headers) 1 else 2) else it }
    }

    private fun respond(cmd: String): Reply = when {
        cmd.startsWith("AT") -> at(cmd)
        cmd.startsWith("ST") -> Reply.Text(if (cmd == "STI") profile.stnId ?: "?" else "?")
        !cmd.isHex() || cmd.length % 2 != 0 -> Reply.Text("?")
        !busMatches() -> Reply.Text(SEARCHING, UNABLE_TO_CONNECT)
        batteryAddressed() -> battery(cmd)
        else -> connect(obd(cmd))
    }

    /** The first request after a reset searches for the protocol; later ones answer at once. */
    private fun connect(reply: Reply): Reply {
        if (connected || protocol != "0") return reply
        // OBD on UDS cars do not answer the classic 01 00 the search is made of, many electric cars no OBD at all
        val silent = vehicle.standard == ObdStandard.J1979_2 || !vehicle.answersObd
        if (silent && reply is Reply.Text) return Reply.Text(SEARCHING, UNABLE_TO_CONNECT)
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
            arg == "AR" || arg == "CRA" -> {
                receiveFilter = null
                OK
            }
            arg.startsWith("CRA") -> {
                receiveFilter = arg.drop(3)
                OK
            }
            arg.startsWith("FCSH") -> {
                flowControlHeader = arg.drop(4)
                OK
            }
            arg.startsWith("FCSM") -> {
                flowControlManual = arg.drop(4) == "1"
                OK
            }
            arg.startsWith("CP") -> {
                priority = arg.drop(2)
                OK
            }
            arg.startsWith("SH") -> when (profile.headers) {
                HeaderSupport.SUPPORTED -> {
                    requestHeader = arg.drop(2)
                    OK
                }
                HeaderSupport.IGNORED -> OK
                HeaderSupport.REJECTED -> Reply.Text("?")
            }
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
        requestHeader = FUNCTIONAL_HEADER
        receiveFilter = null
        flowControlHeader = null
        flowControlManual = false
        priority = DEFAULT_PRIORITY
        protocol = "0"
        connected = false
    }

    private val extendedIds get() = protocol in EXTENDED_PROTOCOLS

    /** The id a request goes to now: on 29-bit CAN the priority (ATCP) and the three header bytes. */
    private val requestId get() = if (extendedIds) priority + requestHeader.takeLast(EXTENDED_HEADER_DIGITS) else requestHeader

    /** The request is addressed to the battery unit, on the kind of ids it uses. */
    private fun batteryAddressed(): Boolean {
        val unit = vehicle.battery ?: return false
        return unit.extended == extendedIds && requestId == unit.requestId
    }

    /**
     * The battery unit answers; the adapter prints it only when it listens on the unit's id, and the whole of
     * a long reply only when its flow control frame reached the unit.
     */
    private fun battery(cmd: String): Reply {
        val unit = vehicle.battery!!
        val heard = receiveFilter?.equals(unit.responseId) ?: (!unit.extended && unit.responseId in AUTO_RECEIVED)
        if (!heard) return NO_DATA
        val payload = unit.answer(cmd, elapsedSeconds())
        val flowControlTo = if (flowControlManual) flowControlHeader else automaticFlowControl(unit.responseId)
        val longReply = payload.size > SINGLE_FRAME_MAX
        return Reply.Unit(payload, unit.responseId, firstFrameOnly = longReply && flowControlTo != unit.requestId)
    }

    /** ELM327 on its own: "reply − 8" on 11-bit CAN, source and target swapped on 29-bit. */
    private fun automaticFlowControl(responseId: String): String =
        if (responseId.length == 3) (responseId.toInt(HEX) - OBD_PAIR_OFFSET).toString(HEX).uppercase()
        else responseId.take(4) + responseId.takeLast(2) + responseId.substring(4, 6)

    private fun obd(cmd: String): Reply {
        // No unit of the car speaks OBD: only the battery unit, on its own address, answers anything
        if (!vehicle.answersObd) return NO_DATA
        val bytes = cmd.chunked(2).map { it.toInt(HEX) }
        // UDS 19 02 to the engine unit: every code it keeps, manufacturer ones included (as VCDS reads them)
        if (cmd.startsWith("1902")) return if (requestHeader == ENGINE_HEADER) manufacturerCodes() else NO_DATA
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

    /** 59 02 availability-mask { code(3) status }*: standard and manufacturer codes, confirmed. */
    /**
     * The unit may first answer "response pending" (7F 19 78) while it collects the codes; the ELM327 keeps
     * listening and prints both messages.
     */
    private fun manufacturerCodes(): Reply = Reply.Ecu(
        List(vehicle.udsResponsePending) { listOf(0x7F, 0x19, 0x78) } + listOf(
            listOf(0x59, 0x02, 0xFF) +
                vehicle.stored.flatMap { dtc3(it) + STATUS_CONFIRMED_ACTIVE } +
                vehicle.manufacturerCodes.flatMap { (code, failure) -> word(code) + failure + STATUS_CONFIRMED_ACTIVE },
        ),
    )

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
    private fun canFrames(payload: List<Int>, canId: String = ECU_CAN_ID): List<String> {
        if (headers) {
            if (payload.size <= SINGLE_FRAME_MAX) return listOf(canFrame(listOf(payload.size) + payload, canId))
            val first = listOf(FIRST_FRAME or (payload.size shr 8), payload.size and 0xFF) + payload.take(FIRST_FRAME_DATA)
            val rest = payload.drop(FIRST_FRAME_DATA).chunked(CONSECUTIVE_FRAME_DATA).mapIndexed { i, chunk ->
                canFrame(listOf(CONSECUTIVE_FRAME or ((i + 1) % HEX)) + chunk, canId)
            }
            return listOf(canFrame(first, canId)) + rest
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

    private fun canFrame(data: List<Int>, canId: String = ECU_CAN_ID): String =
        canId + (if (spaces) " " else "") + hex(data + List(CAN_FRAME_BYTES - data.size) { 0 })

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

        /** ISO 15765-4 with 29-bit ids: 500 and 250 kbit/s. */
        val EXTENDED_PROTOCOLS = setOf("7", "9")
        const val EXTENDED_HEADER_DIGITS = 6
        const val DEFAULT_PRIORITY = "18"
        const val OBD_PAIR_OFFSET = 8

        /** What the adapter hears on 11-bit CAN without ATCRA: the OBD replies. */
        val AUTO_RECEIVED = (0x7E8..0x7EF).map { it.toString(16).uppercase() }.toSet()
        const val ECU_CAN_ID = "7E8"
        const val FUNCTIONAL_HEADER = "7DF"
        const val ENGINE_HEADER = "7E0"

        /** testFailed + confirmed: the fault is present now. */
        const val STATUS_CONFIRMED_ACTIVE = 0x09
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
