package com.example.udpbridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

object AudioStreamPlayer {

    private const val TAG = "AudioStreamPlayer"

    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var track: AudioTrack? = null

    @Synchronized
    fun startIfNeeded() {
        if (track != null) return

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufSize = (minBuf * 2).coerceAtLeast(4096)

        val t = AudioTrack(
            AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNELS)
                .setEncoding(ENCODING)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        t.play()
        track = t
        Log.i(TAG, "AudioTrack started (buf=$bufSize)")
    }

    fun writePcmLE(pcmLe: ByteArray, len: Int) {
        val t = track ?: return
        try {
            t.write(pcmLe, 0, len)
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        } finally {
            track = null
            Log.i(TAG, "AudioTrack stopped")
        }
    }
}
