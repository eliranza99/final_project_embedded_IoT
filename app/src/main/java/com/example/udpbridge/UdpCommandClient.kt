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

    // טבלת ASCII המוסכמת לפקודות הפרויקט
    object Commands {
        const val GAIN_UP = "U"      // הגברת הגבר
        const val GAIN_DOWN = "D"    // הנמכת הגבר
        const val RECORD_START = "R" // תחילת הקלטה ב-SD
        const val RECORD_STOP = "S"  // עצירת הקלטה ב-SD
        const val STREAM_START = "T" // תחילת שידור אודיו
        const val STREAM_STOP = "X"  // עצירת שידור אודיו
        const val SOS = "!"          // אות מצוקה
    }

    /**
     * שולחת פקודת ASCII ישירה ללא Secret Key
     */
    fun sendAsciiCommand(remoteIp: String, remotePort: Int, command: String) {
        sendCommand(remoteIp, remotePort, command)
    }

    fun sendCommand(remoteIp: String, remotePort: Int, payload: String) {
        val ip = remoteIp.trim()
        if (ip.isEmpty()) {
            Log.w(TAG, "Remote IP is empty, not sending command")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                // שליחת ה-Payload בלבד ללא שרשור מפתח
                val data = payload.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(data, data.size, address, remotePort)

                Log.d(TAG, "Sending clean command to $ip:$remotePort -> $payload")
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP command", e)
            }
        }
    }
}