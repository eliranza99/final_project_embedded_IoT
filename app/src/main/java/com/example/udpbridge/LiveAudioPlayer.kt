package com.example.udpbridge

import android.media.*
import android.util.Log

object LiveAudioPlayer {
    private const val TAG = "LiveAudioPlayer"

    private var track: AudioTrack? = null
    private var sampleRate: Int = 44100

    fun start(sr: Int = 44100) {
        if (track != null) return
        sampleRate = sr

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf * 4,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply { play() }

        Log.i(TAG, "Live audio started @ $sampleRate Hz")
    }

    fun stop() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
        track = null
        Log.i(TAG, "Live audio stopped")
    }

    fun writePcm16Le(pcmLe: ByteArray) {
        track?.write(pcmLe, 0, pcmLe.size)
    }
}
