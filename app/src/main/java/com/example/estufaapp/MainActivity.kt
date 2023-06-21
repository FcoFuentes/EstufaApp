package com.example.estufaapp

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.estufaapp.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.*
import kotlinx.coroutines.*

private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val LOCATION_PERMISSION_REQUEST_CODE = 2
private const val SERVICE_UUID = "25AE1441-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_READ_UUID = "25AE1442-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_INDICATE_UUID = "25AE1444-05D3-4C5B-8281-93D4E07420CF"
private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

val scope = MainScope()
var job: Job? = null
val CHANNEL_ID = "Notification"
val notificationId = 0
var recargaAux = 0
var enciendeAux = 0

class MainActivity : AppCompatActivity() {

    enum class BLELifecycleState {
        Disconnected,
        Scanning,
        Connecting,
        ConnectedDiscovering,
        ConnectedSubscribing,
        Connected
    }

    private var lifecycleState = BLELifecycleState.Disconnected
        set(value) {
            field = value
            appendLog("status = $value")
            runOnUiThread {
                //  textViewLifecycleState.text = "State: ${value.name}"
                if (value != BLELifecycleState.Connected) {
                    // appendLog("COMO ESTAMOS CTM")
                    //    textViewSubscription.text = getString(R.string.text_not_subscribed)
                }
                if (value == BLELifecycleState.Connected) {
                    showToast("CONECTADO A ESTUFA")
                    startUpdates()
                }
            }
        }

    private val switchConnect: SwitchMaterial
        get() = findViewById<SwitchMaterial>(R.id.switchConnect)

    private val textViewEstado: TextView
        get() = findViewById<TextView>(R.id.fire_name2)

    private val textViewTemp: TextView
        get() = findViewById<TextView>(R.id.fire_name)

    private val textViewTemp_amb: TextView
        get() = findViewById<TextView>(R.id.fire_name1)

    private val userWantsToScanAndConnect: Boolean
        get() = switchConnect.isChecked
    private var isScanning = false
    private var connectedGatt: BluetoothGatt? = null
    private var characteristicForRead: BluetoothGattCharacteristic? = null
    private var characteristicForRead_1: BluetoothGattCharacteristic? = null

