package com.example.udpbridge

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

object AudioPlaybackManager {

    private const val TAG = "AudioPlaybackManager"

    private var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun play(path: String) {
        val ctx = appContext ?: run {
            Log.e(TAG, "play() called but appContext is null")
            return
        }

        try {
            stop() // לעצור ניגון קודם אם היה

            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                Log.d(TAG, "Playback completed")
                stop()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                stop()
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp

            Log.i(TAG, "Playing file: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing file: $path", e)
            stop()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
    }
}
