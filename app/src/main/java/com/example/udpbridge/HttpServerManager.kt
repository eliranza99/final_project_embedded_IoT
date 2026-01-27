package com.example.udpbridge

import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

object HttpServerManager {

    private const val TAG = "HttpServerManager"
    private var server: GatewayHttpServer? = null

    fun start(port: Int, filesDir: File) {
        if (server != null && server?.listeningPort == port) return
        stop()

        val s = GatewayHttpServer(port, filesDir)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) // Multi-threaded

        server = s
        Log.i(TAG, "HTTP server started on port $port (Multi-threaded mode)")
    }

    fun stop() {
        try { server?.stop() } catch (e: Exception) {
            Log.e(TAG, "Error stopping HTTP server", e)
        } finally { server = null }
    }

    private class GatewayHttpServer(
        val port: Int,
        private val appFilesDir: File
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            val method = session.method?.name ?: "GET"

            if (method == "OPTIONS") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "")
                    .also { addCors(it) }
            }

            if (method == "GET" && (uri == "/" || uri == "/index.html")) {
                return serveAssetText("web/index.html", "text/html; charset=utf-8")
            }

            if (method == "GET" && uri == "/command") {
                val params = session.parameters
                val action = params["val"]?.firstOrNull() ?: params["type"]?.firstOrNull() ?: ""

                val commandToSend = when(action) {
                    "RECORD_START" -> UdpCommandClient.Commands.RECORD_START
                    "RECORD_STOP"  -> UdpCommandClient.Commands.RECORD_STOP
                    "SOS"          -> UdpCommandClient.Commands.SOS
                    "GAIN_UP"      -> UdpCommandClient.Commands.GAIN_UP
                    "GAIN_DOWN"    -> UdpCommandClient.Commands.GAIN_DOWN
                    "STREAM_START" -> UdpCommandClient.Commands.STREAM_START
                    "STREAM_STOP"  -> UdpCommandClient.Commands.STREAM_STOP
                    else           -> action
                }

                if (commandToSend.isNotEmpty()) {
                    UdpCommandClient.sendAsciiCommand(
                        NetworkConfigState.remoteDeviceIp,
                        NetworkConfigState.remoteUdpPort,
                        commandToSend
                    )
                    WebSocketServerManager.broadcast("APP_EVENT:$commandToSend")
                }

                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK: $commandToSend")
                    .also { addCors(it) }
            }

            // ✅ Status API המעודכן עם זמן ותאריך לכל אירוע
            if (method == "GET" && uri == "/status") {
                val json = JSONObject().apply {
                    put("sosActive", UdpSharedState.isSosActive.value)
                    put("lastMessage", UdpSharedState.lastMessage.value)
                    put("deviceIp", NetworkConfigState.deviceIp)

                    val historyArray = JSONArray()
                    UdpSharedState.getHistory().forEach { record ->
                        val item = JSONObject()
                        item.put("msg", record.message)
                        item.put("time", record.timestamp)
                        historyArray.put(item)
                    }
                    put("history", historyArray)
                }

                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                    .also { addCors(it) }
            }

            if (method == "GET" && uri == "/live.wav") {
                val sampleRate = NetworkConfigState.audioSampleRateHz
                val header = wavStreamingHeader(sampleRate, 1, 16)
                val headerIn = ByteArrayInputStream(header)
                val pcmStream = LivePcmRingBuffer.openStream(startLive = true)
                val stream = SequenceInputStream(headerIn, pcmStream)

                return newChunkedResponse(Response.Status.OK, "audio/wav", stream).also { res ->
                    res.addHeader("Cache-Control", "no-store")
                    addCors(res)
                }
            }

            if (method == "GET" && uri == "/config.json") {
                val obj = JSONObject().apply {
                    put("httpPort", port)
                    put("deviceIp", NetworkConfigState.deviceIp)
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
                    .also { addCors(it) }
            }

            return newFixedLengthResponse(Response.Status.OK, "text/plain", "Gateway Active")
                .also { addCors(it) }
        }

        private fun serveAssetText(assetPath: String, mime: String): Response {
            return try {
                val ctx = AppContextHolder.appContext ?: throw Exception("Context null")
                val text = ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
                newFixedLengthResponse(Response.Status.OK, mime, text).also { addCors(it) }
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset Not Found")
            }
        }

        private fun wavStreamingHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = channels * (bitsPerSample / 8)
            val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray())
            bb.putInt(-1)
            bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray())
            bb.putInt(16)
            bb.putShort(1)
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(byteRate)
            bb.putShort(blockAlign.toShort())
            bb.putShort(bitsPerSample.toShort())
            bb.put("data".toByteArray())
            bb.putInt(-1)
            return bb.array()
        }

        private fun addCors(res: Response) {
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
            res.addHeader("Access-Control-Allow-Headers", "*")
        }
    }
}