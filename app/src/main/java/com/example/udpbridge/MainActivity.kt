package com.example.udpbridge

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ חשוב: מאפשר ל-HttpServerManager לגשת ל-assets
        AppContextHolder.init(applicationContext)

        val deviceIp = getDeviceIpAddress() ?: "Unknown"

        // ✅ קריטי: עכשיו HttpServerManager/VLC משתמשים בזה
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
