package io.github.anetesa.elm327

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A Tesla Model 3 Long Range on its diagnostic cable: no OBD services at all, the battery management unit
 * broadcasts on the vehicle CAN bus and is only heard in monitor mode. Signals and their layouts follow
 * joshwardell/model3dbc and OpenTeslaBuyer (both MIT); the energy message 0x352 changed with firmware, so the
 * car can send the 11-bit layout (2020 firmware) or the multiplexed one (newer firmware).
 */
@Suppress("MagicNumber") // raw signal positions and scales, as the DBC files give them
object TeslaModel3 {

    enum class EnergyLayout { BITS_11, MULTIPLEXED }

    /** 77.8 kWh when new, 70.0 kWh now: 90 % state of health, 72 % charged, brick 53 the weakest. */
    fun longRange(layout: EnergyLayout = EnergyLayout.MULTIPLEXED): Vehicle {
        val newKwh = 77.8
        val fullKwh = 70.0
        val soc = 72.0
        val buffer = 3.1
        val remaining = buffer + (fullKwh - buffer) * soc / 100
        val bricks = 96
        val weak = 52
        fun brick(i: Int, t: Double) = if (i == weak) 3.931 else 3.952 + 0.001 * sin(i + t)
        val packVolts = (0 until bricks).sumOf { brick(it, 0.0) }

        fun energy(): List<List<Int>> = when (layout) {
            EnergyLayout.BITS_11 -> listOf(
                frame(
                    0 to 11 to (fullKwh / 0.1),
                    11 to 11 to (remaining / 0.1),
                    22 to 11 to (remaining / 0.1),
                    33 to 11 to ((remaining - 0.5) / 0.1),
                    44 to 11 to ((fullKwh - remaining) / 0.1),
                    55 to 8 to (buffer / 0.1),
                ),
            )
            EnergyLayout.MULTIPLEXED -> listOf(
                frame(0 to 2 to 0.0, 16 to 16 to (fullKwh / 0.02), 32 to 16 to (remaining / 0.02), 48 to 16 to ((remaining - 0.5) / 0.02)),
                frame(0 to 2 to 1.0, 16 to 16 to (buffer / 0.01), 32 to 16 to (remaining / 0.02), 48 to 16 to ((fullKwh - remaining) / 0.02)),
            )
        }

        return Vehicle(
            bus = Bus.CAN,
            vin = "5YJ3E7EB0LF000000",
            pids = emptyMap(),
            answersObd = false,
            broadcast = mapOf(
                // HV bus: pack voltage, standing current about zero
                0x132 to { _ -> listOf(frame(0 to 16 to (packVolts / 0.01), 16 to 16 to 0.0)) },
                // State of charge: min, displayed, max, average, and the pack's energy when new
                0x292 to { _ -> listOf(frame(0 to 10 to soc * 10 - 3, 10 to 10 to soc * 10, 20 to 10 to soc * 10 + 3, 30 to 10 to soc * 10, 40 to 10 to newKwh * 10, 50 to 8 to 60.0)) },
                0x352 to { _ -> energy() },
                // Page 0 temperatures 22..24.5 °C, page 1 the weakest and strongest brick
                0x332 to { t ->
                    val volts = (0 until bricks).map { brick(it, t) }
                    listOf(
                        frame(0 to 2 to 0.0, 16 to 8 to (24.5 + 40) / 0.5, 24 to 8 to (22.0 + 40) / 0.5),
                        frame(
                            0 to 2 to 1.0,
                            2 to 12 to volts.max() / 0.002,
                            16 to 12 to volts.min() / 0.002,
                            32 to 7 to (volts.indexOf(volts.max())).toDouble(),
                            40 to 7 to weak.toDouble(),
                        ),
                    )
                },
                // Brick voltages: page n carries bricks 3n, 3n + 1, 3n + 2
                0x401 to { t ->
                    (0 until bricks / 3).map { page ->
                        frame(0 to 8 to page.toDouble(), 16 to 16 to brick(page * 3, t) / 0.0001, 32 to 16 to brick(page * 3 + 1, t) / 0.0001, 48 to 16 to brick(page * 3 + 2, t) / 0.0001)
                    }
                },
                // Lifetime energy out of and into the pack, kWh
                0x3D2 to { _ -> listOf(frame(0 to 32 to 21_450_000.0, 32 to 32 to 23_120_000.0)) },
            ),
        )
    }

    /** An eight-byte little-endian frame from (start bit to length) to raw value. */
    private fun frame(vararg signals: Pair<Pair<Int, Int>, Double>): List<Int> {
        var word = 0L
        for ((position, value) in signals) {
            val (start, length) = position
            val mask = (1L shl length) - 1
            word = word and (mask shl start).inv() or ((value.roundToInt().toLong() and mask) shl start)
        }
        return (0 until 8).map { (word ushr (8 * it) and 0xFF).toInt() }
    }
}
