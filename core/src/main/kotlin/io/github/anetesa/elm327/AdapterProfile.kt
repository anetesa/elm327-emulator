package io.github.anetesa.elm327

/**
 * How the emulated adapter behaves. The presets reproduce what real ELM327 adapters and their
 * clones do, so a client app can be checked against each of them, not only against a perfect chip.
 */
data class AdapterProfile(
    val id: String,
    val title: String,
    /** Reply to ATZ and ATI. */
    val banner: String,
    /** Time the adapter takes to answer a command, ms. */
    val commandDelayMs: Long = 40,
    /** Replies are sent in pieces of this many bytes (cheap BLE clones send 20 bytes at a time). */
    val chunkSize: Int = Int.MAX_VALUE,
    val chunkDelayMs: Long = 0,
    /** AT commands answered with "?" (cut-down clones). */
    val rejectedAt: Set<String> = emptySet(),
    /** Fake "v2.1" clones ignore all but the first PID of a multi-PID request. */
    val multiPid: Boolean = true,
    /** A longer reply turns into "BUFFER FULL" (the genuine chip has 512 bytes, some clones less). */
    val bufferLimitChars: Int = Int.MAX_VALUE,
    /** The link drops after this many commands (power loss, Bluetooth out of range). */
    val dropAfterCommands: Int? = null,
    /** Reply to STI: only STN chips (OBDLink) have one, the rest answer "?". */
    val stnId: String? = null,
    /** Reply to AT@1; null answers "?". */
    val description: String? = "OBDII to RS232 Interpreter",
    /** The Bluetooth name the real adapter shows in a scan; the phone can be renamed to it. */
    val bluetoothName: String = "OBDII Emulator",
    /** How the adapter treats ATSH: the request address that manufacturer services need. */
    val headers: HeaderSupport = HeaderSupport.SUPPORTED,
)

/** ATSH on clones: most follow it, some answer OK and keep asking every unit, some do not know it at all. */
enum class HeaderSupport { SUPPORTED, IGNORED, REJECTED }

object AdapterProfiles {
    val Genuine = AdapterProfile("genuine", "Genuine ELM327 v2.2", "ELM327 v2.2")
    val CloneV15 = AdapterProfile(
        "clone-v1.5",
        "Clone v1.5",
        "ELM327 v1.5",
        commandDelayMs = 80,
        rejectedAt = setOf("ATAT1"),
    )
    val FakeV21 = AdapterProfile(
        "fake-v2.1",
        "Fake v2.1 (single PID)",
        "ELM327 v2.1",
        commandDelayMs = 90,
        rejectedAt = setOf("ATAT1", "ATR1", "AT@1"),
        multiPid = false,
        description = null,
    )
    val Stn = AdapterProfile("stn", "STN1110 (OBDLink)", "ELM327 v1.4b", commandDelayMs = 10, stnId = "STN1110 v4.2.0")
    val SlowBle = AdapterProfile(
        "slow-ble",
        "Slow BLE clone (20-byte chunks)",
        "ELM327 v1.5",
        commandDelayMs = 60,
        chunkSize = 20,
        chunkDelayMs = 30,
    )
    val SmallBuffer = AdapterProfile("small-buffer", "Clone with a tiny buffer", "ELM327 v1.5", bufferLimitChars = 24)
    val Unstable = AdapterProfile("unstable", "Drops the link after 18 commands", "ELM327 v1.5", dropAfterCommands = 18)

    /** Viecar BLE: a v1.5 clone that sends replies in 20-byte notifications, named "Viecar" in a scan. */
    val Viecar = AdapterProfile(
        "viecar",
        "Viecar BLE (ELM327 v1.5)",
        "ELM327 v1.5",
        commandDelayMs = 60,
        chunkSize = 20,
        chunkDelayMs = 15,
        rejectedAt = setOf("ATAT1"),
        bluetoothName = "Viecar",
    )

    /** Answers OK to ATSH but keeps sending to 7DF, so the engine unit never hears manufacturer requests. */
    val IgnoresHeader = AdapterProfile("ignores-sh", "Clone that ignores ATSH", "ELM327 v1.5", headers = HeaderSupport.IGNORED)

    /** Answers "?" to ATSH: a cut-down clone that can only ask every OBD unit at once. */
    val NoHeader = AdapterProfile("no-sh", "Clone without ATSH", "ELM327 v2.1", headers = HeaderSupport.REJECTED, description = null)

    val all = listOf(Genuine, CloneV15, FakeV21, Stn, SlowBle, SmallBuffer, Unstable, Viecar, IgnoresHeader, NoHeader)
}
