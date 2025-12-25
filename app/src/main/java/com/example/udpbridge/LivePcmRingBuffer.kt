package com.example.udpbridge

import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * In-memory ring buffer for LIVE PCM bytes (little-endian).
 * Multi-client safe: each client gets its own read pointer.
 *
 * If a client falls behind more than buffer size -> it jumps near "live".
 */
object LivePcmRingBuffer {

    private const val CAPACITY_BYTES = 512 * 1024

    private val buf = ByteArray(CAPACITY_BYTES)

    private val lock = ReentrantLock()
    private val dataArrived = lock.newCondition()

    // absolute counters (monotonic)
    private var totalWritten: Long = 0L
    private var closed: Boolean = false

    fun write(pcmLe: ByteArray, len: Int) {
        if (len <= 0) return

        lock.withLock {
            if (closed) return

            var offset = 0
            while (offset < len) {
                val writePos = (totalWritten % CAPACITY_BYTES).toInt()
                val chunk = min(len - offset, CAPACITY_BYTES - writePos)
                System.arraycopy(pcmLe, offset, buf, writePos, chunk)
                offset += chunk
                totalWritten += chunk.toLong()
            }
            dataArrived.signalAll()
        }
    }

    fun openStream(startLive: Boolean = true): InputStream {
        val startAbs = lock.withLock {
            if (startLive) totalWritten else maxOf(0L, totalWritten - CAPACITY_BYTES.toLong())
        }
        return LiveStreamInput(startAbs)
    }

    fun closeAll() {
        lock.withLock {
            closed = true
            dataArrived.signalAll()
        }
    }

    private class LiveStreamInput(startAbs: Long) : InputStream() {
        private var readAbs: Long = startAbs
        private var localClosed = false

        override fun read(): Int {
            val one = ByteArray(1)
            val r = read(one, 0, 1)
            return if (r <= 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0

            lock.withLock {
                while (true) {
                    if (localClosed || closed) return -1

                    val available = totalWritten - readAbs

                    // If client fell behind too much, jump near live
                    if (available > CAPACITY_BYTES) {
                        readAbs = totalWritten - (CAPACITY_BYTES.toLong() / 4)
                        continue
                    }

                    if (available > 0) {
                        val toRead = min(len.toLong(), available).toInt()
                        var remaining = toRead
                        var outPos = off

                        while (remaining > 0) {
                            val p = (readAbs % CAPACITY_BYTES).toInt()
                            val chunk = min(remaining, CAPACITY_BYTES - p)
                            System.arraycopy(buf, p, b, outPos, chunk)
                            outPos += chunk
                            remaining -= chunk
                            readAbs += chunk.toLong()
                        }
                        return toRead
                    }

                    // No data yet -> wait a bit
                    dataArrived.awaitNanos(1_000_000_000L) // ~1s
                }
            }
        }

        override fun close() {
            localClosed = true
        }
    }
}
