package com.example.udpbridge

import android.media.MediaMetadataRetriever
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object RecordingRepository {

    private const val TAG = "RecordingRepo"

    data class RecordingInfo(
        val id: String,
        val name: String,
        val path: String,
        val timestamp: Long,
        val sizeBytes: Long,
        val durationSec: Long?
    )

    private val recordings = CopyOnWriteArrayList<RecordingInfo>()

    /** ✅ חדש: טוען הקלטות קיימות מהתיקייה received כדי שלא נאבד רשימה אחרי Restart */
    fun scanReceivedDir(receivedDir: File) {
        try {
            if (!receivedDir.exists() || !receivedDir.isDirectory) {
                Log.i(TAG, "scanReceivedDir: dir missing: ${receivedDir.absolutePath}")
                return
            }

            val files = receivedDir.listFiles()?.filter { it.isFile } ?: emptyList()
            if (files.isEmpty()) return

            // לא להכניס כפולים
            val existingPaths = recordings.map { it.path }.toHashSet()

            for (f in files) {
                if (existingPaths.contains(f.absolutePath)) continue

                // נשתמש ב-lastModified כ-timestamp אם אין לנו אחר
                val ts = f.lastModified()

                val durationSec: Long? = try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(f.absolutePath)
                    val dMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    mmr.release()
                    dMs?.div(1000L)
                } catch (_: Exception) {
                    null
                }

                val rec = RecordingInfo(
                    id = f.name + "_" + ts,
                    name = f.name,
                    path = f.absolutePath,
                    timestamp = ts,
                    sizeBytes = f.length(),
                    durationSec = durationSec
                )

                recordings.add(rec)
                Log.i(TAG, "scanReceivedDir added: $rec")
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanReceivedDir failed", e)
        }
    }

    fun addRecordingFromFile(file: File): RecordingInfo {
        val size = file.length()
        val ts = System.currentTimeMillis()

        val durationSec: Long? = try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val dMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            mmr.release()
            dMs?.div(1000L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read duration for ${file.name}", e)
            null
        }

        val rec = RecordingInfo(
            id = file.name + "_" + ts,
            name = file.name,
            path = file.absolutePath,
            timestamp = ts,
            sizeBytes = size,
            durationSec = durationSec
        )

        recordings.add(rec)
        Log.i(TAG, "Recording added: $rec")
        return rec
    }

    fun findByIdOrName(key: String): RecordingInfo? {
        recordings.find { it.id == key }?.let { return it }
        return recordings.find { it.name == key }
    }

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (r in recordings) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("name", r.name)
            obj.put("timestamp", r.timestamp)
            obj.put("sizeBytes", r.sizeBytes)
            r.durationSec?.let { obj.put("durationSec", it) }
            arr.put(obj)
        }
        return arr
    }
}
