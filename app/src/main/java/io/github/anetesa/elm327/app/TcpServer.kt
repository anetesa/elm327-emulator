package io.github.anetesa.elm327.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wi‑Fi adapters are TCP servers, usually at 192.168.0.10:35000. Here the phone listens on every
 * network it is on — its own hotspot included — and the app shows the addresses to type in.
 */
class TcpServer(private val port: Int = DEFAULT_PORT) : LinkServer {
    override val kind = TransportKind.WIFI
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    override fun start(scope: CoroutineScope, host: ServerHost) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                server = socket
                host.status(kind, ServerStatus.Listening("port $port"))
                while (isActive) {
                    val client = socket.accept()
                    clients += client
                    launch {
                        serveStream(kind, client.inetAddress.hostAddress.orEmpty(), client.getInputStream(), client.getOutputStream(), host) {
                            runCatching { client.close() }
                            clients -= client
                            if (server != null) host.status(kind, ServerStatus.Listening("port $port"))
                        }
                    }
                }
            } catch (e: IOException) {
                if (server != null) host.status(kind, ServerStatus.Failed(e.message ?: "cannot listen on port $port"))
            }
        }
    }

    override fun stop() {
        val socket = server
        server = null
        runCatching { socket?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    companion object {
        const val DEFAULT_PORT = 35000
    }
}
