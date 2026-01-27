package com.example.udpbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

object UdpSharedState {

    // ✅ מבנה נתונים לשמירת הודעה יחד עם חותמת זמן
    data class EventRecord(val message: String, val timestamp: Long)

    private val _lastMessage = MutableStateFlow("No messages yet")
    val lastMessage: StateFlow<String> = _lastMessage

    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive

    // ✅ שמירת רשימה של אובייקטי EventRecord (הודעה + זמן)
    private val eventHistory = Collections.synchronizedList(mutableListOf<EventRecord>())

    private val _lastReceivedFilePath = MutableStateFlow<String?>(null)
    val lastReceivedFilePath: StateFlow<String?> = _lastReceivedFilePath

    private val _lastReceivedRecordingKey = MutableStateFlow<String?>(null)
    val lastReceivedRecordingKey: StateFlow<String?> = _lastReceivedRecordingKey

    fun update(msg: String) {
        _lastMessage.value = msg
        // ✅ יצירת רשומה עם הזמן הנוכחי של הטלפון (במילי-שניות)
        val record = EventRecord(msg, System.currentTimeMillis())
        eventHistory.add(0, record)
        if (eventHistory.size > 10) eventHistory.removeAt(10)
    }

    fun updateSosStatus(active: Boolean) {
        _isSosActive.value = active
        val statusMsg = if (active) "EMERGENCY: SOS RECEIVED" else "NORMAL: SOS CLEARED"
        update(statusMsg)
    }

    fun getHistory(): List<EventRecord> = eventHistory.toList()

    fun updateLastReceivedFile(path: String) {
        _lastReceivedFilePath.value = path
    }

    fun updateLastReceivedRecordingKey(key: String) {
        _lastReceivedRecordingKey.value = key
    }
}