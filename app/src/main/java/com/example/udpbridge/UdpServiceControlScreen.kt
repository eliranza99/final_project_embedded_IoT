package com.example.udpbridge

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// הגדרת מצבי השליטה ההיברידית
enum class ControlMedium { WIFI, BLE }

@Composable
fun UdpServiceControlScreen(
    deviceIp: String,
    initialLocalPort: Int,
    initialWebSocketPort: Int,
    initialHttpPort: Int,
    onUpdateLocalPort: (Int) -> Unit,
    onUpdateWebSocketPort: (Int) -> Unit,
    onUpdateHttpPort: (Int) -> Unit,
    onStart: (udpPort: Int, wsPort: Int, httpPort: Int) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    var isRunning by remember { mutableStateOf(false) }
    var controlMedium by remember { mutableStateOf(ControlMedium.WIFI) } // ברירת מחדל WIFI

    val lastMsg by UdpSharedState.lastMessage.collectAsState()
    val lastRecKey by UdpSharedState.lastReceivedRecordingKey.collectAsState()

    var showNetworkConfig by remember { mutableStateOf(false) }

    var udpPortText by remember { mutableStateOf(initialLocalPort.toString()) }
    var wsPortText by remember { mutableStateOf(initialWebSocketPort.toString()) }
    var httpPortText by remember { mutableStateOf(initialHttpPort.toString()) }

    var remoteIpText by remember { mutableStateOf(NetworkConfigState.remoteDeviceIp) }
    var remotePortText by remember { mutableStateOf(NetworkConfigState.remoteUdpPort.toString()) }

    val currentUdpPort = udpPortText.toIntOrNull() ?: initialLocalPort
    val currentWsPort = wsPortText.toIntOrNull() ?: initialWebSocketPort
    val currentHttpPort = httpPortText.toIntOrNull() ?: initialHttpPort

    fun httpBase(): String = "http://$deviceIp:$currentHttpPort"

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // מימוש פונקציית השליטה ההיברידית המלאה
    fun sendHybridCommand(asciiCommand: String) {
        if (controlMedium == ControlMedium.WIFI) {
            val ip = remoteIpText.trim()
            val port = remotePortText.toIntOrNull()
            if (ip.isEmpty() || port == null) {
                toast("Configure Remote IP/Port first")
                return
            }
            NetworkConfigState.updateRemoteDevice(ip, port)
            // שליחה ב-WiFi (UDP) ללא Secret Key
            UdpCommandClient.sendAsciiCommand(ip, port, asciiCommand)
        } else {
            // שליחה ב-BLE דרך ה-BleManager החדש
            BleManager.sendAsciiCommand(asciiCommand)
            toast("Command '$asciiCommand' sent via BLE")
        }
    }

    fun openInVlc(url: String) {
        val uri = Uri.parse(url)
        val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            setPackage("org.videolan.vlc")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(vlcIntent)
        } catch (_: Exception) {
            val chooser = Intent.createChooser(vlcIntent, "Open with")
            context.startActivity(chooser)
        }
    }

    fun openLastReceivedWavInVlc() {
        val key = lastRecKey?.trim().orEmpty()
        if (key.isEmpty()) {
            toast("No received recording yet")
            return
        }
        openInVlc("${httpBase()}/media/audio/$key")
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("IoT Hybrid Gateway", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))

        // בחירת ערוץ שליטה (WiFi / BLE)
        Text("Control Medium Selection:", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = controlMedium == ControlMedium.WIFI, onClick = { controlMedium = ControlMedium.WIFI })
            Text("WiFi (UDP)")
            Spacer(Modifier.width(20.dp))
            RadioButton(selected = controlMedium == ControlMedium.BLE, onClick = { controlMedium = ControlMedium.BLE })
            Text("Bluetooth (BLE)")
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // כפתור SOS בולט - פקודת '!'
        Button(
            onClick = { sendHybridCommand(UdpCommandClient.Commands.SOS) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            modifier = Modifier.fillMaxWidth().height(60.dp),
            enabled = isRunning
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SEND SOS SIGNAL")
        }

        Spacer(Modifier.height(16.dp))

        // בקרת הגבר (Gain) - פקודות 'U' ו-'D'
        Text("Microphone Gain Control", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { sendHybridCommand(UdpCommandClient.Commands.GAIN_UP) }, enabled = isRunning) {
                Text("Gain Up (+)")
            }
            Button(onClick = { sendHybridCommand(UdpCommandClient.Commands.GAIN_DOWN) }, enabled = isRunning) {
                Text("Gain Down (-)")
            }
        }

        Spacer(Modifier.height(16.dp))

        // בקרת הקלטה וסטרימינג - פקודות ASCII
        Text("Recording & Streaming", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { sendHybridCommand(UdpCommandClient.Commands.RECORD_START) }, enabled = isRunning) {
                Text("Rec Start")
            }
            OutlinedButton(onClick = { sendHybridCommand(UdpCommandClient.Commands.RECORD_STOP) }, enabled = isRunning) {
                Text("Rec Stop")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { sendHybridCommand(UdpCommandClient.Commands.STREAM_START) }, enabled = isRunning) {
                Text("Stream Start")
            }
            OutlinedButton(onClick = { sendHybridCommand(UdpCommandClient.Commands.STREAM_STOP) }, enabled = isRunning) {
                Text("Stream Stop")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 18.dp))

        Text("Last message received:", style = MaterialTheme.typography.titleMedium)
        Text(lastMsg, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(12.dp))

        Button(onClick = { openLastReceivedWavInVlc() }, enabled = (lastRecKey != null)) {
            Text("Play last received WAV (VLC)")
        }

        Spacer(Modifier.height(18.dp))

        OutlinedButton(onClick = { showNetworkConfig = !showNetworkConfig }) {
            Text(if (showNetworkConfig) "Hide Config" else "Show Network Config")
        }

        if (showNetworkConfig) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = remoteIpText,
                onValueChange = { remoteIpText = it },
                label = { Text("Remote Device IP (for WiFi)") }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = remotePortText,
                onValueChange = { remotePortText = it },
                label = { Text("Remote UDP Port") }
            )
            Spacer(Modifier.height(12.dp))
            Text("Local Gateway Ports: UDP:$currentUdpPort | WS:$currentWsPort | HTTP:$currentHttpPort")
        }

        Spacer(Modifier.height(24.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onStart(currentUdpPort, currentWsPort, currentHttpPort)
                isRunning = true
            },
            enabled = !isRunning
        ) { Text("START GATEWAY SERVICE") }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onStop(); isRunning = false },
            enabled = isRunning
        ) { Text("STOP SERVICE") }
    }
}