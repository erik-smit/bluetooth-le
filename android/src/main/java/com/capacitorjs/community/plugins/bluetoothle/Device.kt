package com.capacitorjs.community.plugins.bluetoothle

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import kotlin.collections.HashMap

class CallbackResponse(
    val success: Boolean,
    val value: String,
)

class Device(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val address: String,
    private val onDisconnect: () -> Unit
) {
    companion object {
        private val TAG = Device::class.java.simpleName
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
        private const val DEFAULT_TIMEOUT: Long = 5000
        private const val CONNECTION_TIMEOUT: Long = 10000
        private const val REQUEST_MTU = 512
    }

    private var connectionState = STATE_DISCONNECTED
    private var device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
    private var bluetoothGatt: BluetoothGatt? = null
    private var callbackMap = HashMap<String, ((CallbackResponse) -> Unit)>()
    private var timeoutMap = HashMap<String, Handler>()
    private var setNotificationsKey = ""
    private var bondStateReceiver: BroadcastReceiver? = null

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED
                // service discovery is required to use services
                Log.d(TAG, "Connected to GATT server. Starting service discovery.")
                val result = bluetoothGatt?.discoverServices()
                if (result != true) {
                    reject("connect", "Starting service discovery failed.")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED
                onDisconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                Log.d(TAG, "Disconnected from GATT server.")
                resolve("disconnect", "Disconnected.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Try requesting a larger MTU. Maximally supported MTU will be selected.
                requestMtu(REQUEST_MTU)
            } else {
                reject("connect", "Service discovery failed.")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed: $mtu")
            } else {
                Log.d(TAG, "MTU change failed: $mtu")
            }
            resolve("connect", "Connected.")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            val key = "read|${characteristic.service.uuid}|${characteristic.uuid}"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    val value = bytesToString(data)
                    resolve(key, value)
                } else {
                    reject(key, "No data received while reading characteristic.")
                }
            } else {
                reject(key, "Reading characteristic failed.")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            val key = "write|${characteristic.service.uuid}|${characteristic.uuid}"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resolve(key, "Characteristic successfully written.")
            } else {
                reject(key, "Writing characteristic failed.")
            }

        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            val notifyKey = "notification|${characteristic.service.uuid}|${characteristic.uuid}"
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) {
                val value = bytesToString(data)
                callbackMap[notifyKey]?.invoke(CallbackResponse(true, value))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resolve(setNotificationsKey, "Setting notification succeeded.")
            } else {
                reject(setNotificationsKey, "Setting notification failed.")
            }
            setNotificationsKey = ""

        }
    }

    fun getId(): String {
        return address
    }

    /**
     * Async actions that will be executed (see gattCallback)
     * - connect to gatt server
     * - discover services
     * - request MTU
     */
    fun connect(callback: (CallbackResponse) -> Unit) {
        val key = "connect"
        callbackMap[key] = callback
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        connectionState = STATE_CONNECTING
        setConnectionTimeout(key, "Connection timeout.", bluetoothGatt)
    }

    fun isConnected(): Boolean {
        return connectionState == STATE_CONNECTED
    }

    private fun requestMtu(mtu: Int) {
        Log.d(TAG, "requestMtu $mtu")
        val result = bluetoothGatt?.requestMtu(mtu)
        if (result != true) {
            reject("connect", "Starting requestMtu failed.")
        }
    }

    fun createBond(callback: (CallbackResponse) -> Unit) {
        val key = "createBond"
        callbackMap[key] = callback
        try {
            createBondStateReceiver()
        } catch (e: Error) {
            Log.e(TAG, "Error while registering bondStateReceiver: ${e.localizedMessage}")
            reject(key, "Creating bond failed.")
            return
        }
        val result = device.createBond()
        if (!result) {
            reject(key, "Creating bond failed.")
            return
        }
        // if already bonded, resolve immediately
        if (isBonded()) {
            resolve(key, "Creating bond succeeded.")
            return
        }
        // otherwise, wait for bond state change
    }

    private fun createBondStateReceiver() {
        if (bondStateReceiver == null) {
            bondStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                        val key = "createBond"
                        val updatedDevice =
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        // BroadcastReceiver receives bond state updates from all devices, need to filter by device
                        if (device.address == updatedDevice?.address) {
                            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                            val previousBondState =
                                intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                            Log.d(TAG, "Bond state transition $previousBondState -> $bondState")
                            if (bondState == BluetoothDevice.BOND_BONDED) {
                                resolve(key, "Creating bond succeeded.")
                            } else if (previousBondState == BluetoothDevice.BOND_BONDING && bondState == BluetoothDevice.BOND_NONE) {
                                reject(key, "Creating bond failed.")
                            } else if (bondState == -1) {
                                reject(key, "Creating bond failed.")
                            }
                        }
                    }
                }
            }
            val intentFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(bondStateReceiver, intentFilter)
        }
    }

    fun isBonded(): Boolean {
        return device.bondState == BluetoothDevice.BOND_BONDED
    }

    fun disconnect(callback: (CallbackResponse) -> Unit) {
        val key = "disconnect"
        callbackMap[key] = callback
        if (bluetoothGatt == null) {
            resolve(key, "Disconnected.")
            return
        }
        bluetoothGatt?.disconnect()
        setTimeout(key, "Disconnection timeout.")
    }

    fun read(serviceUUID: UUID, characteristicUUID: UUID, callback: (CallbackResponse) -> Unit) {
        val key = "read|$serviceUUID|$characteristicUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        val result = bluetoothGatt?.readCharacteristic(characteristic)
        if (result != true) {
            reject(key, "Reading characteristic failed.")
            return
        }
        setTimeout(key, "Read timeout.")
    }

    fun write(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        value: String,
        writeType: Int,
        callback: (CallbackResponse) -> Unit
    ) {
        val key = "write|$serviceUUID|$characteristicUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        if (value == "") {
            reject(key, "Invalid data.")
            return
        }
        val bytes = stringToBytes(value)
        characteristic.value = bytes
        characteristic.writeType = writeType
        val result = bluetoothGatt?.writeCharacteristic(characteristic)
        if (result != true) {
            reject(key, "Writing characteristic failed.")
            return
        }
        setTimeout(key, "Write timeout.")
    }

    fun setNotifications(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        enable: Boolean,
        notifyCallback: ((CallbackResponse) -> Unit)?,
        callback: (CallbackResponse) -> Unit,
    ) {
        val key = "setNotifications|$serviceUUID|$characteristicUUID"
        setNotificationsKey = key
        val notifyKey = "notification|$serviceUUID|$characteristicUUID"
        callbackMap[key] = callback
        if (notifyCallback != null) {
            callbackMap[notifyKey] = notifyCallback
        }
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }

        val result = bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        if (result != true) {
            reject(key, "Setting notification failed.")
            return
        }

        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
        if (descriptor == null) {
            reject(key, "Setting notification failed.")
            return
        }

        if (enable) {
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
        } else {
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }

        val resultDesc = bluetoothGatt?.writeDescriptor(descriptor)
        if (resultDesc != true) {
            reject(key, "Setting notification failed.")
            return
        }
        // wait for onDescriptorWrite
    }

    private fun resolve(key: String, value: String) {
        if (callbackMap.containsKey(key)) {
            Log.d(TAG, "resolve: $key $value")
            callbackMap[key]?.invoke(CallbackResponse(true, value))
            callbackMap.remove(key)
            timeoutMap[key]?.removeCallbacksAndMessages(null)
            timeoutMap.remove(key)
        } else {
            Log.w(TAG, "Resolve callback not registered for key: $key")
        }
    }

    private fun reject(key: String, value: String) {
        if (callbackMap.containsKey(key)) {
            Log.d(TAG, "reject: $key $value")
            callbackMap[key]?.invoke(CallbackResponse(false, value))
            callbackMap.remove(key)
            timeoutMap[key]?.removeCallbacksAndMessages(null)
            timeoutMap.remove(key)
        } else {
            Log.w(TAG, "Reject callback not registered for key: $key")
        }
    }

    private fun setTimeout(key: String, message: String, timeout: Long = DEFAULT_TIMEOUT) {
        val handler = Handler(Looper.getMainLooper())
        timeoutMap[key] = handler
        handler.postDelayed({
            reject(key, message)
        }, timeout)
    }

    private fun setConnectionTimeout(
        key: String,
        message: String,
        gatt: BluetoothGatt?,
        timeout: Long = CONNECTION_TIMEOUT
    ) {
        val handler = Handler(Looper.getMainLooper())
        timeoutMap[key] = handler
        handler.postDelayed({
            gatt?.disconnect()
            reject(key, message)
        }, timeout)
    }
}