package com.folio.hermeslive.memo

import com.folio.hermeslive.stt.Wav
import java.io.File
import java.io.RandomAccessFile

/**
 * 16-bit mono PCM WAV, streamed to disk: [open] writes a header with zero sizes, [write] appends
 * samples, [close] patches the sizes. A file cut off by a kill (sizes still zero, maybe half a
 * sample at the end) is made whole by [repair] from its length.
 */
class WavWriter private constructor(private val file: RandomAccessFile, private val sampleRate: Int) {
    private var bytes = 0L
    private val scratch = ByteArray(8_192)

    /** Samples written so far. */
    val samples: Long get() = bytes / 2

    /** Append [count] samples of [buffer] from [offset]. */
    fun write(buffer: ShortArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val n = minOf(end - i, scratch.size / 2)
            for (k in 0 until n) {
                val v = buffer[i + k].toInt()
                scratch[2 * k] = v.toByte()
                scratch[2 * k + 1] = (v shr 8).toByte()
            }
            file.write(scratch, 0, 2 * n)
            bytes += 2 * n
            i += n
        }
    }

    /** Patch the header and close (fsynced: the record that counts this chunk is written after). */
    fun close() {
        file.use {
            header(it, bytes, sampleRate)
            it.fd.sync()
        }
    }

    companion object {
        const val HEADER = Wav.HEADER

        fun open(target: File, sampleRate: Int): WavWriter {
            target.parentFile?.mkdirs()
            val f = RandomAccessFile(target, "rw")
            f.setLength(0)
            header(f, 0, sampleRate)
            return WavWriter(f, sampleRate)
        }

        /**
         * Make [target] a whole WAV again after a kill: the sizes from its length, a trailing odd
         * byte dropped. Returns its samples (0 for a missing or header-only file).
         */
        fun repair(target: File, sampleRate: Int): Long {
            if (!target.exists()) return 0
            RandomAccessFile(target, "rw").use { f ->
                if (f.length() < HEADER) {
                    f.setLength(0)
                    header(f, 0, sampleRate)
                    return 0
                }
                val data = (f.length() - HEADER) and 1L.inv()
                f.setLength(HEADER + data)
                header(f, data, sampleRate)
                f.fd.sync()
                return data / 2
            }
        }

        private fun header(f: RandomAccessFile, dataBytes: Long, sampleRate: Int) {
            f.seek(0)
            f.write(Wav.header(dataBytes, sampleRate))
            f.seek(HEADER + dataBytes)
        }
    }
}
