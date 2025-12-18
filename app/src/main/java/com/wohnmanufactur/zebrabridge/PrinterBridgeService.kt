package com.wohnmanufactur.zebrabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.zebra.sdk.comm.BluetoothConnection
import com.zebra.sdk.comm.Connection
import com.zebra.sdk.printer.ZebraPrinter
import com.zebra.sdk.printer.ZebraPrinterFactory
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

class PrinterBridgeService : Service() {

    private var httpServer: BridgeHttpServer? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private var printerAddress: String? = null
    private var printerConnection: Connection? = null
    private var zebraPrinter: ZebraPrinter? = null

    companion object {
        private const val TAG = "PrinterBridgeService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "zebra_bridge_channel"
        private const val HTTP_PORT = 9100
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadPrinterConfig()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startHttpServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopHttpServer()
        disconnectPrinter()
        serviceJob.cancel()
    }

    private fun loadPrinterConfig() {
        val prefs = getSharedPreferences("ZebraBridge", Context.MODE_PRIVATE)
        printerAddress = prefs.getString("printer_address", null)
        Log.d(TAG, "Loaded printer address: $printerAddress")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zebra Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP Bridge für Zebra Drucker"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zebra Bridge aktiv")
            .setContentText("HTTP Server läuft auf Port $HTTP_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startHttpServer() {
        try {
            httpServer = BridgeHttpServer(HTTP_PORT)
            httpServer?.start()
            Log.i(TAG, "HTTP Server started on port $HTTP_PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
        Log.i(TAG, "HTTP Server stopped")
    }

    private fun connectToPrinter(): Boolean {
        val address = printerAddress ?: return false
        
        try {
            if (printerConnection?.isConnected == true) {
                return true
            }
            
            printerConnection = BluetoothConnection(address)
            printerConnection?.open()
            zebraPrinter = ZebraPrinterFactory.getInstance(printerConnection)
            Log.i(TAG, "Connected to printer: $address")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to printer", e)
            return false
        }
    }

    private fun disconnectPrinter() {
        try {
            printerConnection?.close()
            printerConnection = null
            zebraPrinter = null
            Log.i(TAG, "Disconnected from printer")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from printer", e)
        }
    }

    private fun printLabel(cpclData: String): Boolean {
        return try {
            if (!connectToPrinter()) {
                Log.e(TAG, "Cannot print - not connected to printer")
                return false
            }
            
            printerConnection?.write(cpclData.toByteArray(Charsets.UTF_8))
            Log.i(TAG, "Label sent to printer")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to print label", e)
            disconnectPrinter()
            false
        }
    }

    private fun getPrinterStatus(): JsonObject {
        val status = JsonObject()
        status.addProperty("connected", printerConnection?.isConnected == true)
        status.addProperty("printerAddress", printerAddress ?: "")
        
        try {
            if (connectToPrinter()) {
                zebraPrinter?.let { printer ->
                    val printerStatus = printer.currentStatus
                    status.addProperty("isReadyToPrint", printerStatus.isReadyToPrint)
                    status.addProperty("isPaused", printerStatus.isPaused)
                    status.addProperty("isHeadOpen", printerStatus.isHeadOpen)
                    status.addProperty("isPaperOut", printerStatus.isPaperOut)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting printer status", e)
            status.addProperty("error", e.message)
        }
        
        return status
    }

    // Inner class for HTTP Server
    inner class BridgeHttpServer(port: Int) : NanoHTTPD(port) {
        
        private val gson = Gson()

        override fun serve(session: IHTTPSession): Response {
            // Add CORS headers to all responses
            val corsHeaders = mutableMapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "Content-Type, Accept"
            )

            // Handle preflight OPTIONS request
            if (session.method == Method.OPTIONS) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                    corsHeaders.forEach { (key, value) -> addHeader(key, value) }
                }
            }

            val uri = session.uri
            val method = session.method

            Log.d(TAG, "HTTP Request: $method $uri")

            val response = when {
                // Status endpoint
                uri == "/status" || uri == "/api/status" -> {
                    handleStatusRequest()
                }
                
                // Print endpoint
                (uri == "/print" || uri == "/api/print") && method == Method.POST -> {
                    handlePrintRequest(session)
                }
                
                // Discovery endpoint (for compatibility with Browser Print)
                uri == "/available" || uri == "/api/available" -> {
                    handleAvailableRequest()
                }
                
                // Default printers endpoint (Browser Print compatibility)
                uri == "/default" || uri == "/api/default" -> {
                    handleDefaultPrinterRequest()
                }
                
                // Health check
                uri == "/health" || uri == "/" -> {
                    handleHealthCheck()
                }
                
                else -> {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "application/json",
                        "{\"error\": \"Endpoint not found\"}"
                    )
                }
            }

            // Add CORS headers to response
            corsHeaders.forEach { (key, value) -> response.addHeader(key, value) }
            return response
        }

        private fun handleStatusRequest(): Response {
            val status = getPrinterStatus()
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(status)
            )
        }

        private fun handlePrintRequest(session: IHTTPSession): Response {
            return try {
                val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                val buffer = ByteArray(contentLength)
                session.inputStream.read(buffer, 0, contentLength)
                val body = String(buffer, Charsets.UTF_8)

                Log.d(TAG, "Print request body: $body")

                // Parse JSON body
                val jsonBody = try {
                    gson.fromJson(body, JsonObject::class.java)
                } catch (e: Exception) {
                    // If not JSON, treat as raw CPCL
                    null
                }

                val cpclData = jsonBody?.get("data")?.asString ?: body

                serviceScope.launch {
                    val success = printLabel(cpclData)
                    Log.d(TAG, "Print result: $success")
                }

                val result = JsonObject().apply {
                    addProperty("success", true)
                    addProperty("message", "Print job submitted")
                }
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    gson.toJson(result)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error handling print request", e)
                val error = JsonObject().apply {
                    addProperty("success", false)
                    addProperty("error", e.message)
                }
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    gson.toJson(error)
                )
            }
        }

        private fun handleAvailableRequest(): Response {
            val printers = JsonObject().apply {
                addProperty("available", printerAddress != null)
                printerAddress?.let { address ->
                    val printerInfo = JsonObject().apply {
                        addProperty("address", address)
                        addProperty("type", "bluetooth")
                    }
                    add("printer", printerInfo)
                }
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(printers)
            )
        }

        private fun handleDefaultPrinterRequest(): Response {
            val defaultPrinter = JsonObject().apply {
                if (printerAddress != null) {
                    addProperty("name", "Zebra Bluetooth Printer")
                    addProperty("address", printerAddress)
                    addProperty("connection", "bluetooth")
                    addProperty("available", true)
                } else {
                    addProperty("available", false)
                    addProperty("error", "No printer configured")
                }
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(defaultPrinter)
            )
        }

        private fun handleHealthCheck(): Response {
            val health = JsonObject().apply {
                addProperty("status", "ok")
                addProperty("service", "ZebraBridge")
                addProperty("version", "1.0.0")
                addProperty("port", HTTP_PORT)
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(health)
            )
        }
    }
}
