package io.github.anetesa.elm327

/** The car's network as ELM327 reports it (ATDPN number). */
enum class Bus(val protocolNumber: String, val protocolName: String) {
    /** ISO 15765-4 CAN, 11-bit IDs, 500 kbit/s: most cars since 2008. */
    CAN("6", "ISO 15765-4 (CAN 11/500)"),

    /** ISO 9141-2 (K-Line): older European and Asian cars. */
    K_LINE("3", "ISO 9141-2"),
}

/** Which on-board diagnostics standard the engine control unit speaks. */
enum class ObdStandard {
    /** SAE J1979: services 01–0A, every car since the 2000s. */
    J1979,

    /** SAE J1979-2 (OBD on UDS): CAN only, the classic services are not answered. */
    J1979_2,
}

/** A Mode 06 monitor test: raw 16-bit values, as the engine control unit reports them. */
class MonitorTest(val tid: Int, val unitAndScaling: Int, val min: Int, val max: Int, val value: (t: Double) -> Int)

/**
 * The emulated car: bus, VIN, live values over time and trouble codes. Values are the raw bytes of
 * the SAE J1979 replies; [t] is the time in seconds since the car "started".
 */
class Vehicle(
    val bus: Bus,
    val vin: String?,
    val standard: ObdStandard = ObdStandard.J1979,
    private val pids: Map<Int, (t: Double) -> List<Int>>,
    stored: List<Int> = emptyList(),
    pending: List<Int> = emptyList(),
    val permanent: List<Int> = emptyList(),
    /** Mode 06 tests by monitor id (MID). */
    private val monitorTests: Map<Int, List<MonitorTest>> = emptyMap(),
    /** Freeze frame: the code that stored it and the raw PID bytes at that moment. */
    private val freezeCode: Int? = null,
    private val freezePids: Map<Int, List<Int>> = emptyMap(),
    /** Mode 09: control unit name and its software — calibration ids with their check numbers. */
    val ecuName: String? = null,
    val calibrations: List<Pair<String, Long>> = emptyList(),
    /** In-use performance ratios (Mode 09, InfoType 08): 16-bit counters; clearing codes keeps them. */
    val inUseRatios: List<Int> = emptyList(),
    /** Byte D of PID 01: the spark monitors not yet complete (same bits as [SPARK_MONITORS]). */
    private val incompleteMonitors: Int = 0x00,
    /**
     * Codes the engine unit keeps but does not report in modes 03/07/0A: the code and its failure type,
     * answered only to UDS 19 02 sent to the engine unit (P11A6 00 on an Audi).
     */
    val manufacturerCodes: List<Pair<Int, Int>> = emptyList(),
    /** How many "response pending" (7F 19 78) the engine unit sends before its UDS 19 02 answer. */
    val udsResponsePending: Int = 0,
    /**
     * Whether any unit answers the OBD services. Many electric cars (Nissan Leaf, VW ID.4) do not: the
     * protocol search fails, and only their own units answer manufacturer requests.
     */
    val answersObd: Boolean = true,
    /** The high-voltage battery's management unit of an electric car. */
    val battery: BatteryUnit? = null,
) {
    var stored: List<Int> = stored
        private set
    var pending: List<Int> = pending
        private set
    private var cleared = false

    /** Raw bytes of [pid] at [t] seconds, or null when the car does not support it. */
    fun pidBytes(pid: Int, t: Double): List<Int>? = when {
        pid % BLOCK == 0 -> maskOf(pids.keys + 0x01, pid)
        // After clearing codes the monitors are incomplete and the "since clear" counters restart
        pid == 0x01 -> listOf(
            (if (stored.isNotEmpty()) MIL_ON else 0) or stored.size,
            if (cleared) COMMON_INCOMPLETE else COMMON_COMPLETE,
            SPARK_MONITORS,
            if (cleared) SPARK_MONITORS else incompleteMonitors,
        )
        cleared && pid in SINCE_CLEAR_PIDS -> pids[pid]?.invoke(t)?.map { 0 }
        else -> pids[pid]?.invoke(t)
    }

    /** Mode 06: the support mask of a block (00, 20, …) or the records `MID TID UAS VALUE MIN MAX`. */
    fun monitorBytes(mid: Int, t: Double): List<Int>? {
        if (mid % BLOCK == 0) return maskOf(monitorTests.keys, mid)
        return monitorTests[mid]?.flatMap { test ->
            listOf(mid, test.tid, test.unitAndScaling) + word(test.value(t)) + word(test.min) + word(test.max)
        }
    }

    /** Mode 02: PID bytes of the freeze frame. PID 02 is the code that stored it (0000 when none). */
    fun freezeBytes(pid: Int): List<Int>? {
        val code = freezeCode?.takeIf { it in stored }
        return when {
            pid == FREEZE_DTC_PID -> word(code ?: 0)
            code == null -> null
            pid % BLOCK == 0 -> maskOf(freezePids.keys + FREEZE_DTC_PID, pid)
            else -> freezePids[pid]
        }
    }

    /** Mode 04: clears stored and pending codes; permanent ones stay until the car's own self-test. */
    fun clearCodes() {
        stored = emptyList()
        pending = emptyList()
        cleared = true
    }

    /** SAE J1979 support mask: one bit per number, the block's last bit means "the next block exists". */
    private fun maskOf(supported: Set<Int>, base: Int): List<Int>? {
        // Every block up to the highest number: an empty block in between still has its "next" bit
        val last = supported.maxOrNull() ?: 0
        val all = supported + (BLOCK..((last - 1) / BLOCK * BLOCK) step BLOCK)
        if (base != 0 && base !in all) return null
        var mask = 0L
        for (i in 1..BLOCK) if ((base + i) in all) mask = mask or (1L shl (BLOCK - i))
        return (3 downTo 0).map { ((mask shr (it * 8)) and 0xFF).toInt() }
    }

    private companion object {
        const val BLOCK = 0x20
        const val MIL_ON = 0x80

        /** Byte B of the status: misfire, fuel system and components monitors — complete / incomplete. */
        const val COMMON_COMPLETE = 0x07
        const val COMMON_INCOMPLETE = 0x77

        /** Catalyst, evaporative system, oxygen sensors and their heaters, EGR. */
        const val SPARK_MONITORS = 0xE5
        val SINCE_CLEAR_PIDS = setOf(0x30, 0x31, 0x4E)
        const val FREEZE_DTC_PID = 0x02
    }
}

internal fun word(v: Int) = listOf(v shr 8 and 0xFF, v and 0xFF)
