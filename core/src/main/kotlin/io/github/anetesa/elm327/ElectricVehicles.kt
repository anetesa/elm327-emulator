package io.github.anetesa.elm327

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Electric cars whose battery is read through its management unit. Byte layouts follow the open OVMS
 * project (vehicle_nissanleaf, vehicle_kianiroev, vehicle_hyundai_ioniq5; MIT) and community PID lists
 * for VW MEB; the values are made up but plausible for a used car, with one weaker cell in each pack.
 */
@Suppress("MagicNumber") // raw bytes, laid out as the battery units send them
object ElectricVehicles {

    /** What the battery looks like; voltages in V, temperatures in °C. */
    class Pack(
        val stateOfCharge: Double,
        val stateOfHealth: Double,
        val cells: Int,
        val cellVolts: Double,
        val weakCell: Int,
        val weakCellVolts: Double,
        val temperatures: List<Int>,
    ) {
        fun cell(i: Int, t: Double) = if (i == weakCell) weakCellVolts else cellVolts + 0.002 * sin(i + t / 30)
        val packVolts get() = cellVolts * cells
    }

    // --- Nissan Leaf: LBC 79B → 7BB, KWP groups 21 01 (pack), 02 (cells), 04 (temperatures), 61 (SOH, ZE1)

    /** Nissan Leaf 30 kWh (AZE0, 2016): no OBD at all; SOH only as an estimate from Ah (80 Ah when new). */
    fun leaf2016() = leaf(
        vin = "SJNFAAZE0U6000000",
        pack = Pack(62.5, 89.5, 96, 3.94, 37, 3.902, listOf(14, 15, 15, 14)),
        newAh = 80.0,
        ze1 = false,
    )

    /** Nissan Leaf 40 kWh (ZE1, 2019): reports its SOH itself in group 61. */
    fun leaf2019() = leaf(
        vin = "SJNFAAZE1U0000000",
        pack = Pack(80.4, 93.0, 96, 4.02, 61, 3.991, listOf(18, 19, 19, 18)),
        newAh = 115.0,
        ze1 = true,
    )

    private fun leaf(vin: String, pack: Pack, newAh: Double, ze1: Boolean): Vehicle {
        fun group1(): List<Int> {
            val r = MutableList(if (ze1) 51 else 41) { 0xFF }
            put(r, 18, 2, (pack.packVolts * 100).roundToInt())
            put(r, 20, 2, 12_600) // 12 V battery, mV
            val ah = newAh * pack.stateOfHealth / 100
            if (ze1) {
                put(r, 28, 2, (pack.stateOfHealth * 102.4).roundToInt())
                put(r, 31, 3, (pack.stateOfCharge * 10_000).roundToInt())
                put(r, 35, 3, (ah * 10_000).roundToInt())
            } else {
                put(r, 26, 2, (pack.stateOfHealth * 100).roundToInt())
                put(r, 29, 3, (pack.stateOfCharge * 10_000).roundToInt())
                put(r, 33, 3, (ah * 10_000).roundToInt())
            }
            return listOf(0x61, 0x01) + r
        }
        val temperatures = pack.temperatures.flatMap { listOf(0x02, 0x50, it) }.toMutableList().apply {
            this[8] = 0xFF // the third sensor is absent on these packs
            addAll(listOf(pack.temperatures.first(), 0x00))
        }
        return Vehicle(
            bus = Bus.CAN,
            vin = vin,
            pids = emptyMap(),
            answersObd = false,
            battery = BatteryUnit(
                requestId = "79B",
                responseId = "7BB",
                answers = mapOf<String, (Double) -> List<Int>>(
                    "2101" to { _ -> group1() },
                    "2102" to { t ->
                        listOf(0x61, 0x02) + (0 until pack.cells).flatMap { word((pack.cell(it, t) * 1000).roundToInt()) } +
                            word((pack.packVolts * 100).roundToInt()) + word((pack.packVolts * 100).roundToInt())
                    },
                    "2104" to { _ -> listOf(0x61, 0x04) + temperatures },
                ) + if (ze1) mapOf<String, (Double) -> List<Int>>("2161" to { _ -> listOf(0x61, 0x61, 0x00, 0x00) + word((pack.stateOfHealth * 100).roundToInt()) }) else emptyMap(),
            ),
        )
    }

    // --- Hyundai / Kia: BMS 7E4 → 7EC, UDS 22 01 01 (pack), 05 (SOH), 02–04 and 0A–0C (32 cells each)

