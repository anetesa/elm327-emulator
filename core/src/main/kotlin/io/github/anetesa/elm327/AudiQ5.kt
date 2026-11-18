package io.github.anetesa.elm327

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A 2015 Audi Q5 2.0 TFSI (EA888 Gen2) on a cold start, from a real scan: misfires in cylinders 1 and 2
 * while the catalyst heats up, a lean long-term trim, a weak battery and a permanent small EVAP leak.
 */
@Suppress("MagicNumber") // raw values, exactly as the engine control unit sent them
object AudiQ5 {

    /** Catalyst heating: retarded ignition and a high idle for the first seconds after a cold start. */
    private const val CAT_HEATING_S = 40.0

    /**
     * @param vin the car's VIN; the real one stays out of the repository (local.properties), this one is made up.
     */
    fun coldStart(vin: String = "WAUZZZ8R0FA000000", misfires: Misfires = Misfires.AFTER_AVS, seed: Int = 15): Vehicle {
        val rnd = Random(seed)
        fun heating(t: Double) = t < CAT_HEATING_S
        fun rpm(t: Double) = if (heating(t)) 1296.0 + 30 * sin(t) else 760.0 + 15 * sin(t / 2)
        fun pct(v: Double) = (v * 255 / 100).roundToInt()

        // The coolant starts at 25 °C and approaches 90 °C
        fun coolant(t: Double) = 90 - 65 * exp(-t / 300)
        fun trim(percent: Double) = (128 + percent * 128 / 100).roundToInt()
        return Vehicle(
            bus = Bus.CAN,
            vin = vin,
            pids = mapOf(
                0x04 to { t -> listOf(pct(if (heating(t)) 25.9 else 19.0)) },
                0x05 to { t -> listOf(coolant(t).roundToInt() + 40) },
                0x06 to { _ -> listOf(trim(rnd.nextDouble(-3.0, 3.0))) },
                0x07 to { _ -> listOf(trim(14.8)) },
                0x0B to { t -> listOf(if (heating(t)) 36 else 31) },
                0x0C to { t -> word((rpm(t) * 4).roundToInt()) },
                0x0D to { _ -> listOf(0) },
                // Ignition timing: −17.5° while the catalyst heats, then a normal idle advance
                0x0E to { t -> listOf(((if (heating(t)) -17.5 else 6.0 + rnd.nextDouble(-1.0, 1.0)) + 64).times(2).roundToInt()) },
                0x0F to { _ -> listOf(24 + 40) },
                0x10 to { t -> word(((if (heating(t)) 12.3 else 3.6) * 100).roundToInt()) },
                0x11 to { t -> listOf(pct(if (heating(t)) 16.9 else 12.5)) },
                0x1F to { t -> word(t.roundToInt()) },
                0x21 to { _ -> listOf(0x00, 0x00) },
                0x2F to { _ -> listOf(pct(54.5)) },
                0x30 to { _ -> listOf(6) },
                0x31 to { _ -> word(34) },
                0x33 to { _ -> listOf(99) },
                // 11.8 V on the starter, then the alternator charges
                0x42 to { t -> word(if (t < 3) 11_779 else 14_050) },
                0x46 to { _ -> listOf(19 + 40) },
            ),
            ecuName = "ECM\u0000-EngineControl",
            calibrations = listOf("8R1115L 0005AVCB" to 0xE5EDE92FL, "8R0158AS100321" to 0xB0AE2672L),
            // OBD conditions, ignitions, then completions/conditions: catalyst 1, 2, O2 sensor 1, 2, EGR, air,
            // EVAP, secondary O2 sensor 1, 2
            inUseRatios = listOf(3341, 13_582, 4629, 3287, 0, 0, 4684, 3341, 0, 0, 9451, 3341, 0, 0, 379, 410, 3124, 3341, 0, 0),
            stored = listOf(0x0300, 0x0301, 0x0302),
            permanent = listOf(0x0456, 0x0300, 0x0302),
            // Catalyst, EVAP and oxygen sensor monitors not ready after 34 km and 6 warm-ups
            incompleteMonitors = 0x25,
            monitorTests = misfireTests(misfires),
            // P0300 stored 3 s after a cold start: catalyst heating, timing −17.5°, long-term trim +14.8 %
            freezeCode = 0x0300,
            freezePids = mapOf(
                0x04 to listOf(66),
                0x05 to listOf(25 + 40),
                0x06 to listOf(128),
                0x07 to listOf(147),
                0x0C to word(5186),
                0x0D to listOf(0),
                0x0E to listOf(93),
                0x0F to listOf(24 + 40),
                0x10 to word(1230),
                0x11 to listOf(43),
                0x1F to word(3),
                0x2F to listOf(139),
                0x33 to listOf(99),
                0x42 to word(11_779),
                0x46 to listOf(19 + 40),
            ),
        )
    }

    /** Misfires of cylinders 1 and 2 in one cold start: counts, seconds until they stop, averages. */
    enum class Misfires(val cylinder1: Int, val cylinder2: Int, val untilS: Double, val average1: Int, val average2: Int) {
        /** Before the AVS actuator was replaced: 64 and 327 until the catalyst heating ends at about 40 s (the averages of the scan are kept). */
        BEFORE_AVS(64, 327, CAT_HEATING_S, 4, 6),

        /** After it: 22 and 23 in the first 7 seconds. */
        AFTER_AVS(22, 23, 7.0, 4, 6),
    }

    /**
     * Mode 06 misfire counters: cylinders 1 and 2 misfire during the first seconds after the start and
     * stop, cylinders 3 and 4 are clean.
     */
    private fun misfireTests(m: Misfires): Map<Int, List<MonitorTest>> {
        fun growing(total: Int): (Double) -> Int = { t -> (total * (t / m.untilS).coerceIn(0.0, 1.0)).roundToInt() }
        val cylinder1 = growing(m.cylinder1)
        val cylinder2 = growing(m.cylinder2)
        return mapOf(
            0xA1 to misfires(current = { t -> cylinder1(t) + cylinder2(t) }, average = { m.average1 + m.average2 }),
            0xA2 to misfires(current = cylinder1, average = { m.average1 }),
            0xA3 to misfires(current = cylinder2, average = { m.average2 }),
            0xA4 to misfires(current = { 0 }, average = { 0 }),
            0xA5 to misfires(current = { 0 }, average = { 0 }),
        )
    }

    /** Test 0B is the average over the last cycles, 0C the count in the current one (unit 24: counts). */
    private fun misfires(current: (Double) -> Int, average: (Double) -> Int) = listOf(
        MonitorTest(0x0B, 0x24, 0, 0xFFFF, average),
        MonitorTest(0x0C, 0x24, 0, 0xFFFF, current),
    )
}
