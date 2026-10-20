package com.folio.hermeslive.stt

import com.folio.hermes.openrouter.CloudSttModels
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.TranscriptionClient
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The cloud pass (Settings › Voice › Speech recognition: "Cloud when online"): one speech
 * segment's audio, as a 16 kHz mono WAV held in memory only, to the chosen model. Null on any
 * failure (no key, offline, timeout, HTTP error): the on-device pass stands.
 */
fun interface CloudStt {
    suspend fun transcribe(wav: ByteArray, model: String, terms: List<String>, timeoutMs: Long): String?
}

/** [CloudStt] on the app's OpenRouter client. Offline ([online] false): null, and no request. */
class OpenRouterCloudStt(private val client: TranscriptionClient, private val online: () -> Boolean) : CloudStt {
    override suspend fun transcribe(wav: ByteArray, model: String, terms: List<String>, timeoutMs: Long): String? {
        if (!online()) return null
        val result = client.transcribe(wav, CloudSttModels.of(model), terms, timeoutMs)
        return (result as? OpenRouterResult.Ok)?.value?.takeIf { it.isNotBlank() }
    }
}

/** 16-bit PCM WAV in memory (never a file). */
object Wav {
    /** The canonical header's length. */
    const val HEADER = 44

    /** The 44-byte header of a 16-bit mono PCM WAV holding [dataBytes] at [rate] (also the offline memo's `WavWriter`). */
    fun header(dataBytes: Long, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        return h.array()
    }

    fun encode(pcm: FloatArray, rate: Int = AudioRate.HZ): ByteArray {
        val data = pcm.size * 2
        val out = ByteArrayOutputStream(HEADER + data)
        out.write(header(data.toLong(), rate))
        val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) body.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(body.array())
        return out.toByteArray()
    }
}

/**
 * Whether the cloud text changes what Jev reads ("in sto" vs "in stock"): the words differ once
 * case, punctuation, number forms ([TextNorm]) and fillers ("um", "uh") are set aside. Pure.
 */
object CloudMateriality {
    private val FILLERS = setOf("um", "umm", "uh", "uhm", "er", "erm", "ah", "hmm", "mm", "mhm")

    fun words(text: String): List<String> = TextNorm.normalize(text).filter { it !in FILLERS }

    fun material(fast: String, cloud: String): Boolean = words(fast) != words(cloud)
}
