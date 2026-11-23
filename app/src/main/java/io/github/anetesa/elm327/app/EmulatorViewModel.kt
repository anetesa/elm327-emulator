package io.github.anetesa.elm327.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.anetesa.elm327.AdapterProfile
import io.github.anetesa.elm327.AdapterProfiles
import io.github.anetesa.elm327.AudiQ5
import io.github.anetesa.elm327.Bus
import io.github.anetesa.elm327.DemoVehicles
import io.github.anetesa.elm327.ElectricVehicles
import io.github.anetesa.elm327.ElmSession
import io.github.anetesa.elm327.ObdStandard
import io.github.anetesa.elm327.SessionEvent
import io.github.anetesa.elm327.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A car the emulator can play. */
enum class CarChoice(val title: String, val create: () -> Vehicle) {
    CAN("Petrol car, CAN (ISO 15765-4)", { DemoVehicles.petrol(Bus.CAN) }),
    K_LINE("Petrol car, K-Line (ISO 9141-2)", { DemoVehicles.petrol(Bus.K_LINE) }),
    UDS("OBD on UDS (SAE J1979-2)", { DemoVehicles.petrol(Bus.CAN, ObdStandard.J1979_2) }),
    AUDI_Q5("Audi Q5 2.0 TFSI, cold start misfires", { AudiQ5.coldStart(carVin()) }),
    AUDI_Q5_AFTER_CLEAR("Audi Q5 2.0 TFSI, first start after clearing codes", { AudiQ5.coldStart(carVin(), AudiQ5.Misfires.AFTER_CLEAR) }),
    AUDI_Q5_BUSY_UNIT("Audi Q5 2.0 TFSI, engine unit answers \"wait\" first", { AudiQ5.coldStart(carVin(), udsResponsePending = 2) }),
    LEAF_2016("Nissan Leaf 30 kWh (2016), no OBD, battery unit only", { ElectricVehicles.leaf2016() }),
    LEAF_2019("Nissan Leaf 40 kWh (2019), SOH from the battery unit", { ElectricVehicles.leaf2019() }),
    KONA("Hyundai Kona Electric 64 kWh", { ElectricVehicles.konaElectric() }),
    IONIQ_5("Hyundai Ioniq 5 77 kWh (E-GMP, 192 cells)", { ElectricVehicles.ioniq5() }),
    ID4("VW ID.4 (MEB, 29-bit battery unit)", { ElectricVehicles.id4() }),
}

/** The real VIN from local.properties when there is one. */
private fun carVin() = BuildConfig.CAR_VIN.ifBlank { "WAUZZZ8R0FA000000" }

data class UiState(
    val profile: AdapterProfile = AdapterProfiles.Genuine,
    val car: CarChoice = CarChoice.CAN,
    val enabled: Set<TransportKind> = TransportKind.entries.toSet(),
    val running: Boolean = false,
    val statuses: Map<TransportKind, ServerStatus> = emptyMap(),
    val bluetoothName: String? = null,
    val addresses: List<String> = emptyList(),
    /** Newest first. */
    val log: List<String> = emptyList(),
)

@SuppressLint("MissingPermission") // the activity asks for the Bluetooth permissions before starting
class EmulatorViewModel(application: Application) : AndroidViewModel(application), ServerHost {
    private val bluetooth = (application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
    private val _state = MutableStateFlow(UiState(addresses = addresses()))
    val state: StateFlow<UiState> = _state
    private var servers: List<LinkServer> = emptyList()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun select(profile: AdapterProfile) = _state.update { it.copy(profile = profile) }

    fun select(car: CarChoice) = _state.update { it.copy(car = car) }

    fun toggle(kind: TransportKind) = _state.update {
        it.copy(enabled = if (kind in it.enabled) it.enabled - kind else it.enabled + kind)
    }

    /** Re-reads what may have changed while the app was in the background: name, addresses. */
    fun refresh() = _state.update {
        it.copy(bluetoothName = runCatching { bluetooth?.adapter?.name }.getOrNull(), addresses = addresses())
    }

    fun renameBluetooth() {
        runCatching { bluetooth?.adapter?.setName(_state.value.profile.bluetoothName) }
        refresh()
    }

    fun start() {
        if (_state.value.running) return
        val adapter = bluetooth?.adapter
        val enabled = _state.value.enabled
        val statuses = mutableMapOf<TransportKind, ServerStatus>()
        servers = enabled.mapNotNull { kind ->
            when {
                kind == TransportKind.WIFI -> TcpServer()
                adapter == null || !adapter.isEnabled -> {
                    statuses[kind] = ServerStatus.Failed("Bluetooth is off")
                    null
                }
                kind == TransportKind.CLASSIC -> ClassicServer(adapter)
                else -> BleServer(getApplication(), bluetooth)
            }
        }
        _state.update { it.copy(running = true, statuses = statuses, addresses = addresses()) }
        servers.forEach { it.start(viewModelScope, this) }
    }

    fun stop() {
        servers.forEach { it.stop() }
        servers = emptyList()
        _state.update { it.copy(running = false, statuses = emptyMap()) }
    }

    override fun onCleared() = stop()

    override fun status(kind: TransportKind, status: ServerStatus) {
        _state.update { if (it.running) it.copy(statuses = it.statuses + (kind to status)) else it }
    }

    override fun log(kind: TransportKind, client: String, text: String) {
        val line = "${time.format(Date())} ${kind.name} $client: $text"
        _state.update { it.copy(log = (listOf(line) + it.log).take(MAX_LOG)) }
    }

    override fun openSession(kind: TransportKind, client: String, send: suspend (ByteArray) -> Unit, drop: () -> Unit): ElmSession {
        val state = _state.value
        return ElmSession(
            viewModelScope,
            state.profile,
            state.car.create(),
            send = send,
            onEvent = { event ->
                when (event) {
                    is SessionEvent.Command -> log(kind, client, "${event.command} → ${event.reply.readable(event.command)}")
                    is SessionEvent.Dropped -> log(kind, client, "link dropped (${event.reason})")
                }
            },
            onDropped = drop,
        )
    }

    /** The reply without the echo and the prompt, lines separated by " | ". */
    private fun String.readable(command: String): String {
        val lines = split('\r', '\n').filter { it.isNotBlank() && it != ">" }
        return (if (lines.firstOrNull() == command) lines.drop(1) else lines).joinToString(" | ")
    }

    private fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${nic.name})" } }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_LOG = 300
    }
}
