package com.folio.hermeslive.call.ondemand

/** The last [capacity] samples of 16 kHz PCM (the pre-roll). Not thread-safe: the controller's lock guards it. */
class PcmRing(val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var start = 0
    var size = 0
        private set

    fun append(pcm: ShortArray, count: Int = pcm.size) {
        if (capacity == 0) return
        var from = 0
        var n = count
        if (n >= capacity) {
            from = n - capacity
            n = capacity
            start = 0
            size = 0
        }
        for (i in 0 until n) {
            val at = (start + size) % capacity
            buf[at] = pcm[from + i]
            if (size < capacity) size++ else start = (start + 1) % capacity
        }
    }

    /** Oldest first. */
    fun snapshot(): ShortArray = ShortArray(size) { buf[(start + it) % capacity] }

    fun clear() {
        start = 0
        size = 0
    }
}

/** A growing PCM buffer with a cap: past [capacity] the oldest samples go (and [dropped] counts them). */
class PcmQueue(private val capacity: Int) {
    private var buf = ShortArray(minOf(capacity, INITIAL))
    var size = 0
        private set
    var dropped = 0L
        private set

    fun append(pcm: ShortArray, count: Int = pcm.size) {
        if (count <= 0) return
        if (size + count > buf.size && buf.size < capacity) buf = buf.copyOf(minOf(capacity, maxOf(buf.size * 2, size + count)))
        val over = size + count - capacity
        if (over > 0) {
            val keep = (size - over).coerceAtLeast(0)
            System.arraycopy(buf, size - keep, buf, 0, keep)
            dropped += size - keep
            size = keep
        }
        val from = (count - capacity).coerceAtLeast(0)
        dropped += from
        System.arraycopy(pcm, from, buf, size, count - from)
        size += count - from
    }

    fun drain(): ShortArray = buf.copyOf(size).also { size = 0 }

    fun clear() {
        size = 0
        dropped = 0
    }

    private companion object {
        const val INITIAL = 16_000
    }
}

/** Cuts any chunking of 16 kHz PCM into the voice detector's frames ([frame] samples, as floats). */
class VadFramer(private val frame: Int) {
    private val pending = FloatArray(frame)
    private var filled = 0

    /** Each whole frame [pcm] completes, in order. */
    inline fun push(pcm: ShortArray, count: Int, onFrame: (FloatArray) -> Unit) {
        var i = 0
        while (i < count) {
            val n = take(pcm, i, count - i)
            i += n
            if (full()) onFrame(emit())
        }
    }

    fun take(pcm: ShortArray, from: Int, available: Int): Int {
        val n = minOf(frame - filled, available)
        for (k in 0 until n) pending[filled + k] = pcm[from + k] / 32_768f
        filled += n
        return n
    }

    fun full(): Boolean = filled == frame

    fun emit(): FloatArray {
        filled = 0
        return pending.copyOf()
    }

    fun reset() {
        filled = 0
    }
}
