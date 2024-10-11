package org.beiwe.app.listeners

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.beiwe.app.storage.EncryptionEngine
import org.beiwe.app.storage.TextFileManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class OmniringService : Service() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionState = STATE_DISCONNECTED
    var bluetoothLeScanner: BluetoothLeScanner? = null

    companion object {
        private const val TAG = "BLEService"
        const val ACTION_GATT_CONNECTED =
            "dev.rbabtista.kmm_phenotyping.external.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED =
            "dev.rbabtista.kmm_phenotyping.external.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "dev.rbabtista.kmm_phenotyping.external.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE =
            "dev.rbabtista.kmm_phenotyping.external.ACTION_DATA_AVAILABLE"
        const val EXTRA_DATA = "dev.rbabtista.kmm_phenotyping.external.EXTRA_DATA"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"

        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2

        @JvmField
        var omniring_header =
            "timestamp,PPG_red,PPG_IR,PPG_Green,IMU_Accel_x,IMU_Accel_y,IMU_Accel_z,IMU_Gyro_x,IMU_Gyro_y,IMU_Gyro_z,IMU_Mag_x,IMU_Mag_y,IMU_Mag_z,timestamp"

    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    fun initialize(): Boolean {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        TextFileManager.getBluetoothLogFile().newFile()
        return true
    }

    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic) {
        val intent = Intent(action)

        // For all other profiles, writes the data formatted in HEX.
        val data: ByteArray? = characteristic.value
        if (data?.isNotEmpty() == true) {
            val hexString: String = data.joinToString(separator = " ") {
                String.format("%02X", it)
            }
            intent.putExtra(EXTRA_DATA, "${characteristic.uuid} || $data\n$hexString")
        }
        sendBroadcast(intent)
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED
                broadcastUpdate(ACTION_GATT_CONNECTED)
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED
                broadcastUpdate(ACTION_GATT_DISCONNECTED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
            }

            enableNotification(
                serviceUUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                characteristicUUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(
                TAG,
                "characteristic changed: ${decodeByteData(characteristic?.value ?: byteArrayOf())}"
            )
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
            Log.d(TAG, "descriptor written: ${descriptor?.value?.toHexString()}")
        }
    }

    fun unpackFByteArray(byteArray: ByteArray): Float {
        val buffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.float
    }

    fun decodeByteData(byteData: ByteArray): List<Float> {
        val floatArray = mutableListOf<Float>()
        for (i in byteData.indices step 4) {
            val tmpFloat = unpackFByteArray(byteData.copyOfRange(i, i + 4))
            floatArray.add(tmpFloat)
        }
        return floatArray
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    fun getSupportedGattServices(): List<BluetoothGattService>? {
        return if (bluetoothGatt == null) null else bluetoothGatt?.services
    }

    fun connect(address: String): Boolean {
        bluetoothAdapter?.let { adapter ->
            try {
                val device = adapter.getRemoteDevice(address)
                bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
                return true
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid address.")
                return false
            }
        } ?: run {
            Log.e(TAG, "BluetoothAdapter not initialized.")
            return false
        }
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        bluetoothGatt?.let { gatt ->
            gatt.readCharacteristic(characteristic)
        } ?: run {
            Log.w(TAG, "BluetoothGatt not initialized")
            return
        }
    }

    // Enable notifications
    fun enableNotification(
        serviceUUID: String,
        characteristicUUID: String
    ) {
        val service = bluetoothGatt?.getService(UUID.fromString(serviceUUID))
        val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUUID))
        if (characteristic != null) {
            // Enable notifications
            bluetoothGatt?.setCharacteristicNotification(characteristic, true)

            // Configure the descriptor for notifications
            val descriptor =
                characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            bluetoothGatt?.writeDescriptor(descriptor)
        }
    }

    // Disable notifications
    fun disableNotification(
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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val rssi = result.rssi
//            Log.i(
//                "Bluetooth",
//                System.currentTimeMillis().toString() + "," + device + ", " + rssi
//            )
            TextFileManager.getBluetoothLogFile().writeEncrypted(
                System.currentTimeMillis()
                    .toString() + "," + EncryptionEngine.hashMAC(device.toString()) + "," + rssi
            )
            if (device.name == "PPG_Ring#1") {
                Log.d(TAG, "onScanResult: found device, connecting")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initialize()
        bluetoothLeScanner?.startScan(scanCallback)
        return START_STICKY
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private fun close() {
        bluetoothGatt?.let { gatt ->
            gatt.close()
            bluetoothGatt = null
        }
    }
}