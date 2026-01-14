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

    private var udpPort: Int = 5000
    private var audioRtpPort: Int = 5004
    private var webSocketPort: Int = 8080
    private var httpPort: Int = 8081

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
        udpPort = intent?.getIntExtra(EXTRA_UDP_PORT, udpPort) ?: udpPort
        webSocketPort = intent?.getIntExtra(EXTRA_WS_PORT, webSocketPort) ?: webSocketPort
        httpPort = intent?.getIntExtra(EXTRA_HTTP_PORT, httpPort) ?: httpPort
        audioRtpPort = NetworkConfigState.audioRtpPort

        if (socketText == null) startUdpTextListening()
        if (socketAudio == null) startUdpAudioListening()

        WebSocketServerManager.startServer(webSocketPort)
        HttpServerManager.start(httpPort, filesDir)

        Log.d(TAG, "Service Started: UDP:$udpPort, WS:$webSocketPort, HTTP:$httpPort")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { socketText?.close() } catch (_: Exception) {}
        try { socketAudio?.close() } catch (_: Exception) {}

        incomingFiles.values.forEach {
            try { it.fos.flush(); it.fos.close() } catch (_: Exception) {}
        }
        incomingFiles.clear()

        AudioStreamPlayer.stop()
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
            .setOngoing(true)
            .build()
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
        scope.launch {
            for (msg in messageChannel) handleIncomingMessage(msg)
        }
    }

    private fun handleIncomingMessage(msg: String) {
        val payload = msg.trim()
        if (payload.isEmpty()) return

        if (payload.startsWith("FILE_")) {
            handleFilePayload(payload)
            return
        }

        when (payload) {
            "!", "SOS_ACTIVE" -> {
                UdpSharedState.update("EMERGENCY: SOS RECEIVED")
                WebSocketServerManager.broadcast("ALARM:SOS_ACTIVE")
            }
            "CANCEL_SOS", "SOS_CANCELLED" -> {
                UdpSharedState.update("NORMAL: SOS CLEARED")
                WebSocketServerManager.broadcast("ALARM:SOS_OFF")
            }
            else -> {
                UdpSharedState.update(payload)
            }
        }
        WebSocketServerManager.broadcast("UDP_TEXT:$payload")
    }

    private fun handleFilePayload(payload: String) {
        try {
            when {
                payload.startsWith("FILE_START:") -> {
                    val parts = payload.split(":", limit = 3)
                    val name = parts[1].trim()
                    val total = parts[2].toInt()
                    val outDir = File(filesDir, "received").apply { mkdirs() }
                    val outFile = File(outDir, name)
                    val fos = FileOutputStream(outFile)
                    incomingFiles[name] = IncomingFileState(name, total, 0, outFile, fos)
                    WebSocketServerManager.broadcast("FILE_START:$name")
                }
                payload.startsWith("FILE_CHUNK:") -> {
                    val parts = payload.split(":", limit = 4)
                    val name = parts[1].trim()
                    val dataBase64 = parts[3]
                    val st = incomingFiles[name] ?: return
                    val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
                    st.fos.write(bytes)
                    st.receivedChunks++
                }
                payload.startsWith("FILE_END:") -> {
                    val name = payload.removePrefix("FILE_END:").trim()
                    val st = incomingFiles.remove(name) ?: return
                    st.fos.flush()
                    st.fos.close()
                    RecordingRepository.addRecordingFromFile(st.outFile)
                    WebSocketServerManager.broadcast("FILE_END:$name")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "File handling error", e) }
    }

    private fun startUdpAudioListening() {
        scope.launch {
            try {
                socketAudio = DatagramSocket(audioRtpPort).apply { receiveBufferSize = 2 * 1024 * 1024 }
                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketAudio?.receive(packet)
                    if (packet.length < 12) continue

                    val data = packet.data
                    // בדיקת Header AA AA
                    if ((data[0].toInt() and 0xFF) == 0xAA && (data[1].toInt() and 0xFF) == 0xAA) {

                        // בדיקת דגל SOS בבייט ה-9
                        val flags = data[9].toInt() and 0xFF
                        if ((flags and 0x01) != 0) {
                            handleIncomingMessage("!")
                        }

                        // חילוץ PCM (החל מבייט 12)
                        val pcmLen = packet.length - 12
                        val pcmData = ByteArray(pcmLen)
                        System.arraycopy(data, 12, pcmData, 0, pcmLen)

                        LivePcmRingBuffer.write(pcmData, pcmLen)
                        AudioStreamPlayer.startIfNeeded()
                        AudioStreamPlayer.writePcmLE(pcmData, pcmLen)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Audio UDP Error", e) }
        }
    }
}