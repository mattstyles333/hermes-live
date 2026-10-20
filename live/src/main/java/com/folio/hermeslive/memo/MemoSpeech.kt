package com.folio.hermeslive.memo

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The few lines a memo says, offline: Android's own [TextToSpeech] in the app ([AndroidMemoSpeech]),
 * a fake in tests. [MemoCall] bounds both waits (ready ≤ 1.5 s, a line ≤ 4 s), so nothing here times out.
 */
interface MemoSpeech {
    /** True once an engine is ready to speak, false if there is none (or it failed to start). */
    suspend fun awaitReady(): Boolean

    /** Speak [text] and return when it has finished (or was stopped). */
    suspend fun speak(text: String)

    /** Stop speaking now. */
    fun stop()

    /** Release the engine (the end of the memo). */
    fun shutdown()
}

/**
 * The system engine. Build it on the main thread. An offline [android.speech.tts.Voice] for the
 * language is preferred (`isNetworkConnectionRequired == false`), so it speaks with no network;
 * the audio goes out as a call ([AudioAttributes.USAGE_VOICE_COMMUNICATION]), on the call's route.
 */
class AndroidMemoSpeech(context: Context) : MemoSpeech {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var seq = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS && configure())
    }

    private fun configure(): Boolean = runCatching {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val locale = Locale.getDefault()
        tts.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
            ?.sortedWith(compareBy({ it.locale != locale }, { -it.quality }))
            ?.firstOrNull()
            ?.let { tts.voice = it }
            ?: tts.setLanguage(locale)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })
        true
    }.getOrDefault(false)

    private fun finish(id: String) {
        pending.remove(id)?.complete(Unit)
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override suspend fun speak(text: String) {
        val id = "memo-${seq++}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) finish(id)
        done.await()
    }

    override fun stop() {
        runCatching { tts.stop() }
        pending.keys.toList().forEach(::finish)
    }

    override fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}
