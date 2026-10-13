package com.folio.hermes.telemetry

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field numbers from AOSP system/core/debuggerd/proto/tombstone.proto (checked 2026-10-13). */
class TombstoneTest {
    private class W {
        val out = ByteArrayOutputStream()
        fun varint(v: Long): W {
            var x = v
            while (x and 0x7fL.inv() != 0L) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
            return this
        }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun int(field: Int, v: Long) = tag(field, 0).varint(v)
        fun bytes(field: Int, b: ByteArray) = tag(field, 2).varint(b.size.toLong()).also { out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: W) = bytes(field, m.out.toByteArray())
        fun build() = out.toByteArray()
    }

    private fun frame(pc: Long, fn: String, off: Long, file: String) =
        W().int(1, pc).int(2, pc + 0x1000).int(3, 0x7fff).str(4, fn).int(5, off).str(6, file).str(8, "ab".repeat(20))

    @Test fun protoTombstoneKeepsSignalAbortAndCrashingBacktraceOnly() {
        val crashed = W().int(1, 1234).str(2, "AudioRecordJavaThread")
            .msg(3, W().str(1, "x0").int(2, 0xdead)) // registers: skipped
            .msg(4, frame(0x4f0b0, "abort", 164, "/apex/com.android.runtime/lib64/bionic/libc.so"))
            .msg(4, frame(0x2a1c, "webrtc::AudioRecordJni::DataIsRecorded", 52, "/data/app/lib/arm64/libjingle_peerconnection_so.so"))
            .msg(5, W().str(1, "x0").bytes(3, "my private words".toByteArray())) // memory dump: skipped
        val other = W().int(1, 1).str(2, "main").msg(4, frame(0x10, "epoll_wait", 8, "libc.so"))
        val tomb = W()
            .int(1, 1) // arch
            .str(2, "samsung/dm1q/dm1q:14/UP1A/123:user/release-keys")
            .int(5, 1000).int(6, 1234)
            .msg(10, W().int(1, 6).str(2, "SIGABRT").int(3, -1).str(4, "SI_TKILL"))
            .str(14, "FORTIFY: pthread_mutex_lock called on a destroyed mutex")
            .msg(15, W().str(1, "null pointer dereference"))
            .msg(16, W().int(1, 1).msg(2, other))
            .msg(16, W().int(1, 1234).msg(2, crashed))
            .msg(18, W().str(1, "main").str(2, "logcat line with my private words")) // log buffers: skipped
            .build()
        val out = Tombstone.summarize(tomb)!!
        assertTrue(out, out.startsWith("signal 6 (SIGABRT), code -1 (SI_TKILL), fault addr --------\n"))
        assertTrue(out, "Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex'" in out)
        assertTrue(out, "Cause: null pointer dereference" in out)
        assertTrue(out, "backtrace (thread AudioRecordJavaThread, tid 1234):" in out)
        assertTrue(out, "#00 pc 000000000004f0b0  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)" in out)
        assertTrue(out, "#01 pc 0000000000002a1c  /data/app/lib/arm64/libjingle_peerconnection_so.so (webrtc::AudioRecordJni::DataIsRecorded+52)" in out)
        assertFalse(out, "private" in out)
        assertFalse(out, "epoll_wait" in out)
    }

    @Test fun faultAddressIsShownWhenPresent() {
        val tomb = W().int(6, 7).msg(10, W().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, 0)).build()
        assertTrue(Tombstone.summarize(tomb)!!.startsWith("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0"))
    }

    @Test fun textTombstoneKeepsOnlyWhatCrashedWhere() {
        val text = """
            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            pid: 1234, tid: 1240, name: AudioThread  >>> com.folio.hermeslive <<<
            signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
            x0  0000000000000000  x1  0000007fd3a8c000
            backtrace:
                  #00 pc 000000000004f0b0  /system/lib64/libc.so (abort+164)
            memory near x1: 0000007fd3a8c000 6d79207072697661 7465 my private
        """.trimIndent().toByteArray()
        val out = Tombstone.summarize(text)!!
        assertTrue(out, "signal 11 (SIGSEGV)" in out)
        assertTrue(out, "#00 pc 000000000004f0b0" in out)
        assertFalse(out, "private" in out)
        assertFalse(out, "x1  0000007fd3a8c000" in out)
    }

    @Test fun garbageIsNull() {
        assertNull(Tombstone.summarize(byteArrayOf(0x0f, 0x7f, 0x01)))
        assertNull(Tombstone.summarize(ByteArray(0)))
        assertEquals(null, Tombstone.summarize(byteArrayOf(0x52, 0x10, 0x00)))
    }
}
