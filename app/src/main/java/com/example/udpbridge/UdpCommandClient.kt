package com.example.udpbridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object UdpCommandClient {

    private const val TAG = "UdpCommandClient"

    /**
     * שולח פקודה ב-UDP למכשיר חיצוני (Nicla / PC)
     * הפורמט: SECRET|payload
     */
    fun sendCommand(remoteIp: String, remotePort: Int, payload: String) {
        val ip = remoteIp.trim()
        if (ip.isEmpty()) {
            Log.w(TAG, "Remote IP is empty, not sending command")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                val message = "${SecurityConfigState.secretKey}|$payload"
                val data = message.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(data, data.size, address, remotePort)

                Log.d(TAG, "Sending command to $ip:$remotePort -> $message")
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP command", e)
            }
        }
    }
}
