package com.example.udpbridge

import android.util.Log
import fi.iki.elonen.NanoHTTPD
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
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = s
        Log.i(TAG, "HTTP server started on port $port")
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

            // 1. CORS Preflight
            if (method == "OPTIONS") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "")
                    .also { addCors(it) }
            }

            // 2. Serve Web Dashboard
            if (method == "GET" && (uri == "/" || uri == "/index.html")) {
                return serveAssetText("web/index.html", "text/html; charset=utf-8")
            }

            // 3. Command API (Remote Control for Nicla)
            if (method == "GET" && uri == "/command") {
                val params = session.parameters
                val action = params["type"]?.firstOrNull() ?: ""

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

                UdpCommandClient.sendAsciiCommand(
                    NetworkConfigState.remoteDeviceIp,
                    NetworkConfigState.remoteUdpPort,
                    commandToSend
                )
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK: $commandToSend")
                    .also { addCors(it) }
            }

            // 4. Status API (Polling with SOS fix)
            if (method == "GET" && uri == "/status") {
                val lastMsg = UdpSharedState.lastMessage.value
                // תיקון הלופ: SOS פעיל רק אם ההודעה מכילה SOS ולא מכילה CLEARED
                val isSosActive = (lastMsg.contains("SOS") || lastMsg.contains("EMERGENCY") || lastMsg.trim() == "!")
                        && !lastMsg.contains("CLEARED")

                val json = JSONObject().apply {
                    put("sosActive", isSosActive)
                    put("lastMessage", lastMsg)
                    put("deviceIp", NetworkConfigState.deviceIp)
                }

                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                    .also { addCors(it) }
            }

            // 5. LIVE Audio Stream (WAV) - החלק שהיה חסר!
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

            // 6. Config JSON
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
            bb.putInt(-1) // Unknown length for live stream
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