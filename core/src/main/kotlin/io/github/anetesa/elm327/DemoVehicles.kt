package io.github.anetesa.elm327

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Ready-made cars for the emulator. */
@Suppress("MagicNumber") // raw values, exactly as an engine control unit sends them
object DemoVehicles {

    /**
     * A petrol car warming up at idle with a lean mixture (P0171) and random misfires (P0300):
     * live data, readiness, freeze frame, Mode 06 tests, VIN, calibration ids and in-use ratios.
     */
    fun petrol(bus: Bus = Bus.CAN, standard: ObdStandard = ObdStandard.J1979, seed: Int = 42): Vehicle {
        val rnd = Random(seed)
        fun rpm(t: Double) = 850 + 400 * (1 + sin(t / 4))
        fun pct(v: Double) = (v * 255 / 100).roundToInt()
        return Vehicle(
            bus = bus,
            vin = "1G4JC5444R7252367",
            standard = standard,
            pids = mapOf(
                0x04 to { t -> listOf(pct(22 + 10 * sin(t / 4))) },
                0x05 to { t -> listOf(minOf(90.0, 40 + t).roundToInt() + 40) },
                0x06 to { _ -> listOf(128 + 12 + rnd.nextInt(-3, 4)) },
                0x07 to { _ -> listOf(128 + 18) },
                0x0B to { _ -> listOf(32 + rnd.nextInt(0, 6)) },
                0x0C to { t -> word((rpm(t) * 4).roundToInt()) },
                0x0D to { _ -> listOf(0) },
                0x0E to { _ -> listOf(((10 + rnd.nextDouble(-2.0, 2.0) + 64) * 2).roundToInt()) },
                0x0F to { _ -> listOf(28 + 40) },
                0x10 to { t -> word((rpm(t) / 300 * 100).roundToInt()) },
                0x11 to { _ -> listOf(pct(14.0)) },
                0x1F to { t -> word(t.roundToInt()) },
                0x21 to { _ -> listOf(0x00, 0x7B) },
                0x2F to { _ -> listOf(pct(41.0)) },
                0x30 to { _ -> listOf(46) },
                0x31 to { _ -> listOf(0x03, 0x20) },
                0x33 to { _ -> listOf(100) },
                0x42 to { _ -> word(13_900) },
                0x46 to { _ -> listOf(18 + 40) },
                0x4E to { _ -> word(1310) },
                0x5C to { t -> listOf(minOf(95.0, 35 + t).roundToInt() + 40) },
            ),
            ecuName = "ECM\u0000-EngineControl",
            calibrations = listOf("12653917" to 0x5C3E91A2L, "12650412" to 0x0B7F44D1L),
            // 420 drives, 1310 ignitions; the evaporative system is checked rarely — 6 times out of 420
            inUseRatios = listOf(420, 1310, 38, 420, 0, 0, 120, 420, 0, 0, 0, 0, 0, 0, 6, 420, 110, 420, 0, 0),
            stored = listOf(0x0171, 0x0300),
            pending = listOf(0x0171),
            monitorTests = monitorTests(),
            // P0171 was stored on a warm engine under load: 2400 rpm, 72 km/h, fuel trims +20 % / +18 %
            freezeCode = 0x0171,
            freezePids = mapOf(
                0x04 to listOf(pct(62.0)),
                0x05 to listOf(88 + 40),
                0x06 to listOf(128 + 26),
                0x07 to listOf(128 + 23),
                0x0B to listOf(71),
                0x0C to word(2400 * 4),
                0x0D to listOf(72),
                0x0F to listOf(31 + 40),
                0x10 to word(1840),
                0x11 to listOf(pct(38.0)),
            ),
        )
    }

    /**
     * Oxygen sensors and their heaters, catalyst, variable valve timing, fuel system (out of range,
     * like P0171) and misfire counters of 4 cylinders (growing in cylinders 1 and 3).
     */
    private fun monitorTests(): Map<Int, List<MonitorTest>> = mapOf(
        0x01 to listOf(MonitorTest(0x05, 0x10, 0, 150) { 68 }, MonitorTest(0x06, 0x10, 0, 150) { 74 }),
        0x02 to listOf(MonitorTest(0x07, 0x0B, 0, 400) { 82 }, MonitorTest(0x08, 0x0B, 600, 1275) { 836 }),
        0x21 to listOf(MonitorTest(0x80, 0x20, 0, 0xB0) { 0x62 }),
        0x35 to listOf(MonitorTest(0x81, 0x9C, 0xFC18, 1000) { 0xFF6A }),
        0x41 to listOf(MonitorTest(0x81, 0x14, 1, 10) { 4 }),
        0x81 to listOf(MonitorTest(0x80, 0xAF, 0xF830, 2000) { 2350 }),
        0xA1 to misfires(current = { 9 }, average = { t -> 3 + (t / 5).toInt() }),
        0xA2 to misfires(current = { 0 }, average = { 0 }),
        0xA3 to misfires(current = { 8 }, average = { t -> 3 + (t / 5).toInt() }),
        0xA4 to misfires(current = { 1 }, average = { 0 }),
        0xA5 to misfires(current = { 0 }, average = { 0 }),
    )

    private fun misfires(current: (Double) -> Int, average: (Double) -> Int) = listOf(
        MonitorTest(0x0B, 0x24, 0, 0xFFFF, current),
        MonitorTest(0x0C, 0x24, 0, 0xFFFF, average),
    )
}
