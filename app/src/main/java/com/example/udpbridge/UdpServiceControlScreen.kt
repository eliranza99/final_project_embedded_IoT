package com.example.udpbridge

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
    val lastMsg by UdpSharedState.lastMessage.collectAsState()
    val lastFilePath by UdpSharedState.lastReceivedFilePath.collectAsState()
    val lastRecKey by UdpSharedState.lastReceivedRecordingKey.collectAsState() // ✅

    var showNetworkConfig by remember { mutableStateOf(true) }

    var udpPortText by remember { mutableStateOf(initialLocalPort.toString()) }
    var wsPortText by remember { mutableStateOf(initialWebSocketPort.toString()) }
    var httpPortText by remember { mutableStateOf(initialHttpPort.toString()) }

    var remoteIpText by remember { mutableStateOf(NetworkConfigState.remoteDeviceIp) }
    var remotePortText by remember { mutableStateOf(NetworkConfigState.remoteUdpPort.toString()) }

    var secretKey by remember { mutableStateOf(SecurityConfigState.secretKey) }

    val currentUdpPort = udpPortText.toIntOrNull() ?: initialLocalPort
    val currentWsPort = wsPortText.toIntOrNull() ?: initialWebSocketPort
    val currentHttpPort = httpPortText.toIntOrNull() ?: initialHttpPort

    fun httpBase(): String = "http://$deviceIp:$currentHttpPort"

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun openInVlc(url: String) {
        val uri = Uri.parse(url)

        // ניסיון חזק: ACTION_VIEW + סוג audio/*
        val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            setPackage("org.videolan.vlc")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(vlcIntent)
        } catch (_: Exception) {
            // אם VLC לא נתפס (או לא מותקן) - נציע chooser
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Open with"
            )
            context.startActivity(chooser)
        }
    }



    fun sendCommandOrShowError(payload: String) {
        val ip = remoteIpText.trim()
        val port = remotePortText.toIntOrNull()
        if (ip.isEmpty() || port == null) {
            UdpSharedState.update("Configure remote IP & port for commands")
            toast("חסר Remote IP / Port")
            return
        }
        NetworkConfigState.updateRemoteDevice(ip, port)
        UdpCommandClient.sendCommand(ip, port, payload)
    }

    // ✅ שינוי מרכזי: VLC מקבל WAV ישיר, לא playlist (מונע "לא ניתן לנגן מגוון...")
    fun openLastReceivedWavInVlc() {
        val key = lastRecKey?.trim().orEmpty()
        if (key.isEmpty()) {
            toast("No received recording yet")
            return
        }
        val url = "${httpBase()}/media/audio/$key"
        openInVlc(url)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("UDP Bridge Service", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))

        Text(
            text = if (isRunning) "Service is RUNNING" else "Service is STOPPED",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(12.dp))

        Text("Last message received:", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(lastMsg, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(16.dp))

        Text("Recorded audio (VLC)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { openLastReceivedWavInVlc() },
            enabled = (lastRecKey != null) || (lastFilePath != null)
        ) {
            Text("Play last received WAV (VLC)")
        }

        Spacer(Modifier.height(18.dp))

        Text("Remote device (commands)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = remoteIpText,
            onValueChange = { remoteIpText = it },
            label = { Text("Remote IP (Nicla / PC)") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = remotePortText,
            onValueChange = { remotePortText = it },
            label = { Text("Remote UDP Port (Phone → Device)") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        Text("Audio recording", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { sendCommandOrShowError("CMD:start_rec_audio") },
                enabled = isRunning
            ) { Text("Start Audio Rec") }

            OutlinedButton(
                onClick = { sendCommandOrShowError("CMD:stop_rec_audio") },
                enabled = isRunning
            ) { Text("Stop Audio Rec") }
        }

        Spacer(Modifier.height(10.dp))

        Text("Video recording (audio+video)", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { sendCommandOrShowError("CMD:start_rec_video") },
                enabled = isRunning
            ) { Text("Start Video Rec") }

            OutlinedButton(
                onClick = { sendCommandOrShowError("CMD:stop_rec_video") },
                enabled = isRunning
            ) { Text("Stop Video Rec") }
        }

        Spacer(Modifier.height(10.dp))

        Text("Live streaming (device control)", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { sendCommandOrShowError("CMD:start_stream") },
                enabled = isRunning
            ) { Text("Start Streaming") }

            OutlinedButton(
                onClick = { sendCommandOrShowError("CMD:stop_stream") },
                enabled = isRunning
            ) { Text("Stop Streaming") }
        }

        Spacer(Modifier.height(18.dp))

        OutlinedButton(onClick = { showNetworkConfig = !showNetworkConfig }) {
            Text(if (showNetworkConfig) "Hide network configuration" else "Show network configuration")
        }

        if (showNetworkConfig) {
            Spacer(Modifier.height(14.dp))
            Text("Network / Gateway configuration", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text("Device IP (phone / wifi): $deviceIp")

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = udpPortText,
                onValueChange = { udpPortText = it },
                label = { Text("Local UDP Port (Device → Phone)") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = wsPortText,
                onValueChange = { wsPortText = it },
                label = { Text("WebSocket Port (Phone → Web)") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = httpPortText,
                onValueChange = { httpPortText = it },
                label = { Text("HTTP Port (VLC/Browser)") },
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            Text("Device (Nicla / PC) should send UDP to:")
            Text("$deviceIp:$currentUdpPort", style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(6.dp))
            Text("Web client connects to:")
            Text("ws://$deviceIp:$currentWsPort", style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(6.dp))
            Text("HTTP base:")
            Text(httpBase(), style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(12.dp))

            Text("Secret key for UDP authentication:")
            Text(secretKey, style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { secretKey = SecurityConfigState.regenerateSecretKey() }) {
                Text("Regenerate secret key")
            }

            Spacer(Modifier.height(6.dp))
            Text("All UDP packets must start with: \"$secretKey|\"", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = {
                val udp = udpPortText.toIntOrNull() ?: initialLocalPort
                val ws = wsPortText.toIntOrNull() ?: initialWebSocketPort
                val http = httpPortText.toIntOrNull() ?: initialHttpPort

                onUpdateLocalPort(udp)
                onUpdateWebSocketPort(ws)
                onUpdateHttpPort(http)

                onStart(udp, ws, http)
                isRunning = true
            },
            enabled = !isRunning
        ) { Text("Start service") }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                onStop()
                isRunning = false
            },
            enabled = isRunning
        ) { Text("Stop service") }

        Spacer(Modifier.height(12.dp))
    }
}
