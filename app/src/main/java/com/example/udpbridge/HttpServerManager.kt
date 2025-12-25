package com.example.udpbridge

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.*
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.min

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

    fun getPortOrNull(): Int? = server?.listeningPort

    private class GatewayHttpServer(
        port: Int,
        private val appFilesDir: File
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            val method = session.method?.name ?: "GET"

            // CORS preflight
            if (method == "OPTIONS") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "")
                    .also { addCors(it) }
            }

            // =======================
            // Serve WEB UI from assets
            // =======================
            if (method == "GET" && (uri == "/" || uri == "/index.html")) {
                return serveAssetText("web/index.html", "text/html; charset=utf-8")
            }

            // =======================
            // ✅ LIVE WAV for VLC: GET /live.wav
            // =======================
            if (method == "GET" && uri == "/live.wav") {
                // NOTE: match your sender sample-rate. Default here: 44100 mono PCM16
                val sampleRate = NetworkConfigState.audioSampleRateHz
                val channels = NetworkConfigState.audioChannels
                val bits = NetworkConfigState.audioBitDepth

                val header = wavStreamingHeader(sampleRate, channels, bits)
                val headerIn = ByteArrayInputStream(header)
                val pcmStream = LivePcmRingBuffer.openStream(startLive = true)
                val stream = SequenceInputStream(headerIn, pcmStream)

                return newChunkedResponse(Response.Status.OK, "audio/wav", stream).also { res ->
                    res.addHeader("Cache-Control", "no-store")
                    addCors(res)
                }
            }

            // =======================
            // GET /config.json
            // =======================
            if (method == "GET" && uri == "/config.json") {
                val obj = JSONObject()
                obj.put("udpPort", NetworkConfigState.localUdpPort)
                obj.put("webSocketPort", NetworkConfigState.webSocketPort)
                obj.put("httpPort", NetworkConfigState.httpPort)
                obj.put("httpBase", "http://${NetworkConfigState.deviceIp}:${NetworkConfigState.httpPort}")

                val audio = JSONObject()
                audio.put("container", "WAV (live)")
                audio.put("codec", "PCM")
                audio.put("channels", NetworkConfigState.audioChannels)
                audio.put("sampleRateHz", NetworkConfigState.audioSampleRateHz)
                audio.put("bitDepth", NetworkConfigState.audioBitDepth)
                obj.put("audioFormat", audio)

                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    obj.toString()
                ).also { addCors(it) }
            }

            // =======================
            // GET /api/recordings
            // =======================
            if (method == "GET" && uri == "/api/recordings") {
                val obj = JSONObject()
                obj.put("recordings", RecordingRepository.toJsonArray())
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    obj.toString()
                ).also { addCors(it) }
            }

            // =======================
            // VLC playlist for saved recordings
            // =======================
            if (method == "GET" && uri.startsWith("/vlc/audio/") && uri.endsWith(".m3u")) {
                val key = decodeKey(uri.removePrefix("/vlc/audio/").removeSuffix(".m3u"))
                val rec = RecordingRepository.findByIdOrName(key)
                    ?: return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain; charset=utf-8",
                        "Recording not found: $key"
                    ).also { addCors(it) }

                val ip = NetworkConfigState.deviceIp.trim()
                val port = NetworkConfigState.httpPort
                if (ip.isBlank()) {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "text/plain; charset=utf-8",
                        "deviceIp is not configured"
                    ).also { addCors(it) }
                }

                val mediaUrl = "http://$ip:$port/media/audio/${rec.id}"

                val body = buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXTINF:-1,${rec.name}")
                    appendLine(mediaUrl)
                }

                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/x-mpegURL; charset=utf-8",
                    body
                ).also { addCors(it) }
            }

            // =======================
            // GET /media/audio/<key>  (ORIGINAL WAV) + Range support
            // =======================
            if (method == "GET" && uri.startsWith("/media/audio/")) {
                val key = decodeKey(uri.removePrefix("/media/audio/"))
                return serveWavWithRange(session, key)
            }

            val info = """
                Gateway OK.
                Try:
                - /              (web UI)
                - /config.json
                - /api/recordings
                - /live.wav       (LIVE WAV for VLC)
                - /media/audio/<id>
                - /vlc/audio/<id>.m3u
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", info)
                .also { addCors(it) }
        }

        private fun wavStreamingHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = channels * (bitsPerSample / 8)

            // Streaming WAV: unknown sizes -> put 0xFFFFFFFF placeholders (VLC is ok with it)
            val riffSize = 0xFFFFFFFF.toInt()
            val dataSize = 0xFFFFFFFF.toInt()

            val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(riffSize)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))

            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16) // PCM fmt chunk size
            bb.putShort(1) // AudioFormat=1 (PCM)
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(byteRate)
            bb.putShort(blockAlign.toShort())
            bb.putShort(bitsPerSample.toShort())

            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataSize)

            return bb.array()
        }

        private fun serveAssetText(assetPath: String, mime: String): Response {
            return try {
                val ctx = AppContextHolder.appContext
                val text = ctx.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                newFixedLengthResponse(Response.Status.OK, mime, text).also { addCors(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Missing asset: $assetPath", e)
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "Missing asset: $assetPath"
                ).also { addCors(it) }
            }
        }

        private fun decodeKey(raw: String): String {
            val t = raw.trim()
            return try { URLDecoder.decode(t, "UTF-8") } catch (_: Exception) { t }
        }

        private fun serveWavWithRange(session: IHTTPSession, key: String): Response {
            val rec = RecordingRepository.findByIdOrName(key)
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "Recording not found: $key"
                ).also { addCors(it) }

            val file = File(rec.path)
            if (!file.exists()) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "File missing on device: ${rec.path}"
                ).also { addCors(it) }
            }

            val fileLen = file.length()
            val rangeHeader = session.headers["range"] ?: session.headers["Range"]

            if (rangeHeader.isNullOrBlank() || !rangeHeader.lowercase(Locale.US).startsWith("bytes=")) {
                val fis = FileInputStream(file)
                return newFixedLengthResponse(Response.Status.OK, "audio/wav", fis, fileLen).also { res ->
                    res.addHeader("Accept-Ranges", "bytes")
                    res.addHeader("Content-Length", fileLen.toString())
                    addCors(res)
                }
            }

            val range = rangeHeader.substringAfter("bytes=", "")
            val parts = range.split("-", limit = 2)
            val start = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            val endReq = parts.getOrNull(1)?.trim()?.toLongOrNull()

            if (start >= fileLen) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain; charset=utf-8", "")
                    .also { res ->
                        res.addHeader("Content-Range", "bytes */$fileLen")
                        addCors(res)
                    }
            }

            val end = min(endReq ?: (fileLen - 1), fileLen - 1)
            val len = (end - start + 1)

            val raf = RandomAccessFile(file, "r")
            raf.seek(start)
            val input = object : InputStream() {
                private var remaining = len
                override fun read(): Int {
                    if (remaining <= 0) return -1
                    val b = raf.read()
                    if (b >= 0) remaining--
                    if (remaining <= 0) close()
                    return b
                }
                override fun read(b: ByteArray, off: Int, l: Int): Int {
                    if (remaining <= 0) return -1
                    val toRead = min(l.toLong(), remaining).toInt()
                    val r = raf.read(b, off, toRead)
                    if (r > 0) remaining -= r.toLong()
                    if (remaining <= 0) close()
                    return r
                }
                override fun close() {
                    try { raf.close() } catch (_: Exception) {}
                }
            }

            return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "audio/wav", input, len).also { res ->
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Range", "bytes $start-$end/$fileLen")
                res.addHeader("Content-Length", len.toString())
                addCors(res)
            }
        }

        private fun addCors(res: Response) {
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
            res.addHeader("Access-Control-Allow-Headers", "*")
        }
    }
}
