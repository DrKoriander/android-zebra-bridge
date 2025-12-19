package com.wohnmanufactur.zebrabridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnScanPrinters: Button
    private lateinit var btnSetMac: Button
    private lateinit var editMacAddress: EditText
    private lateinit var printerListView: ListView

    private val discoveredPrinters = mutableListOf<BluetoothDevice>()
    private lateinit var printerAdapter: ArrayAdapter<String>

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { addDiscoveredPrinter(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    statusText.text = "Scan abgeschlossen. ${discoveredPrinters.size} Geräte gefunden."
                    btnScanPrinters.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnScanPrinters = findViewById(R.id.btnScanPrinters)
        btnSetMac = findViewById(R.id.btnSetMac)
        editMacAddress = findViewById(R.id.editMacAddress)
        printerListView = findViewById(R.id.printerListView)

        printerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        printerListView.adapter = printerAdapter

        btnStartService.setOnClickListener { startBridgeService() }
        btnStopService.setOnClickListener { stopBridgeService() }
        btnScanPrinters.setOnClickListener { scanForPrinters() }
        btnSetMac.setOnClickListener { setManualMacAddress() }

        printerListView.setOnItemClickListener { _, _, position, _ ->
            selectPrinter(position)
        }

        // Register Bluetooth receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)

        // Load saved MAC address into EditText
        loadSavedMacAddress()

        checkPermissions()
        loadPairedPrinters()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // Receiver was not registered
        }
    }

    private fun loadSavedMacAddress() {
        val prefs = getSharedPreferences("ZebraBridge", Context.MODE_PRIVATE)
        val savedAddress = prefs.getString("printer_address", null)
        val savedName = prefs.getString("printer_name", "")

        if (savedAddress != null) {
            editMacAddress.setText(savedAddress)
            statusText.text = "Gespeicherter Drucker: $savedName ($savedAddress)"
        }
    }

    private fun setManualMacAddress() {
        val macAddress = editMacAddress.text.toString().trim().uppercase()

        // Validate MAC address format
        val macRegex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        if (!macRegex.matches(macAddress)) {
            Toast.makeText(this, "Ungültiges MAC-Format. Beispiel: F4:60:77:45:E0:62", Toast.LENGTH_LONG).show()
            return
        }

        // Save the MAC address
        val prefs = getSharedPreferences("ZebraBridge", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("printer_address", macAddress)
            .putString("printer_name", "Manuell: $macAddress")
            .apply()

        statusText.text = "MAC-Adresse gesetzt: $macAddress"
        Toast.makeText(this, "Drucker-Adresse gespeichert: $macAddress", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Alle Berechtigungen erteilt", Toast.LENGTH_SHORT).show()
                loadPairedPrinters()
            } else {
                Toast.makeText(this, "Einige Berechtigungen fehlen", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadPairedPrinters() {
        if (!hasBluetoothPermission()) return

        bluetoothAdapter?.bondedDevices?.forEach { device ->
            // Show all paired Bluetooth devices (user can select the printer)
            addDiscoveredPrinter(device)
        }
        updatePrinterList()
    }

    private fun scanForPrinters() {
        if (!hasBluetoothPermission()) {
            Toast.makeText(this, "Bluetooth-Berechtigung fehlt", Toast.LENGTH_SHORT).show()
            return
        }

        discoveredPrinters.clear()
        printerAdapter.clear()
        loadPairedPrinters() // First add paired devices

        bluetoothAdapter?.let { adapter ->
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()
            statusText.text = "Suche nach Geräten..."
            btnScanPrinters.isEnabled = false
        }
    }

    private fun addDiscoveredPrinter(device: BluetoothDevice) {
        if (!discoveredPrinters.any { it.address == device.address }) {
            discoveredPrinters.add(device)
            updatePrinterList()
        }
    }

    private fun updatePrinterList() {
        if (!hasBluetoothPermission()) return

        printerAdapter.clear()
        discoveredPrinters.forEach { device ->
            val name = device.name ?: "Unbekannt"
            printerAdapter.add("$name\n${device.address}")
        }
        printerAdapter.notifyDataSetChanged()
    }

    private fun selectPrinter(position: Int) {
        if (position >= discoveredPrinters.size) return

        val device = discoveredPrinters[position]
        val prefs = getSharedPreferences("ZebraBridge", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("printer_address", device.address)
            .putString("printer_name", device.name ?: "Unbekannt")
            .apply()

        val name = if (hasBluetoothPermission()) device.name ?: "Unbekannt" else "Unbekannt"
        editMacAddress.setText(device.address)
        statusText.text = "Drucker ausgewählt: $name"
        Toast.makeText(this, "Drucker $name ausgewählt", Toast.LENGTH_SHORT).show()
    }

    private fun startBridgeService() {
        val prefs = getSharedPreferences("ZebraBridge", Context.MODE_PRIVATE)
        val printerAddress = prefs.getString("printer_address", null)

        if (printerAddress == null) {
            Toast.makeText(this, "Bitte zuerst einen Drucker auswählen oder MAC-Adresse eingeben", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, PrinterBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        statusText.text = "Bridge Service gestartet auf Port 9100\nDrucker: $printerAddress"
        btnStartService.isEnabled = false
        btnStopService.isEnabled = true
    }

    private fun stopBridgeService() {
        val intent = Intent(this, PrinterBridgeService::class.java)
        stopService(intent)

        statusText.text = "Bridge Service gestoppt"
        btnStartService.isEnabled = true
        btnStopService.isEnabled = false
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }
}
