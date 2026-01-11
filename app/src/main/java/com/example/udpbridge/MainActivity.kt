package com.example.udpbridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    // מנהל בקשות ההרשאה - פותר את השגיאה מצילום המסך
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Permissions are required for BLE and Streaming", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // הפעלת בדיקת ההרשאות מיד עם פתיחת האפליקציה
        checkAndRequestPermissions()

        // מאפשר ל-HttpServerManager לגשת ל-assets
        AppContextHolder.init(applicationContext)

        val deviceIp = getDeviceIpAddress() ?: "Unknown"

        if (deviceIp != "Unknown") {
            NetworkConfigState.updateDeviceIp(deviceIp)
        }

        val initialLocalPort = NetworkConfigState.localUdpPort
        val initialWebSocketPort = NetworkConfigState.webSocketPort
        val initialHttpPort = NetworkConfigState.httpPort

        setContent {
            MaterialTheme {
                UdpServiceControlScreen(
                    deviceIp = deviceIp,
                    initialLocalPort = initialLocalPort,
                    initialWebSocketPort = initialWebSocketPort,
                    initialHttpPort = initialHttpPort,
                    onUpdateLocalPort = { NetworkConfigState.updateLocalUdpPort(it) },
                    onUpdateWebSocketPort = { NetworkConfigState.updateWebSocketPort(it) },
                    onUpdateHttpPort = { NetworkConfigState.updateHttpPort(it) },
                    onStart = { udpPort, wsPort, httpPort ->
                        startUdpService(udpPort, wsPort, httpPort)
                    },
                    onStop = { stopUdpService() }
                )
            }
        }
    }

    /**
     * פונקציה לריכוז ובקשת כל ההרשאות הנדרשות
     */
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // טיפול בשגיאת המיקום: חובה לבקש FINE ו-COARSE יחד
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // הרשאות בלוטוס לאנדרואיד 12 ומעלה
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // הרשאה להתראות (נדרש עבור ה-Foreground Service באנדרואיד 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // הרשאה ספציפית לסנכרון נתונים ברקע עבור אנדרואיד 14
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startUdpService(udpPort: Int, webSocketPort: Int, httpPort: Int) {
        val intent = Intent(this, UdpForegroundService::class.java).apply {
            putExtra(UdpForegroundService.EXTRA_UDP_PORT, udpPort)
            putExtra(UdpForegroundService.EXTRA_WS_PORT, webSocketPort)
            putExtra(UdpForegroundService.EXTRA_HTTP_PORT, httpPort)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopUdpService() {
        stopService(Intent(this, UdpForegroundService::class.java))
    }

    private fun getDeviceIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}