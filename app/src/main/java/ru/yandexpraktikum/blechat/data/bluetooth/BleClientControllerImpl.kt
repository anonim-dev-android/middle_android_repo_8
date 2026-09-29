package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.yandexpraktikum.blechat.R
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelper
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import ru.yandexpraktikum.blechat.utils.notifyCharUUID
import ru.yandexpraktikum.blechat.utils.serviceUUID
import ru.yandexpraktikum.blechat.utils.writeCharUUID
import javax.inject.Inject

class BleClientControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val locationManager: LocationManager,
    private val notifyHelper: NotificationsHelper,
    private val viewModelScope: CoroutineScope,
): BleClientController {

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }
    private var currentGatt: BluetoothGatt? = null


    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    override val isLocationEnabled: StateFlow<Boolean>
        get() = _isLocationEnabled.asStateFlow()


    init {
        updateBluetoothState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLocationState()
        }
    }

    override fun updateBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Bluetooth state", e)
        }
    }

    override fun updateLocationState() {
        try {
            _isLocationEnabled.value = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Location state", e)
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _scannedDevices.asStateFlow()

    /**
     * Обновление элемента из списка [_scannedDevices]
     *
     * @param address адрес устройства, у которого меняется статус подключения
     * @param action действие обновления устройства при его нахождении в списке
     */
    private fun updateDeviceFromList(address: String, action: (ScannedBluetoothDevice) -> ScannedBluetoothDevice) {
        _scannedDevices.update { devices ->
            devices.map {
                if (it.address == address) action.invoke(it) else it
            }
        }
    }


    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            context.checkForConnectPermission {
                val bluetoothDevice = ScannedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
                _scannedDevices.update { devices ->
                    if (devices.none { it.address == bluetoothDevice.address }) {
                        devices + bluetoothDevice
                    } else devices
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE", "Scan failed with error code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        // Задание 2

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (gatt == null) return

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    val discoverResult = gatt.discoverServices()
                    if (discoverResult) {
                        updateDeviceFromList(gatt.device.address) { device -> device.copy(isConnected = true) }
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    updateDeviceFromList(gatt.device.address) { device -> device.copy(isConnected = false) }
                    closeConnection()
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (gatt == null) return

            val notifyCharc = gatt.getService(serviceUUID).getCharacteristic(notifyCharUUID)
            gatt.setCharacteristicNotification(notifyCharc, true)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, charc: BluetoothGattCharacteristic?) {
            if (gatt == null) return

            // обрабатываем изменение характеристики оповещения
            if (charc?.uuid == notifyCharUUID) {
                val value = String(charc.value)
                updateDeviceFromList(gatt.device.address) {
                    device -> device.addRemoteMessage(value, gatt.device.address)
                }
                notifyHelper.notifyOnMessageReceived(context.getString(R.string.new_message), value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, charc: BluetoothGattCharacteristic?, status: Int) {
            Log.i("UWUWUWU", "onCharacteristicWrite gatt address=${gatt?.device?.address}, status=$status, value = ${charc?.value?.let { String(it) }}")
        }

    }

    override fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.startScan(scanCallback)
    }

    override fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.stopScan(scanCallback)
        _scannedDevices.update {
            it.filter { device ->
                device.isConnected
            }
        }
    }

    /**
     * Подключение к выбранному устройству
     *
     * @param device выбранное для подключения устройство
     * @return `true`, если подключение прошло,
     *          `false` - если не выдано разрешение [Manifest.permission.BLUETOOTH_CONNECT]
     *          или подключение не было выполнено из-за отсутсвия какого-то компонента (null)
     */
    override fun connectToDevice(device: ScannedBluetoothDevice): Boolean {
        // Задание 1

        // если разрешение на BLUETOOTH_CONNECT не выдано, возвращаем false
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        // пытаемся подключиться к устройству
        currentGatt = bluetoothAdapter
            ?.getRemoteDevice(device.address)
            ?.connectGatt(context, false, gattCallback)
        return currentGatt != null
    }

    /**
     * Отправка сообщения на другое устройство
     *
     * @param message текст сообщения
     * @param deviceAddress адрес получателя
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun sendMessage(message: String, deviceAddress: String): Boolean {
        // Задание 3
        Log.i("UWUWUWU", "sendMessage current gatt = $currentGatt")
        if (currentGatt == null) return false

        val notifyCharc = currentGatt?.getService(serviceUUID)?.getCharacteristic(writeCharUUID)

        return notifyCharc?.let {
            notifyCharc.setValue(message.toByteArray())
            currentGatt?.writeCharacteristic(notifyCharc).apply {
                Log.i("UWUWUWU", "sendMessage writeCharacteristic result = $this")
            }
            updateDeviceFromList(deviceAddress) { device -> device.addLocalMessage(message, bluetoothAdapter?.address) }
            true
        } ?: false
    }

    /**
     * Очистка ресурсов при закрытии соединения
     */
    @SuppressLint("MissingPermission")
    override fun closeConnection() {
        currentGatt?.close()
        currentGatt = null
    }

    override fun release() {
        closeConnection()
    }
}