    private var characteristicForIndicate: BluetoothGattCharacteristic? = null

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration =
                AppBarConfiguration(
                        setOf(
                                R.id.navigation_home,
                                R.id.navigation_dashboard,
                                R.id.navigation_notifications
                        )
                )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        switchConnect.setOnCheckedChangeListener { _, isChecked ->
            when (isChecked) {
                true -> {
                    val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                    registerReceiver(bleOnOffListener, filter)
                }
                false -> {
                    unregisterReceiver(bleOnOffListener)
                }
            }
            bleRestartLifecycle()
        }
        appendLog("MainActivity.onCreate")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "test_notification"
            val descriptionText = "test_notification_description"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel =
                    NotificationChannel(CHANNEL_ID, name, importance).apply {
                        description = descriptionText
                    }
            val notificationManager: NotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        bleEndLifecycle()
        super.onDestroy()
    }

    fun startUpdates() {
        stopUpdates()
        job =
                scope.launch {
                    while (true) {
                        readEstfa() // the function that should be ran every second
                        delay(1000)
                    }
                }
    }

    fun stopUpdates() {
        job?.cancel()
        job = null
    }

    fun showToast(msj: String) {
        Handler(Looper.getMainLooper())
                .post(Runnable { Toast.makeText(this, msj, Toast.LENGTH_SHORT).show() })
    }

    fun pendingNotification(str: String) {
        val intent =
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, 0)

        val builder =
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("NOTIFICACION DE ALERTA")
                        .setContentText(str)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
        with(NotificationManagerCompat.from(this)) { notify(notificationId, builder.build()) }
    }

    fun readEstfa() {
        var gatt =
                connectedGatt
                        ?: run {
                            appendLog("ERROR: read failed, no connected device")
                            return
                        }
        var characteristic =
                characteristicForRead
                        ?: run {
                            appendLog(
                                    "ERROR: read failed, characteristic unavailable $CHAR_FOR_READ_UUID"
                            )
                            return
                        }
        if (!characteristic.isReadable()) {
            appendLog("ERROR: read failed, characteristic not readable $CHAR_FOR_READ_UUID")
            return
        }
        gatt.readCharacteristic(characteristic)
    }

    fun onTapRead(view: View) {
        var gatt =
                connectedGatt
                        ?: run {
                            appendLog("ERROR: read failed, no connected device")
                            return
                        }
        var characteristic =
                characteristicForRead
                        ?: run {
                            appendLog(
                                    "ERROR: read failed, characteristic unavailable $CHAR_FOR_READ_UUID"
                            )
                            return
                        }
        if (!characteristic.isReadable()) {
            appendLog("ERROR: read failed, characteristic not readable $CHAR_FOR_READ_UUID")
            return
        }
        gatt.readCharacteristic(characteristic)
    }

    private fun appendLog(message: String) {
        Log.d("appendLog", message)
    }

    private fun bleEndLifecycle() {
        safeStopBleScan()
        connectedGatt?.close()
        setConnectedGattToNull()
        lifecycleState = BLELifecycleState.Disconnected
    }

    private fun setConnectedGattToNull() {
        connectedGatt = null
        characteristicForRead = null
        characteristicForRead_1 = null
        characteristicForIndicate = null
    }

    private fun bleRestartLifecycle() {
        runOnUiThread {
            if (userWantsToScanAndConnect) {
                if (connectedGatt == null) {
                    prepareAndStartBleScan()
                } else {
                    connectedGatt?.disconnect()
                }
            } else {
                bleEndLifecycle()
            }
        }
    }

    private fun prepareAndStartBleScan() {
        ensureBluetoothCanBeUsed { isSuccess, message ->
            appendLog(message)
            if (isSuccess) {
                safeStartBleScan()
            }
        }
    }

    private fun safeStartBleScan() {
        if (isScanning) {
            appendLog("Already scanning")
            return
        }

        val serviceFilter = scanFilter.serviceUuid?.uuid.toString()
        appendLog("Starting BLE scan, filter: $serviceFilter")

        isScanning = true
        lifecycleState = BLELifecycleState.Scanning
        bleScanner.startScan(mutableListOf(scanFilter), scanSettings, scanCallback)
    }

    private fun safeStopBleScan() {
        if (!isScanning) {
            appendLog("Already stopped")
            return
        }

        appendLog("Stopping BLE scan")
        isScanning = false
        bleScanner.stopScan(scanCallback)
    }

    private fun subscribeToIndications(
            characteristic: BluetoothGattCharacteristic,
            gatt: BluetoothGatt
    ) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                appendLog("ERROR: setNotification(true) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }
    }

    private fun unsubscribeFromCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = connectedGatt ?: return

        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, false)) {
                appendLog("ERROR: setNotification(false) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private val scanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID))).build()

    private val scanSettings: ScanSettings
        get() {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                scanSettingsSinceM
            } else {
                scanSettingsBeforeM
            }
        }

    private val scanSettingsBeforeM =
            ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setReportDelay(0)
                    .build()

    @RequiresApi(Build.VERSION_CODES.M)
    private val scanSettingsSinceM =
            ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .setReportDelay(0)
                    .build()

    private val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name: String? = result.scanRecord?.deviceName ?: result.device.name
                    appendLog("onScanResult name=$name address= ${result.device?.address}")
                    safeStopBleScan()
                    lifecycleState = BLELifecycleState.Connecting
                    result.device.connectGatt(this@MainActivity, false, gattCallback)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    appendLog("onBatchScanResults, ignoring")
                }

                override fun onScanFailed(errorCode: Int) {
                    appendLog("onScanFailed errorCode=$errorCode")
                    safeStopBleScan()
                    lifecycleState = BLELifecycleState.Disconnected
                    bleRestartLifecycle()
                }
            }

    private val gattCallback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int
                ) {
                    val deviceAddress = gatt.device.address

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            appendLog("Connected to $deviceAddress")
                            Handler(Looper.getMainLooper()).post {
                                lifecycleState = BLELifecycleState.ConnectedDiscovering
                                gatt.discoverServices()
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            appendLog("Disconnected from $deviceAddress")
                            setConnectedGattToNull()
                            gatt.close()
                            lifecycleState = BLELifecycleState.Disconnected
                            bleRestartLifecycle()
                        }
                    } else {
                        appendLog(
                                "ERROR: onConnectionStateChange status=$status deviceAddress=$deviceAddress, disconnecting"
                        )

                        setConnectedGattToNull()
                        gatt.close()
                        lifecycleState = BLELifecycleState.Disconnected
                        bleRestartLifecycle()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    appendLog(
                            "onServicesDiscovered services.count=${gatt.services.size} status=$status"
                    )

                    if (status == 129 /*GATT_INTERNAL_ERROR*/) {
                        appendLog("ERROR: status=129 (GATT_INTERNAL_ERROR), disconnecting")
                        gatt.disconnect()
                        return
                    }

                    val service =
                            gatt.getService(UUID.fromString(SERVICE_UUID))
                                    ?: run {
                                        appendLog(
                                                "ERROR: Service not found $SERVICE_UUID, disconnecting"
                                        )
                                        gatt.disconnect()
                                        return
                                    }

                    connectedGatt = gatt
                    characteristicForRead =
                            service.getCharacteristic(UUID.fromString(CHAR_FOR_READ_UUID))
                    characteristicForIndicate =
                            service.getCharacteristic(UUID.fromString(CHAR_FOR_INDICATE_UUID))

                    characteristicForIndicate?.let {
                        lifecycleState = BLELifecycleState.ConnectedSubscribing
                        subscribeToIndications(it, gatt)
                    }
                            ?: run {
                                appendLog("WARN: characteristic not found $CHAR_FOR_INDICATE_UUID")
                                lifecycleState = BLELifecycleState.Connected
                            }
                }

                override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                ) {
                    var asd = characteristic.uuid
                    appendLog("onCharacteristicRead  uuid $asd")
                    if (characteristic.uuid == UUID.fromString(CHAR_FOR_READ_UUID)) {
                        val strValue = characteristic.value.toString(Charsets.UTF_8)
                        val log =
                                "onCharacteristicRead " +
                                        when (status) {
                                            BluetoothGatt.GATT_SUCCESS -> "OK, value=\"$strValue\""
                                            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "not allowed"
                                            else -> "error $status"
                                        }
                        appendLog(log)
                        runOnUiThread {
                            val splitStr = strValue.split(":")
                            textViewTemp.text = splitStr[0]
                            textViewTemp_amb.text = splitStr[1]
                            textViewEstado.text = splitStr[2]
                            val rec = splitStr[3]
                            val enc = splitStr[4]

                            if (recargaAux == 0 && rec.toInt() == 1) {
                                pendingNotification("RECARGA Y ACOMODA LEÑA")
                                showToast("RECARGA Y ACOMODA LEÑA")
                            }

                            if (enciendeAux == 0 && enc.toInt() == 1) {
                                pendingNotification("ENCIENDE O ACOMODA LEÑA")
                                showToast("ENCIENDE O ACOMODA LEÑA")
                            }
                            recargaAux = rec.toInt()
                            enciendeAux = enc.toInt()
                        }
                    } else {
                        appendLog("onCharacteristicRead unknown uuid $characteristic.uuid")
                    }
                }

                override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                        val strValue = characteristic.value.toString(Charsets.UTF_8)
                        appendLog("onCharacteristicChanged value=\"$strValue\"")
                        runOnUiThread {
                            //  textViewIndicateValue.text = strValue
                        }
                    } else {
                        appendLog("onCharacteristicChanged unknown uuid $characteristic.uuid")
                    }
                }

                override fun onDescriptorWrite(
                        gatt: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int
                ) {
                    if (descriptor.characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val value = descriptor.value
                            val isSubscribed = value.isNotEmpty() && value[0].toInt() != 0
                            val subscriptionText =
                                    when (isSubscribed) {
                                        true -> getString(R.string.text_subscribed)
                                        false -> getString(R.string.text_not_subscribed)
                                    }
                            appendLog("onDescriptorWrite $subscriptionText")
                            runOnUiThread {}
                        } else {
                            appendLog(
                                    "ERROR: onDescriptorWrite status=$status uuid=${descriptor.uuid} char=${descriptor.characteristic.uuid}"
                            )
                        }
                        lifecycleState = BLELifecycleState.Connected
                    } else {
                        appendLog("onDescriptorWrite unknown uuid $descriptor.characteristic.uuid")
                    }
                }
            }

    fun BluetoothGattCharacteristic.isReadable(): Boolean =
            containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    fun BluetoothGattCharacteristic.isWriteable(): Boolean =
            containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    fun BluetoothGattCharacteristic.isWriteableWithoutResponse(): Boolean =
            containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
            containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
            containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return (properties and property) != 0
    }

    enum class AskType {
        AskOnce,
        InsistUntilSuccess
    }

    private var activityResultHandlers = mutableMapOf<Int, (Int) -> Unit>()
    private var permissionResultHandlers =
            mutableMapOf<Int, (Array<out String>, IntArray) -> Unit>()

    private var bleOnOffListener =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getIntExtra(
                                    BluetoothAdapter.EXTRA_STATE,
                                    BluetoothAdapter.STATE_OFF
                            )
                    ) {
                        BluetoothAdapter.STATE_ON -> {
                            appendLog("onReceive: Bluetooth ON")
                            if (lifecycleState == BLELifecycleState.Disconnected) {
                                bleRestartLifecycle()
                            }
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            appendLog("onReceive: Bluetooth OFF")
                            bleEndLifecycle()
                        }
                    }
                }
            }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultHandlers[requestCode]?.let { handler -> handler(resultCode) }
                ?: runOnUiThread {
                    appendLog(
                            "ERROR: onActivityResult requestCode=$requestCode result=$resultCode not handled"
                    )
                }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResultHandlers[requestCode]?.let { handler -> handler(permissions, grantResults) }
                ?: runOnUiThread {
                    appendLog(
                            "ERROR: onRequestPermissionsResult requestCode=$requestCode not handled"
                    )
                }
    }

    private fun ensureBluetoothCanBeUsed(completion: (Boolean, String) -> Unit) {
        enableBluetooth(AskType.AskOnce) { isEnabled ->
            if (!isEnabled) {
                completion(false, "Bluetooth OFF")
                return@enableBluetooth
            }
            grantLocationPermission(AskType.AskOnce) { isGranted ->
                if (!isGranted) {
                    completion(false, "Location permission denied")
                    return@grantLocationPermission
                }

                completion(true, "Bluetooth ON, ready for use")
            }
        }
    }

    private fun enableBluetooth(askType: AskType, completion: (Boolean) -> Unit) {
        if (bluetoothAdapter.isEnabled) {
            completion(true)
        } else {
            val intentString = BluetoothAdapter.ACTION_REQUEST_ENABLE
            val requestCode = ENABLE_BLUETOOTH_REQUEST_CODE
            activityResultHandlers[requestCode] = { result ->
                Unit
                val isSuccess = result == Activity.RESULT_OK
                if (isSuccess || askType != AskType.InsistUntilSuccess) {
                    activityResultHandlers.remove(requestCode)
                    completion(isSuccess)
                } else {
                    startActivityForResult(Intent(intentString), requestCode)
                }
            }
            startActivityForResult(Intent(intentString), requestCode)
        }
    }

    private fun grantLocationPermission(askType: AskType, completion: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isLocationPermissionGranted) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = LOCATION_PERMISSION_REQUEST_CODE
                val wantedPermission = Manifest.permission.ACCESS_FINE_LOCATION

                val builder = AlertDialog.Builder(this)
                builder.setTitle("Location permission required")
                builder.setMessage(
                        "BLE advertising requires location access, starting from Android 6.0"
                )
                builder.setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermission(wantedPermission, requestCode)
                }
                builder.setCancelable(false)
                permissionResultHandlers[requestCode] = { permissions, grantResults ->
                    val isSuccess = grantResults.firstOrNull() != PackageManager.PERMISSION_DENIED
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        builder.create().show()
                    }
                }
                builder.create().show()
            }
        }
    }

    private val isLocationPermissionGranted
        get() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }
    // endregion
}
