package com.example.udpbridge

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.*
import java.net.URLDecoder
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
            // GET /config.json
            // =======================
            if (method == "GET" && uri == "/config.json") {
                val obj = JSONObject()
                obj.put("udpPort", NetworkConfigState.localUdpPort)
                obj.put("webSocketPort", NetworkConfigState.webSocketPort)
                obj.put("httpPort", NetworkConfigState.httpPort)
                obj.put("httpBase", "http://${NetworkConfigState.deviceIp}:${NetworkConfigState.httpPort}")

                val audio = JSONObject()
                audio.put("container", "WAV")
                audio.put("codec", "PCM")
                audio.put("channels", 1)
                audio.put("sampleRateHz", 48000)
                audio.put("bitDepth", 24)
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
            // ✅ VLC playlist: /vlc/audio/<key>.m3u -> points to ORIGINAL WAV (NO compat)
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
                - /media/audio/<id>
                - /vlc/audio/<id>.m3u
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", info)
                .also { addCors(it) }
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

            // No range -> normal 200
            if (rangeHeader.isNullOrBlank() || !rangeHeader.lowercase(Locale.US).startsWith("bytes=")) {
                val fis = FileInputStream(file)
                return newFixedLengthResponse(Response.Status.OK, "audio/wav", fis, fileLen).also { res ->
                    res.addHeader("Accept-Ranges", "bytes")
                    res.addHeader("Content-Length", fileLen.toString())
                    addCors(res)
                }
            }

            // Range parse: bytes=start-end
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
