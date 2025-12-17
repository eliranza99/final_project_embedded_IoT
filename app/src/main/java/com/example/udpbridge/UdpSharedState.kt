package com.example.udpbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object UdpSharedState {

    private val _lastMessage = MutableStateFlow("No messages yet")
    val lastMessage: StateFlow<String> = _lastMessage

    // נתיב הקובץ האחרון שהתקבל (אופציונלי)
    private val _lastReceivedFilePath = MutableStateFlow<String?>(null)
    val lastReceivedFilePath: StateFlow<String?> = _lastReceivedFilePath

    // ✅ חדש: המזהה של ההקלטה האחרונה שהסתיימה (הכי חשוב לניגון דרך HTTP/VLC)
    private val _lastReceivedRecordingKey = MutableStateFlow<String?>(null)
    val lastReceivedRecordingKey: StateFlow<String?> = _lastReceivedRecordingKey

    fun update(msg: String) {
        _lastMessage.value = msg
    }

    fun updateLastReceivedFile(path: String) {
        _lastReceivedFilePath.value = path
    }

    fun updateLastReceivedRecordingKey(key: String) {
        _lastReceivedRecordingKey.value = key
    }
}