    /** Hyundai Kona Electric 64 kWh (2020): answers OBD (fuel type "electric"), 98 cells. */
    fun konaElectric() = hyundaiKia(
        vin = "KMHK581G0LU000000",
        pack = Pack(71.0, 94.2, 98, 3.92, 50, 3.88, listOf(21, 22, 22, 21)),
        cellPages = listOf("02", "03", "04"),
    )

    /** Hyundai Ioniq 5 77 kWh (2022, E-GMP): 192 cells on six pages. */
    fun ioniq5() = hyundaiKia(
        vin = "KMHKN81EFNU000000",
        pack = Pack(55.0, 97.1, 192, 3.81, 140, 3.78, listOf(24, 25, 25, 24)),
        cellPages = listOf("02", "03", "04", "0A", "0B", "0C"),
    )

    private fun hyundaiKia(vin: String, pack: Pack, cellPages: List<String>): Vehicle {
        fun main(t: Double): List<Int> {
            val d = MutableList(54) { 0 }
            d[4] = (pack.stateOfCharge * 2).roundToInt()
            put(d, 10, 2, 0xFFF6) // −1.0 A: standing, the 12 V system draws a little
            put(d, 12, 2, (pack.packVolts * 10).roundToInt())
            d[14] = pack.temperatures.max()
            d[15] = pack.temperatures.min()
            pack.temperatures.forEachIndexed { i, v -> d[16 + i] = v }
            val volts = (0 until pack.cells).map { pack.cell(it, t) }
            d[23] = (volts.max() * 50).roundToInt()
            d[24] = volts.indexOf(volts.max()) + 1
            d[25] = (volts.min() * 50).roundToInt()
            d[26] = pack.weakCell + 1
            return listOf(0x62, 0x01, 0x01) + d
        }
        fun health(): List<Int> {
            val d = MutableList(46) { 0 }
            put(d, 25, 2, (pack.stateOfHealth * 10).roundToInt())
            d[31] = (pack.stateOfCharge * 2).roundToInt()
            return listOf(0x62, 0x01, 0x05) + d
        }
        fun page(index: Int, id: String): (Double) -> List<Int> = { t ->
            val first = index * 32
            val present = (pack.cells - first).coerceIn(0, 32)
            val mask = if (present == 32) 0xFFFFFFFFL else (0xFFFFFFFFL shl (32 - present)) and 0xFFFFFFFFL
            listOf(0x62, 0x01, id.toInt(16)) + (3 downTo 0).map { (mask shr (it * 8) and 0xFF).toInt() } +
                (0 until 32).map { i -> if (i < present) (pack.cell(first + i, t) * 50).roundToInt() else 0 }
        }
        return Vehicle(
            bus = Bus.CAN,
            vin = vin,
            pids = mapOf(
                0x0D to { _ -> listOf(0) },
                0x42 to { _ -> word(14_200) },
                0x46 to { _ -> listOf(8 + 40) },
                0x51 to { _ -> listOf(0x08) }, // fuel type: electric
                0xA6 to { _ -> listOf(0x00, 0x08, 0x5A, 0x5C) }, // 54 653.2 km
            ),
            battery = BatteryUnit(
                requestId = "7E4",
                responseId = "7EC",
                answers = mapOf<String, (Double) -> List<Int>>("220101" to { t -> main(t) }, "220105" to { _ -> health() }) +
                    cellPages.mapIndexed { i, id -> "2201$id" to page(i, id) },
            ),
        )
    }

    // --- VW MEB: BMS on 29-bit ids 17FC007B → 17FE007B; over OBD only charge and voltage are known

    /** VW ID.4 77 kWh (2021): no OBD answers on 11-bit, the battery unit on 29-bit ids. */
    fun id4(): Vehicle {
        val pack = Pack(64.0, 0.0, 96, 3.85, 0, 3.85, emptyList())
        return Vehicle(
            bus = Bus.CAN,
            vin = "WVGZZZE2ZMP000000",
            pids = emptyMap(),
            answersObd = false,
            battery = BatteryUnit(
                requestId = "17FC007B",
                responseId = "17FE007B",
                answers = mapOf<String, (Double) -> List<Int>>(
                    "22028C" to { _ -> listOf(0x62, 0x02, 0x8C, (pack.stateOfCharge * 2.5).roundToInt()) },
                    "221E3B" to { _ -> listOf(0x62, 0x1E, 0x3B) + word((pack.packVolts * 4).roundToInt()) },
                ),
            ),
        )
    }

    /** Writes [value] big-endian into [size] bytes at [at]. */
    private fun put(bytes: MutableList<Int>, at: Int, size: Int, value: Int) {
        for (i in 0 until size) bytes[at + i] = value shr (8 * (size - 1 - i)) and 0xFF
    }
}
