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

    object Commands {
        const val GAIN_UP = "U"
        const val GAIN_DOWN = "D"
        const val RECORD_START = "R"
        const val RECORD_STOP = "S"
        const val STREAM_START = "T"
        const val STREAM_STOP = "X"
        const val SOS = "!"
    }

    fun sendAsciiCommand(remoteIp: String, remotePort: Int, command: String) {
        sendCommand(remoteIp, remotePort, command)
    }

    fun sendCommand(remoteIp: String, remotePort: Int, payload: String) {
        val ip = remoteIp.trim()
        if (ip.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                val data = payload.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(data, data.size, address, remotePort)
                socket.send(packet)
                socket.close()
                Log.d(TAG, "Command sent: $payload to $ip:$remotePort")
            } catch (e: Exception) { Log.e(TAG, "UDP send error", e) }
        }
    }
}