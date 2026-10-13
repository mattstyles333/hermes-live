package com.folio.hermeslive.lab

/**
 * Makes the `bpe.vocab` file sherpa-onnx needs to encode hotwords ("piece<TAB>score" per line,
 * as k2-fsa's scripts/export_bpe_vocab.py writes it), on the phone, without sentencepiece.
 */
object BpeVocab {
    data class Piece(val piece: String, val score: Float)

    /**
     * Reads the pieces of a sentencepiece ModelProto (`bpe.model`): field 1 is the repeated
     * SentencePiece message { 1: piece (string), 2: score (float), 3: type }. Other fields
     * (trainer and normalizer specs) are skipped.
     */
    fun piecesFromModel(bytes: ByteArray): List<Piece> {
        val out = ArrayList<Piece>()
        val r = Reader(bytes, 0, bytes.size)
        while (r.more()) {
            val tag = r.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 1 && wire == 2) {
                val len = r.varint().toInt()
                out += piece(Reader(bytes, r.pos, r.pos + len))
                r.pos += len
            } else {
                r.skip(wire)
            }
        }
        return out
    }

    private fun piece(r: Reader): Piece {
        var text = ""
        var score = 0f
        while (r.more()) {
            val tag = r.varint().toInt()
            when {
                tag ushr 3 == 1 && tag and 7 == 2 -> {
                    val len = r.varint().toInt()
                    text = String(r.bytes, r.pos, len, Charsets.UTF_8)
                    r.pos += len
                }
                tag ushr 3 == 2 && tag and 7 == 5 -> score = Float.fromBits(r.fixed32())
                else -> r.skip(tag and 7)
            }
        }
        return Piece(text, score)
    }

    /**
     * For NeMo models that ship only tokens.txt ("piece id" per line): every piece gets the
     * same score, which makes the encoder a longest match. This is what k2-fsa's own Parakeet
     * hotword example does.
     */
    fun piecesFromTokens(tokensTxt: String): List<Piece> =
        tokensTxt.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .map { line -> Piece(line.substringBeforeLast(' ').ifEmpty { line }, -1.0f) }
            .toList()

    fun format(pieces: List<Piece>): String = buildString {
        for (p in pieces) append(p.piece).append('\t').append(p.score.toString()).append('\n')
    }

    private class Reader(val bytes: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "truncated varint" }
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }

        fun fixed32(): Int {
            require(pos + 4 <= end) { "truncated fixed32" }
            val v = (bytes[pos].toInt() and 0xff) or
                ((bytes[pos + 1].toInt() and 0xff) shl 8) or
                ((bytes[pos + 2].toInt() and 0xff) shl 16) or
                ((bytes[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    // Read the length first: "pos += varint()" would add it to the old pos.
                    val n = varint().toInt()
                    pos += n
                }
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            require(pos <= end) { "truncated field" }
        }
    }
}
