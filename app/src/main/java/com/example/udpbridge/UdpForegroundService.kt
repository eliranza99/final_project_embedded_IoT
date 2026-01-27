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
        private const val HEADER_SIZE = 12
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpPort: Int = 5000
    private var audioRtpPort: Int = 5004
    private var webSocketPort: Int = 8090
    private var httpPort: Int = 8081

    private var socketText: DatagramSocket? = null
    private var socketAudio: DatagramSocket? = null
    private var lastSequenceNumber: Int = -1

    // ✅ משתנה חדש למניעת הצפת הודעות SOS
    private var lastSosState: Boolean = false

    private val messageChannel = Channel<String>(capacity = Channel.BUFFERED)

    private data class IncomingFileState(
        val name: String, val totalChunks: Int, var receivedChunks: Int = 0,
        val outFile: File, val fos: FileOutputStream
    )
    private val incomingFiles = ConcurrentHashMap<String, IncomingFileState>()

    override fun onCreate() {
        super.onCreate()
        AudioPlaybackManager.init(applicationContext)
        startAsForeground()
        startMessageProcessor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        udpPort = intent?.getIntExtra(EXTRA_UDP_PORT, udpPort) ?: udpPort
        webSocketPort = intent?.getIntExtra(EXTRA_WS_PORT, webSocketPort) ?: webSocketPort
        httpPort = intent?.getIntExtra(EXTRA_HTTP_PORT, httpPort) ?: httpPort
        audioRtpPort = NetworkConfigState.audioRtpPort

        if (socketText == null) startUdpTextListening()
        if (socketAudio == null) startUdpAudioListening()

        WebSocketServerManager.startServer(webSocketPort)
        HttpServerManager.start(httpPort, filesDir)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { socketText?.close() } catch (_: Exception) {}
        try { socketAudio?.close() } catch (_: Exception) {}
        incomingFiles.values.forEach { try { it.fos.close() } catch (_: Exception) {} }
        LivePcmRingBuffer.closeAll()
        scope.cancel()
        WebSocketServerManager.stopServer()
        HttpServerManager.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val channelId = "udp_gateway_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "Gateway", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("IoT Gateway Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).build()
        startForeground(1, notification)
    }

    private fun startUdpTextListening() {
        scope.launch {
            try {
                socketText = DatagramSocket(udpPort).apply { receiveBufferSize = 1024 * 1024 }
                val buffer = ByteArray(8192)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketText?.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    messageChannel.trySend(text)
                }
            } catch (e: Exception) { Log.e(TAG, "UDP Text Error", e) }
        }
    }

    private fun startMessageProcessor() {
        scope.launch { for (msg in messageChannel) handleIncomingMessage(msg) }
    }

    private fun handleIncomingMessage(msg: String) {
        val payload = msg.trim()
        if (payload.isEmpty()) return
        if (payload.startsWith("FILE_")) { handleFilePayload(payload); return }

        when (payload) {
            "!", "SOS_ACTIVE" -> {
                // שולח רק אם הסטטוס ב-SharedState עוד לא מעודכן כ-Active
                if (!UdpSharedState.isSosActive.value) {
                    UdpSharedState.updateSosStatus(true)
                    WebSocketServerManager.broadcast("ALARM:SOS_ACTIVE")
                }
            }
            "CANCEL_SOS", "SOS_CANCELLED" -> {
                if (UdpSharedState.isSosActive.value) {
                    UdpSharedState.updateSosStatus(false)
                    WebSocketServerManager.broadcast("ALARM:SOS_OFF")
                }
            }
            else -> {
                UdpSharedState.update(payload)
                WebSocketServerManager.broadcast("APP_EVENT:$payload")
            }
        }
    }

    private fun handleFilePayload(payload: String) {
        try {
            when {
                payload.startsWith("FILE_START:") -> {
                    val parts = payload.split(":", limit = 3)
                    val name = parts[1].trim()
                    val outDir = File(filesDir, "received").apply { mkdirs() }
                    val fos = FileOutputStream(File(outDir, name))
                    incomingFiles[name] = IncomingFileState(name, parts[2].toInt(), 0, File(outDir, name), fos)
                }
                payload.startsWith("FILE_CHUNK:") -> {
                    val parts = payload.split(":", limit = 4)
                    val st = incomingFiles[parts[1].trim()] ?: return
                    st.fos.write(Base64.decode(parts[3], Base64.DEFAULT))
                }
                payload.startsWith("FILE_END:") -> {
                    val st = incomingFiles.remove(payload.removePrefix("FILE_END:").trim()) ?: return
                    st.fos.close()
                    RecordingRepository.addRecordingFromFile(st.outFile)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "File error", e) }
    }

    private fun startUdpAudioListening() {
        scope.launch {
            try {
                socketAudio = DatagramSocket(audioRtpPort).apply { receiveBufferSize = 2 * 1024 * 1024 }
                val buffer = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketAudio?.receive(packet)

                    if (packet.length < HEADER_SIZE) continue
                    val data = packet.data

                    if ((data[0].toInt() and 0xFF) != 0xAA || (data[1].toInt() and 0xFF) != 0xAA) continue

                    val currentSeq = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                    handlePacketLoss(currentSeq)
                    lastSequenceNumber = currentSeq

                    // ✅ זיהוי שינוי מצב SOS (Edge Detection)
                    val currentSosState = (data[9].toInt() and 0x01) != 0
                    if (currentSosState != lastSosState) {
                        lastSosState = currentSosState
                        handleIncomingMessage(if (currentSosState) "!" else "CANCEL_SOS")
                    }

                    val pLen = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
                    if (pLen > 0 && (HEADER_SIZE + pLen) <= packet.length) {
                        val raw = ByteArray(pLen)
                        System.arraycopy(data, HEADER_SIZE, raw, 0, pLen)
                        val final = if (NetworkConfigState.needsEndianFlip) flipEndianness(raw) else raw
                        LivePcmRingBuffer.write(final, pLen)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "CRITICAL ERROR in Audio Listening", e) }
        }
    }

    private fun flipEndianness(data: ByteArray): ByteArray {
        for (i in 0 until data.size - 1 step 2) {
            val t = data[i]; data[i] = data[i+1]; data[i+1] = t
        }
        return data
    }

    private fun handlePacketLoss(currentSeq: Int) {
        if (lastSequenceNumber != -1) {
            val expected = (lastSequenceNumber + 1) % 65536
            if (currentSeq != expected) {
                val gap = if (currentSeq > expected) currentSeq - expected else (65536 - expected) + currentSeq
                if (gap in 1..50) {
                    repeat(gap) { LivePcmRingBuffer.write(ByteArray(1024), 1024) }
                }
            }
        }
    }
}