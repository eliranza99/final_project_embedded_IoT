package com.example.udpbridge

object NetworkConfigState {

    // ======= Local gateway ports (on the phone) =======
    var localUdpPort: Int = 5000
        private set

    var webSocketPort: Int = 8090
        private set

    var httpPort: Int = 8081
        private set

    // ======= Phone IP (Gateway IP) =======
    // This is the IP that browsers/VLC should use to reach the phone.
    var deviceIp: String = ""
        private set

    // ======= Remote device (Nicla/PC) for sending commands =======
    var remoteDeviceIp: String = ""
        private set

    var remoteUdpPort: Int = 6000
        private set

    // ======= Updates =======
    fun updateLocalUdpPort(port: Int) { localUdpPort = port }
    fun updateWebSocketPort(port: Int) { webSocketPort = port }
    fun updateHttpPort(port: Int) { httpPort = port }

    fun updateDeviceIp(ip: String) { deviceIp = ip.trim() }

    fun updateRemoteDevice(ip: String, port: Int) {
        remoteDeviceIp = ip.trim()
        remoteUdpPort = port
    }

    // Convenience (optional)
    fun httpBaseOrEmpty(): String =
        if (deviceIp.isBlank()) "" else "http://$deviceIp:$httpPort"
}
