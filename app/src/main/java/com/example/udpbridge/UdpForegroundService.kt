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
    private var udpPort: Int = 5000
    private var audioRtpPort: Int = 5004
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

        audioRtpPort = NetworkConfigState.audioRtpPort

        if (socketText == null || newUdpPort != udpPort) {
            udpPort = newUdpPort
            socketText?.close()
            startUdpTextListening()
        }

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
        LivePcmRingBuffer.closeAll()
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
            .setContentText("UDP:$udpPort  Audio:$audioRtpPort  WS:$webSocketPort")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    // ======================
    // TEXT / COMMAND UDP (Clean ASCII - No Secret Key)
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
        val payload = msg.trim()
        if (payload.isEmpty()) return

        // הוספת לוג לניפוי שגיאות
        Log.d(TAG, "Processing payload: $payload")

        if (payload.startsWith("FILE_")) {
            handleFilePayload(payload)
            return
        }

        // בדיקה אם מדובר בסיגנל SOS בפורמט ASCII (!)
        if (payload == "!") {
            handleSosDetected()
        }

        UdpSharedState.update(payload)
        WebSocketServerManager.broadcast("UDP_TEXT:$payload")
    }

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
                    incomingFiles[name] = IncomingFileState(name, totalChunks, 0, outFile, fos)
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
                    WebSocketServerManager.broadcast("FILE_PROGRESS:$name:${st.receivedChunks}/${st.totalChunks}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing chunk $index", e)
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
                    RecordingRepository.addRecordingFromFile(st.outFile)
                    WebSocketServerManager.broadcast("FILE_END:$name")
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing file", e)
                }
            }
        }
    }

    // ======================
    // CUSTOM IoT AUDIO UDP (12-Byte Header Processing)
    // ======================
    private fun startUdpAudioListening() {
        scope.launch {
            try {
                socketAudio = DatagramSocket(audioRtpPort).apply {
                    receiveBufferSize = 4 * 1024 * 1024
                }
                val buffer = ByteArray(2048)
                Log.d(TAG, "Listening CUSTOM IoT AUDIO on port $audioRtpPort")

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socketAudio?.receive(packet)

                    // בדיקת גודל מינימלי ל-Header (12 בתים)
                    if (packet.length <= 12) continue

                    val data = packet.data

                    // 1. אימות Sync Word (0xAAAA)
                    val sync1 = data[0].toInt() and 0xFF
                    val sync2 = data[1].toInt() and 0xFF
                    if (sync1 != 0xAA || sync2 != 0xAA) continue

                    // 2. קריאת Flags (בית מספר 9)
                    val flags = data[9].toInt() and 0xFF
                    val isSosSet = (flags and 0x01) != 0       // Bit 0: SOS
                    val isBigEndian = (flags and 0x02) != 0    // Bit 1: Big-Endian flag

                    // 3. טיפול ב-SOS במידה וזוהה ב-Header
                    if (isSosSet) {
                        handleSosDetected()
                    }

                    // 4. קילוף ה-Header וחילוץ האודיו
                    val payloadLen = packet.length - 12
                    val processedPcm = ByteArray(payloadLen)

                    if (isBigEndian) {
                        // המרה מ-MSB ל-LSB עבור נגן האנדרואיד
                        var i = 0
                        while (i < payloadLen - 1) {
                            processedPcm[i] = data[12 + i + 1]
                            processedPcm[i + 1] = data[12 + i]
                            i += 2
                        }
                    } else {
                        // המידע כבר ב-LSB, העתקה ישירה לביצועים אופטימליים
                        System.arraycopy(data, 12, processedPcm, 0, payloadLen)
                    }

                    // 5. הפצה (Bridging) לשרת ה-Web ולנגן המקומי
                    LivePcmRingBuffer.write(processedPcm, processedPcm.size)
                    AudioStreamPlayer.startIfNeeded()
                    AudioStreamPlayer.writePcmLE(processedPcm, processedPcm.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Audio Listener", e)
            }
        }
    }

    private fun handleSosDetected() {
        Log.w(TAG, "!!! SOS SIGNAL DETECTED !!!")
        UdpSharedState.update("EMERGENCY: SOS RECEIVED")
        WebSocketServerManager.broadcast("ALARM:SOS_ACTIVE")
    }
}