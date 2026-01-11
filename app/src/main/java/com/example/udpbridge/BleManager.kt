package com.example.udpbridge

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.*

@SuppressLint("MissingPermission")
object BleManager {
    private const val TAG = "BleManager"

    // UUIDs - יש לוודא שהם תואמים להגדרות בבורד (Nicla/ESP32)
    private val SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val CHARACTERISTIC_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * התחברות למכשיר הניקלה/ESP32
     */
    fun connect(context: Context, deviceAddress: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val device = adapter.getRemoteDevice(deviceAddress)

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    /**
     * שליחת פקודת ASCII דרך הבלוטוס (הצינור)
     */
    fun sendAsciiCommand(command: String) {
        val char = commandCharacteristic
        if (char == null) {
            Log.e(TAG, "Characteristic not found, cannot send BLE command")
            return
        }

        char.value = command.toByteArray(Charsets.UTF_8)
        val success = bluetoothGatt?.writeCharacteristic(char)
        Log.d(TAG, "Sending BLE ASCII: $command | Success: $success")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server, discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server")
                commandCharacteristic = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                commandCharacteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                Log.i(TAG, "BLE Service and Characteristic discovered and ready")
            }
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        commandCharacteristic = null
    }
}