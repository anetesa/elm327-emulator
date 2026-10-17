package io.github.anetesa.elm327.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import io.github.anetesa.elm327.ElmSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A Bluetooth LE adapter as the popular ones look (Vgate iCar Pro, Veepeak BLE and their clones):
 * service FFF0 with a notify characteristic FFF1 for replies and a write characteristic FFF2 for
 * commands. Replies go out as notifications of at most MTU − 3 bytes, one after another.
 */
@SuppressLint("MissingPermission") // the activity asks for BLUETOOTH_CONNECT and BLUETOOTH_ADVERTISE first
class BleServer(private val context: Context, private val manager: BluetoothManager) : LinkServer {
    override val kind = TransportKind.BLE

    private var gatt: BluetoothGattServer? = null
    private var host: ServerHost? = null
    private val clients = ConcurrentHashMap<String, Client>()

    private val notify = BluetoothGattCharacteristic(
        NOTIFY_UUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
    }
    private val write = BluetoothGattCharacteristic(
        WRITE_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    private class Client(val device: BluetoothDevice) {
        @Volatile var mtu = DEFAULT_MTU

        @Volatile var subscribed = false

        /** One signal per delivered notification: the next one may go. */
        val sent = Channel<Unit>(Channel.CONFLATED)
        var session: ElmSession? = null
    }

    override fun start(scope: CoroutineScope, host: ServerHost) {
        this.host = host
        val advertiser = manager.adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            host.status(kind, ServerStatus.Failed("this phone cannot advertise over Bluetooth LE"))
            return
        }
        val server = try {
            manager.openGattServer(context, callback)
        } catch (e: SecurityException) {
            null
        }
        if (server == null) {
            host.status(kind, ServerStatus.Failed("cannot open the GATT server"))
            return
        }
        gatt = server
        // Advertising starts once the service is registered (onServiceAdded)
        server.addService(BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(notify)
            addCharacteristic(write)
        })
    }

    override fun stop() {
        runCatching { manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertising) }
        clients.values.forEach { client ->
            client.session?.close()
            runCatching { gatt?.cancelConnection(client.device) }
        }
        clients.clear()
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun advertise() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        // The name goes into the scan response: together with the UUID it may not fit 31 bytes
        val response = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        manager.adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, response, advertising)
    }

    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            host?.status(kind, ServerStatus.Listening("advertising as \"${manager.adapter.name}\""))
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) "the Bluetooth name is too long, rename the phone" else "error $errorCode"
            host?.status(kind, ServerStatus.Failed("cannot advertise: $reason"))
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) advertise() else host?.status(kind, ServerStatus.Failed("cannot add the service: $status"))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val host = host ?: return
            val name = device.name ?: device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val client = Client(device)
                clients[device.address] = client
                client.session = host.openSession(kind, name, send = { send(client, it) }, drop = { gatt?.cancelConnection(device) })
                host.status(kind, ServerStatus.Connected(name))
                host.log(kind, name, "connected")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clients.remove(device.address)?.session?.close()
                host.log(kind, name, "disconnected")
                if (gatt != null && clients.isEmpty()) host.status(kind, ServerStatus.Listening("advertising as \"${manager.adapter.name}\""))
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            clients[device.address]?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            if (characteristic.uuid == WRITE_UUID) clients[device.address]?.session?.receive(value)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                clients[device.address]?.subscribed = !value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (clients[device.address]?.subscribed == true) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            clients[device.address]?.sent?.trySend(Unit)
        }
    }

    /** Sends a reply as notifications, each waiting for the previous one to be delivered. */
    private suspend fun send(client: Client, bytes: ByteArray) {
        val server = gatt ?: return
        if (!client.subscribed) return
        for (chunk in bytes.asList().chunked(client.mtu - ATT_HEADER)) {
            val value = chunk.toByteArray()
            while (client.sent.tryReceive().isSuccess) Unit
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(client.device, notify, false, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                notify.value = value
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(client.device, notify, false)
            }
            if (!queued) return
            withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { client.sent.receive() }
        }
    }

    private companion object {
        fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        val SERVICE_UUID = uuid16("fff0")
        val NOTIFY_UUID = uuid16("fff1")
        val WRITE_UUID = uuid16("fff2")
        val CCCD_UUID = uuid16("2902")
        const val DEFAULT_MTU = 23
        const val ATT_HEADER = 3
        const val NOTIFY_TIMEOUT_MS = 1000L
    }
}
