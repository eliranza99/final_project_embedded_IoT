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
    var controlMedium by remember { mutableStateOf(ControlMedium.WIFI) }
    var showCancelConfirmation by remember { mutableStateOf(false) }

    val lastMsg by UdpSharedState.lastMessage.collectAsState()
    val lastRecKey by UdpSharedState.lastReceivedRecordingKey.collectAsState()

    // זיהוי מצב SOS
    val isSosActive = lastMsg.contains("EMERGENCY") || lastMsg.contains("SOS RECEIVED")

    LaunchedEffect(isSosActive) {
        if (!isSosActive) showCancelConfirmation = false
    }

    var showNetworkConfig by remember { mutableStateOf(false) }

    var udpPortText by remember { mutableStateOf(initialLocalPort.toString()) }
    var wsPortText by remember { mutableStateOf(initialWebSocketPort.toString()) }
    var httpPortText by remember { mutableStateOf(initialHttpPort.toString()) }

    var remoteIpText by remember { mutableStateOf(NetworkConfigState.remoteDeviceIp) }
    var remotePortText by remember { mutableStateOf(NetworkConfigState.remoteUdpPort.toString()) }

    val currentUdpPort = udpPortText.toIntOrNull() ?: initialLocalPort
    val currentWsPort = wsPortText.toIntOrNull() ?: initialWebSocketPort
    val currentHttpPort = httpPortText.toIntOrNull() ?: initialHttpPort

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // פונקציית שליחה חכמה - SOS תמיד נשלח לווב
    fun sendHybridCommand(asciiCommand: String) {
        // 1. הפצה מיידית ל-WebSocket (עבור ה-Dashboard)
        if (asciiCommand == UdpCommandClient.Commands.SOS || asciiCommand == "!") {
            UdpSharedState.update("EMERGENCY: SOS RECEIVED")
            WebSocketServerManager.broadcast("ALARM:SOS_ACTIVE")
        } else if (asciiCommand == "CANCEL_SOS") {
            UdpSharedState.update("NORMAL: SOS CLEARED")
            WebSocketServerManager.broadcast("ALARM:SOS_OFF")
        }

        // 2. שליחה למכשיר הניקלה (אם הוגדר IP)
        if (controlMedium == ControlMedium.WIFI) {
            val ip = remoteIpText.trim()
            val port = remotePortText.toIntOrNull()

            if (ip.isNotEmpty() && port != null) {
                NetworkConfigState.updateRemoteDevice(ip, port)
                UdpCommandClient.sendAsciiCommand(ip, port, asciiCommand)
            } else if (asciiCommand != "!" && asciiCommand != "CANCEL_SOS") {
                toast("Nicla IP/Port not set")
            }
        } else {
            BleManager.sendAsciiCommand(asciiCommand)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("IoT Hybrid Gateway", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))

        // בחירת ערוץ תקשורת
        Text("Control Medium:", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = controlMedium == ControlMedium.WIFI, onClick = { controlMedium = ControlMedium.WIFI })
            Text("WiFi (UDP)")
            Spacer(Modifier.width(20.dp))
            RadioButton(selected = controlMedium == ControlMedium.BLE, onClick = { controlMedium = ControlMedium.BLE })
            Text("Bluetooth (BLE)")
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // SOS Button Logic
        if (!isSosActive) {
            Button(
                onClick = { sendHybridCommand(UdpCommandClient.Commands.SOS) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxWidth().height(65.dp),
                enabled = isRunning
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("SEND SOS SIGNAL")
            }
        } else {
            if (!showCancelConfirmation) {
                Button(
                    onClick = { showCancelConfirmation = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)), // Purple
                    modifier = Modifier.fillMaxWidth().height(65.dp)
                ) {
                    Text("CANCEL SOS SIGNAL")
                }
            } else {
                Button(
                    onClick = {
                        sendHybridCommand("CANCEL_SOS")
                        showCancelConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)), // Orange
                    modifier = Modifier.fillMaxWidth().height(65.dp)
                ) {
                    Text("CONFIRM CANCEL?")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Nicla Controls", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { sendHybridCommand(UdpCommandClient.Commands.GAIN_UP) }, enabled = isRunning) {
                Text("Gain Up (+)")
            }
            Button(onClick = { sendHybridCommand(UdpCommandClient.Commands.GAIN_DOWN) }, enabled = isRunning) {
                Text("Gain Down (-)")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { sendHybridCommand(UdpCommandClient.Commands.RECORD_START) }, enabled = isRunning) {
                Text("Rec Start")
            }
            OutlinedButton(onClick = { sendHybridCommand(UdpCommandClient.Commands.RECORD_STOP) }, enabled = isRunning) {
                Text("Rec Stop")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 18.dp))

        Text("Last message received:", style = MaterialTheme.typography.titleMedium)
        Text(lastMsg, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(18.dp))

        // קנפוג IP ופורט לניקלה
        OutlinedButton(onClick = { showNetworkConfig = !showNetworkConfig }) {
            Text(if (showNetworkConfig) "Hide Config" else "Show Nicla Config")
        }

        if (showNetworkConfig) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = remoteIpText,
                onValueChange = { remoteIpText = it },
                label = { Text("Nicla IP Address") }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = remotePortText,
                onValueChange = { remotePortText = it },
                label = { Text("Nicla UDP Port") }
            )
            Spacer(Modifier.height(12.dp))
            Text("Local Status: UDP:$currentUdpPort | WS:$currentWsPort | HTTP:$currentHttpPort", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onStart(currentUdpPort, currentWsPort, currentHttpPort); isRunning = true },
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