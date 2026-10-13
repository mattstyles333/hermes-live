package com.folio.hermeslive.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class BpeVocabTest {
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun lenField(out: ByteArrayOutputStream, field: Int, body: ByteArray) {
        varint(out, ((field shl 3) or 2).toLong())
        varint(out, body.size.toLong())
        out.write(body)
    }

    private fun piece(text: String, score: Float, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        lenField(out, 1, text.toByteArray())
        varint(out, ((2 shl 3) or 5).toLong())
        val bits = score.toRawBits()
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xff)
        varint(out, ((3 shl 3) or 0).toLong())
        varint(out, type.toLong())
        return out.toByteArray()
    }

    /** A sentencepiece ModelProto: pieces (field 1) plus specs (fields 2 and 3) to skip. */
    private fun model(): ByteArray {
        val out = ByteArrayOutputStream()
        lenField(out, 1, piece("<blk>", 0f, 3))
        lenField(out, 1, piece("▁THE", -3.3911421f, 1))
        lenField(out, 2, byteArrayOf(0x08, 0x96.toByte(), 0x01)) // trainer_spec with a varint
        lenField(out, 1, piece("S", -3.2376409f, 1))
        lenField(out, 3, "nmt_nfkc".toByteArray())
        return out.toByteArray()
    }

    @Test fun readsPiecesAndScoresFromASentencepieceModel() {
        val pieces = BpeVocab.piecesFromModel(model())
        assertEquals(listOf("<blk>", "▁THE", "S"), pieces.map { it.piece })
        assertEquals(-3.3911421f, pieces[1].score, 0f)
        assertEquals(-3.2376409f, pieces[2].score, 0f)
    }

    @Test fun formatsLikeExportBpeVocab() {
        val text = BpeVocab.format(BpeVocab.piecesFromModel(model()))
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(3, lines.size)
        assertEquals("<blk>", lines[0].substringBefore('\t'))
        assertEquals(0f, lines[0].substringAfter('\t').toFloat(), 0f)
        assertEquals(-3.3911421f, lines[1].substringAfter('\t').toFloat(), 0f)
    }

    @Test fun nemoTokensGetEqualScores() {
        val pieces = BpeVocab.piecesFromTokens("<unk> 0\n▁t 1\r\nhe 2\n\n")
        assertEquals(listOf("<unk>", "▁t", "he"), pieces.map { it.piece })
        assertEquals(setOf(-1.0f), pieces.map { it.score }.toSet())
    }

    @Test fun truncatedModelFails() {
        val bytes = model()
        assertThrows(IllegalArgumentException::class.java) { BpeVocab.piecesFromModel(bytes.copyOf(bytes.size - 3)) }
    }
}
