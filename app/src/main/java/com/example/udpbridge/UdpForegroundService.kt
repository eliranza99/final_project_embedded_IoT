package com.example.udpbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap

class UdpForegroundService : Service() {

    companion object {
        const val EXTRA_UDP_PORT = "extra_udp_port"
        const val EXTRA_WS_PORT = "extra_ws_port"
        const val EXTRA_HTTP_PORT = "extra_http_port"
        private const val TAG = "UdpService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===== Ports =====
    private var udpPort: Int = 5000               // existing text/file protocol
    private var audioRtpPort: Int = 5004          // NEW: RTP audio in
    private var webSocketPort: Int = 8080
    private var httpPort: Int = 8081

    // ===== Sockets =====
    private var socketText: DatagramSocket? = null
    private var socketAudio: DatagramSocket? = null

    private val messageChannel = Channel<String>(capacity = Channel.BUFFERED)

    private data class IncomingFileState(
        val name: String,
        val totalChunks: Int,
        var receivedChunks: Int = 0,
        val outFile: File,
        val fos: FileOutputStream
    )

    private val incomingFiles = ConcurrentHashMap<String, IncomingFileState>()

    override fun onCreate() {
        super.onCreate()
        AudioPlaybackManager.init(applicationContext)
        startAsForeground()
        startMessageProcessor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newUdpPort = intent?.getIntExtra(EXTRA_UDP_PORT, udpPort) ?: udpPort
        val newWsPort = intent?.getIntExtra(EXTRA_WS_PORT, webSocketPort) ?: webSocketPort
        val newHttpPort = intent?.getIntExtra(EXTRA_HTTP_PORT, httpPort) ?: httpPort

        // keep our ports in sync with config (audio port is from state)
        audioRtpPort = NetworkConfigState.audioRtpPort

        // Text socket (5000)
        if (socketText == null || newUdpPort != udpPort) {
            udpPort = newUdpPort
            socketText?.close()
            startUdpTextListening()
        }

        // Audio RTP socket (5004)
        if (socketAudio == null) {
            startUdpAudioListening()
        }

        webSocketPort = newWsPort
        httpPort = newHttpPort

        WebSocketServerManager.startServer(webSocketPort)
        HttpServerManager.start(httpPort, filesDir)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { socketText?.close() } catch (_: Exception) {}
        try { socketAudio?.close() } catch (_: Exception) {}

        incomingFiles.values.forEach { st ->
            try { st.fos.close() } catch (_: Exception) {}
        }
        incomingFiles.clear()

        AudioStreamPlayer.stop()

        scope.cancel()
        messageChannel.close()

        WebSocketServerManager.stopServer()
        HttpServerManager.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val channelId = "udp_gateway_channel"
        val channelName = "UDP Gateway"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("UDP Gateway running")
            .setContentText("UDP:$udpPort  RTP:$audioRtpPort  WS:$webSocketPort  HTTP:$httpPort")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    // ======================
    // TEXT / FILE UDP (existing)
    // ======================
    private fun startUdpTextListening() {
        scope.launch {
            try {
                socketText = DatagramSocket(udpPort).apply {
                    receiveBufferSize = 2 * 1024 * 1024
                }
                val buffer = ByteArray(8192)
                Log.d(TAG, "Listening TEXT on UDP port $udpPort")

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketText?.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    messageChannel.trySend(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in TEXT UDP listener", e)
            }
        }
    }

    private fun startMessageProcessor() {
        scope.launch {
            for (msg in messageChannel) handleIncomingMessage(msg)
        }
    }

    private fun handleIncomingMessage(msg: String) {
        val clean = msg.trim()
        val parts = clean.split("|", limit = 2)
        if (parts.size != 2) return

        val receivedKey = parts[0].trim()
        val payload = parts[1].trim()
        if (receivedKey != SecurityConfigState.secretKey) return

        if (payload.startsWith("FILE_")) {
            handleFilePayload(payload)
            return
        }

        UdpSharedState.update(payload)
        WebSocketServerManager.broadcast("UDP_TEXT:$payload")
    }

    /**
     *  FILE_START:<name>:<totalChunks>
     *  FILE_CHUNK:<name>:<index>:<base64Data>
     *  FILE_END:<name>
     */
    private fun handleFilePayload(payload: String) {
        when {
            payload.startsWith("FILE_START:") -> {
                val parts = payload.split(":", limit = 3)
                if (parts.size < 3) return
                val name = parts[1].trim()
                val totalChunks = parts[2].toIntOrNull() ?: return
                if (totalChunks <= 0) return

                try {
                    val outDir = File(filesDir, "received")
                    if (!outDir.exists()) outDir.mkdirs()

                    val outFile = File(outDir, name)
                    if (outFile.exists()) outFile.delete()

                    val fos = FileOutputStream(outFile, true)
                    incomingFiles[name] = IncomingFileState(
                        name = name,
                        totalChunks = totalChunks,
                        outFile = outFile,
                        fos = fos
                    )

                    UdpSharedState.update("Receiving file: $name ($totalChunks chunks)")
                    WebSocketServerManager.broadcast("FILE_START:$name:$totalChunks")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed FILE_START for $name", e)
                }
            }

            payload.startsWith("FILE_CHUNK:") -> {
                val parts = payload.split(":", limit = 4)
                if (parts.size < 4) return
                val name = parts[1].trim()
                val index = parts[2].toIntOrNull() ?: return
                val base64Data = parts[3]

                val st = incomingFiles[name] ?: return

                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    st.fos.write(bytes)
                    st.receivedChunks++

                    WebSocketServerManager.broadcast(
                        "FILE_PROGRESS:$name:$index:${st.receivedChunks}/${st.totalChunks}"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing chunk $index for '$name'", e)
                }
            }

            payload.startsWith("FILE_END:") -> {
                val parts = payload.split(":", limit = 2)
                if (parts.size < 2) return
                val name = parts[1].trim()

                val st = incomingFiles.remove(name) ?: return
                try {
                    st.fos.flush()
                    st.fos.close()

                    val file = st.outFile
                    val recInfo = RecordingRepository.addRecordingFromFile(file)

                    UdpSharedState.updateLastReceivedFile(file.absolutePath)
                    UdpSharedState.updateLastReceivedRecordingKey(recInfo.id)

                    WebSocketServerManager.broadcast("FILE_END:$name")
                    WebSocketServerManager.broadcast("REC_LIST:${RecordingRepository.toJsonArray()}")
                    WebSocketServerManager.broadcast("FILE_RECEIVED:${recInfo.id}:${recInfo.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing file '$name'", e)
                }
            }
        }
    }

    // ======================
    // RTP AUDIO UDP (NEW)
    // ======================
    private fun startUdpAudioListening() {
        scope.launch {
            try {
                socketAudio = DatagramSocket(audioRtpPort).apply {
                    receiveBufferSize = 4 * 1024 * 1024
                }
                val buffer = ByteArray(2048) // RTP header + payload (1024 samples * 2 bytes = 2048)
                Log.d(TAG, "Listening AUDIO RTP on UDP port $audioRtpPort")

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketAudio?.receive(packet)

                    // RTP minimal header is 12 bytes
                    if (packet.length <= 12) continue

                    // payload = after 12 bytes (we assume no CSRC/ext for this stage)
                    val payloadLen = packet.length - 12
                    val payload = packet.data

                    // Convert big-endian PCM16 to little-endian for AudioTrack
                    val pcmLe = ByteArray(payloadLen)
                    var i = 0
                    while (i < payloadLen) {
                        val beHi = payload[12 + i]
                        val beLo = payload[12 + i + 1]
                        pcmLe[i] = beLo
                        pcmLe[i + 1] = beHi
                        i += 2
                    }

                    AudioStreamPlayer.startIfNeeded()
                    AudioStreamPlayer.writePcmLE(pcmLe, pcmLe.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AUDIO RTP listener", e)
            }
        }
    }
}
