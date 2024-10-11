package org.beiwe.app.listeners

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.beiwe.app.PermissionHandler
import org.beiwe.app.storage.TextFileManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class OmniringListener : Service() {
    private var bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionState = STATE_DISCONNECTED
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var OMNIRING_DATA_SERVICE_UUID = ""
    var collecting = false

    companion object {
        private const val TAG = "OmniringListener"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
        private const val OMNIRING_DATA_CHARACTERISTIC_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2

        @JvmField
        var omniring_header =
            "timestamp,PPG_red,PPG_IR,PPG_Green,IMU_Accel_x,IMU_Accel_y,IMU_Accel_z,IMU_Gyro_x,IMU_Gyro_y,IMU_Gyro_z,IMU_Mag_x,IMU_Mag_y,IMU_Mag_z,timestamp"

    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun unpackFByteArray(byteArray: ByteArray): Float {
        val buffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.float
    }

    private fun decodeByteData(byteData: ByteArray): List<Float> {
        val floatArray = mutableListOf<Float>()
        for (i in byteData.indices step 4) {
            val tmpFloat = unpackFByteArray(byteData.copyOfRange(i, i + 4))
            floatArray.add(tmpFloat)
        }
        return floatArray
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    // Disable notifications
    private fun disableNotification(
        serviceUUID: String,
        characteristicUUID: String
    ) {
        val service = bluetoothGatt?.getService(UUID.fromString(serviceUUID))
        val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUUID))
        if (characteristic != null) {
            // Disable notifications
            bluetoothGatt?.setCharacteristicNotification(characteristic, false)

            // Configure the descriptor for notifications
            val descriptor =
                characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            bluetoothGatt?.writeDescriptor(descriptor)
        }
    }

    // Enable notifications
    private fun enableNotification(
        serviceUUID: String,
        characteristicUUID: String
    ) {
        val service = bluetoothGatt?.getService(UUID.fromString(serviceUUID))
        val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUUID))
        if (characteristic != null) {
            // Enable notifications
            bluetoothGatt?.setCharacteristicNotification(characteristic, true)

//            // Configure the descriptor for notifications
//            val descriptor =
//                characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
//            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//            bluetoothGatt?.writeDescriptor(descriptor)
        }
    }

    private fun getSupportedGattServices(): List<BluetoothGattService>? {
        return if (bluetoothGatt == null) null else bluetoothGatt?.services
    }

    private fun subscribeToNotifications() {
        val gattServices = getSupportedGattServices()
        gattServices?.forEach { service ->
            Log.d(TAG, "subscribeToNotifications: service found ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                Log.d(TAG, "subscribeToNotifications: characteristic found ${characteristic.uuid}")

                if (characteristic.uuid.toString() == OMNIRING_DATA_CHARACTERISTIC_UUID) {
                    OMNIRING_DATA_SERVICE_UUID = service.uuid.toString()
                    Log.d(
                        TAG,
                        "subscribeToNotifications: omniring data characteristic found, enabling notifications"
                    )
                    enableNotification(
                        serviceUUID = OMNIRING_DATA_SERVICE_UUID,
                        characteristicUUID = OMNIRING_DATA_CHARACTERISTIC_UUID
                    )
                }
            }
        }
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (collecting) {
                subscribeToNotifications()
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "onConnectionStateChange: connected to omniring ${gatt.device.name}")
                Log.d(TAG, "onConnectionStateChange: now discovering services..")
                connectionState = STATE_CONNECTED
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(
                    TAG,
                    "onConnectionStateChange: disconnected from omniring ${gatt.device.name}"
                )
                connectionState = STATE_DISCONNECTED
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            TextFileManager.getOmniRingLog().writeEncrypted(
                System.currentTimeMillis().toString() + "," +
                        decodeByteData(characteristic?.value ?: byteArrayOf()).joinToString(",")
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite: ${descriptor?.value?.toHexString()}")
        }
    }

    private fun connect(address: String): Boolean {
        bluetoothAdapter?.let { adapter ->
            try {
                val device = adapter.getRemoteDevice(address)
                bluetoothGatt =
                    device.connectGatt(this@OmniringListener, false, bluetoothGattCallback)
                return true
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "failed to connect to omniring ${e.message}")
                return false
            }
        } ?: run {
            Log.e(TAG, "connect: bluetooth adapter not initialized")
            return false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            if (
                PermissionHandler.checkBluetoothPermissions(this@OmniringListener) &&
                device.name.startsWith("PPG_Ring")
            ) {
                TextFileManager.getOmniRingLog().newFile()
                if (device.bondState != BluetoothAdapter.STATE_CONNECTED)
                    connect(device.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("Bluetooth", "Scan failed with error code: $errorCode")
        }
    }

    private fun initialize(): Boolean {
        Log.d(TAG, "initialize: init omniring")
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initialize()
        if (PermissionHandler.checkBluetoothPermissions(this))
            bluetoothLeScanner?.startScan(scanCallback)
        return START_STICKY
    }

    private fun close() {
        bluetoothGatt?.let { gatt ->
            gatt.close()
            bluetoothGatt = null
        }
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }
}