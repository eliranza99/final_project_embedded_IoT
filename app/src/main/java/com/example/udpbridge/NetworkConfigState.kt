package com.example.udpbridge

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

    // ======= LIVE audio format (must match your sender) =======
    // If your sender is 48000Hz, change audioSampleRateHz to 48000.
    var audioSampleRateHz: Int = 44100
        private set

    var audioChannels: Int = 1
        private set

    var audioBitDepth: Int = 16
        private set

    // ======= Phone IP (Gateway IP) =======
    var deviceIp: String = ""
        private set

    // ======= Remote device (Nicla/PC) for sending commands =======
    var remoteDeviceIp: String = ""
        private set

    var remoteUdpPort: Int = 6000
        private set

    // ======= Updates =======
    fun updateLocalUdpPort(port: Int) { localUdpPort = port }
    fun updateAudioRtpPort(port: Int) { audioRtpPort = port }

    fun updateWebSocketPort(port: Int) { webSocketPort = port }
    fun updateHttpPort(port: Int) { httpPort = port }

    fun updateDeviceIp(ip: String) { deviceIp = ip.trim() }

    fun updateRemoteDevice(ip: String, port: Int) {
        remoteDeviceIp = ip.trim()
        remoteUdpPort = port
    }

    fun httpBaseOrEmpty(): String =
        if (deviceIp.isBlank()) "" else "http://$deviceIp:$httpPort"
}
