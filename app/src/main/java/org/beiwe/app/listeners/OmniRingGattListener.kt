package org.beiwe.app.listeners

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log
import org.beiwe.app.storage.TextFileManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OmniRingGattListener : BluetoothGattCallback() {
    private const val TAG = "OmniRingGattListener"
    var lineCount = 0


    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "onConnectionStateChange: connected")
            gatt?.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        }
    }


    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        super.onServicesDiscovered(gatt, status)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            gatt?.services?.forEach { service ->
                Log.d(TAG, "gatt service: ${service.uuid}")
                service.characteristics?.forEach { characteristic ->
                    Log.d(TAG, "gatt service characteristic: ${characteristic.uuid}")
                    gatt?.readCharacteristic(characteristic)
                }
            }
        }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // TODO
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        if (lineCount > 1000) {
            TextFileManager.getOmniRingLog().newFile()
            lineCount = 0
        }
        val data = decodeByteData(characteristic?.value ?: byteArrayOf()).joinToString(",") + "\n"
        Log.d(TAG, "onCharacteristicChanged: $data")
        TextFileManager.getOmniRingLog().writeEncrypted(data)
        lineCount++
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        // TODO
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

}