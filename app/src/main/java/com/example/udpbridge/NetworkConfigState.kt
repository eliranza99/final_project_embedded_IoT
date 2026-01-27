package com.example.udpbridge

/**
 * אובייקט מרכזי לניהול הגדרות הרשת והסטרימינג של הגייטוואי.
 */
object NetworkConfigState {

    // ======= Local gateway ports (on the phone) =======
    var localUdpPort: Int = 5000
        private set

    var audioRtpPort: Int = 5004
        private set

    var webSocketPort: Int = 8090
        private set

    var httpPort: Int = 8081
        private set

    // ======= LIVE audio format =======
    var audioSampleRateHz: Int = 44100
    var audioChannels: Int = 1
    var audioBitDepth: Int = 16

    // ✅ משתנה להיפוך סדר בתים (במקרה של רעש סטטי)
    var needsEndianFlip: Boolean = false
        private set

    // ======= Phone IP (Gateway IP) =======
    var deviceIp: String = ""
        private set

    // ======= ✅ עדכון: הגדרות המחשב (PC) למשלוח פקודות =======
    // הזנת ה-IP והפורט של המחשב שלך כערכי ברירת מחדל
    var remoteDeviceIp: String = "100.120.102.6"
        private set

    var remoteUdpPort: Int = 5005
        private set

    // ======= Updates =======
    fun updateLocalUdpPort(port: Int) { localUdpPort = port }
    fun updateAudioRtpPort(port: Int) { audioRtpPort = port }
    fun updateWebSocketPort(port: Int) { webSocketPort = port }
    fun updateHttpPort(port: Int) { httpPort = port }
    fun updateDeviceIp(ip: String) { deviceIp = ip.trim() }

    fun updateEndianFlip(flip: Boolean) {
        needsEndianFlip = flip
    }

    /**
     * פונקציה לעדכון כתובת היעד (PC/Nicla)
     */
    fun updateRemoteDevice(ip: String, port: Int) {
        remoteDeviceIp = ip.trim()
        remoteUdpPort = port
    }

    fun httpBaseOrEmpty(): String =
        if (deviceIp.isBlank()) "" else "http://$deviceIp:$httpPort"
}