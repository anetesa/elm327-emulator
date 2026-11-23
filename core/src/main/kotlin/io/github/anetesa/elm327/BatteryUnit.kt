package io.github.anetesa.elm327

/**
 * The battery management unit of an electric car: reached on its own CAN ids with manufacturer requests
 * (KWP 21 xx, UDS 22 xxxx), not with the OBD services. [answers] maps a request in hex to the unit's
 * positive reply (service + 0x40, the echoed identifier, then the data) at [t] seconds.
 *
 * Real units are picky the same way the emulator is: the reply comes from [responseId], so an adapter
 * that does not listen on it hears nothing (ATCRA), and a reply longer than one frame needs the flow
 * control frame sent back to [requestId] (ATFCSH/ATFCSM1 when the ids are not "request + 8").
 */
class BatteryUnit(
    /** The id requests go to: three hex digits on 11-bit CAN, eight on 29-bit. */
    val requestId: String,
    val responseId: String,
    private val answers: Map<String, (t: Double) -> List<Int>>,
) {
    /** 29-bit ids (VW MEB): only reachable on protocol 7 or 9. */
    val extended: Boolean get() = requestId.length == EXTENDED_ID_DIGITS

    /** The positive reply or, for a request the unit does not know, "request out of range" (7F xx 31). */
    fun answer(request: String, t: Double): List<Int> =
        answers[request]?.invoke(t) ?: listOf(NEGATIVE, request.take(2).toInt(HEX), OUT_OF_RANGE)

    private companion object {
        const val EXTENDED_ID_DIGITS = 8
        const val NEGATIVE = 0x7F
        const val OUT_OF_RANGE = 0x31
        const val HEX = 16
    }
}
