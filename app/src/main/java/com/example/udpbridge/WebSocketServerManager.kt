package com.example.udpbridge

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

object WebSocketServerManager {

    private const val TAG = "WebSocketServer"

    private var server: SimpleWsServer? = null

    // מי ה-OWNER כרגע (הדפדפן הראשון שנכנס)
    @Volatile private var ownerConn: WebSocket? = null

    // לשמירת role לפי conn
    private val roles = ConcurrentHashMap<WebSocket, String>() // "OWNER" / "VIEWER"

    fun startServer(port: Int) {
        if (server != null && server!!.port == port) return
        stopServer()

        val s = SimpleWsServer(InetSocketAddress(port))
        s.start()
        server = s

        Log.i(TAG, "WebSocket server started on port $port")
    }

    fun stopServer() {
        try {
            server?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        } finally {
            server = null
            ownerConn = null
            roles.clear()
        }
    }

    fun broadcast(message: String) {
        server?.broadcast(message)
    }

    private class SimpleWsServer(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.i(TAG, "Client connected: ${conn.remoteSocketAddress}")

            // קובעים role: הראשון OWNER, השאר VIEWER
            val role = assignRole(conn)
            conn.send("ROLE:$role")
            conn.send("MSG: Welcome. Your role is $role")

            // שולחים רשימת הקלטות התחלתית
            val arr = RecordingRepository.toJsonArray()
            conn.send("REC_LIST:$arr")

            // מודיעים לכולם כמה מחוברים (נחמד לדיבוג)
            broadcast("MSG: clients=${connections.size}, owner=${ownerConn?.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Client disconnected: ${conn.remoteSocketAddress} ($code $reason)")

            val wasOwner = (conn == ownerConn)
            roles.remove(conn)

            if (wasOwner) {
                ownerConn = null
                // מעבירים בעלות לדפדפן הבא (אם יש)
                val next = pickNextOwner()
                if (next != null) {
                    ownerConn = next
                    roles[next] = "OWNER"
                    next.send("ROLE:OWNER")
                    next.send("MSG: You are now OWNER (control granted)")
                    broadcast("MSG: OWNER changed to ${next.remoteSocketAddress}")
                } else {
                    broadcast("MSG: OWNER disconnected, no clients left to own control")
                }
            }

            broadcast("MSG: clients=${connections.size}, owner=${ownerConn?.remoteSocketAddress}")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            Log.d(TAG, "From Web client: $message")

            // תמיד מותר:
            when {
                message == "WEB:REQ_RECORDINGS" -> {
                    val arr = RecordingRepository.toJsonArray()
                    conn.send("REC_LIST:$arr")
                    return
                }
            }

            // פקודות שליטה — רק OWNER
            when {
                message == "WEB:CMD_START_REC" -> {
                    if (!isOwner(conn)) {
                        conn.send("MSG: NOT_OWNER (view-only)")
                        conn.send("ROLE:VIEWER")
                        return
                    }

                    val ip = NetworkConfigState.remoteDeviceIp
                    val port = NetworkConfigState.remoteUdpPort
                    if (ip.isBlank()) {
                        conn.send("MSG: No remote device IP configured")
                    } else {
                        UdpCommandClient.sendCommand(ip, port, "CMD:start_rec_audio")
                        UdpSharedState.update("WEB(OWNER) → UDP: start_rec_audio to $ip:$port")
                        broadcast("MSG: OWNER sent start_rec_audio")
                    }
                    return
                }

                message == "WEB:CMD_STOP_REC" -> {
                    if (!isOwner(conn)) {
                        conn.send("MSG: NOT_OWNER (view-only)")
                        conn.send("ROLE:VIEWER")
                        return
                    }

                    val ip = NetworkConfigState.remoteDeviceIp
                    val port = NetworkConfigState.remoteUdpPort
                    if (ip.isBlank()) {
                        conn.send("MSG: No remote device IP configured")
                    } else {
                        UdpCommandClient.sendCommand(ip, port, "CMD:stop_rec_audio")
                        UdpSharedState.update("WEB(OWNER) → UDP: stop_rec_audio to $ip:$port")
                        broadcast("MSG: OWNER sent stop_rec_audio")
                    }
                    return
                }

                // אם תרצה גם Delete/Streaming רק ל-OWNER – נשאיר כאן כבר תבנית:
                message.startsWith("WEB:DELETE_RECORDING:") -> {
                    if (!isOwner(conn)) {
                        conn.send("MSG: NOT_OWNER (view-only)")
                        conn.send("ROLE:VIEWER")
                        return
                    }
                    conn.send("MSG: Delete is not implemented yet in Stage A")
                    return
                }

                else -> {
                    // דיבוג / echo
                    conn.send("ECHO:$message")
                }
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "WebSocket error", ex)
        }

        override fun onStart() {
            Log.i(TAG, "WebSocket server started (onStart)")
        }

        private fun assignRole(conn: WebSocket): String {
            // אם אין OWNER — הקליינט הזה נהיה OWNER
            if (ownerConn == null) {
                ownerConn = conn
                roles[conn] = "OWNER"
                return "OWNER"
            }
            roles[conn] = "VIEWER"
            return "VIEWER"
        }

        private fun isOwner(conn: WebSocket): Boolean = (conn == ownerConn)

        private fun pickNextOwner(): WebSocket? {
            // connections הוא Set<WebSocket> של השרת.
            // ניקח "ראשון" קיים (לא מובטח סדר, אבל עובד טוב מספיק ל-Stage A)
            val it = connections.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c.isOpen) return c
            }
            return null
        }
    }
}
