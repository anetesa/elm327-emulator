package io.github.anetesa.elm327.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bluetooth Classic adapters speak the Serial Port Profile (SPP). The client phone must be paired
 * with this one first, as with a real adapter; then it finds the SPP service through SDP.
 */
@SuppressLint("MissingPermission") // the activity asks for BLUETOOTH_CONNECT before starting
class ClassicServer(private val adapter: BluetoothAdapter) : LinkServer {
    override val kind = TransportKind.CLASSIC
    private var server: BluetoothServerSocket? = null
    private val clients = CopyOnWriteArrayList<BluetoothSocket>()

    override fun start(scope: CoroutineScope, host: ServerHost) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                server = socket
                host.status(kind, listening())
                while (isActive) {
                    val client = socket.accept()
                    clients += client
                    val name = client.remoteDevice.name ?: client.remoteDevice.address
                    launch {
                        serveStream(kind, name, client.inputStream, client.outputStream, host) {
                            runCatching { client.close() }
                            clients -= client
                            if (server != null) host.status(kind, listening())
                        }
                    }
                }
            } catch (e: IOException) {
                if (server != null) host.status(kind, ServerStatus.Failed(e.message ?: "cannot open the SPP service"))
            } catch (e: SecurityException) {
                host.status(kind, ServerStatus.Failed("Bluetooth permission denied"))
            }
        }
    }

    private fun listening() = ServerStatus.Listening("pair with \"${adapter.name}\" first")

    override fun stop() {
        val socket = server
        server = null
        runCatching { socket?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private companion object {
        const val SERVICE_NAME = "OBDII"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
