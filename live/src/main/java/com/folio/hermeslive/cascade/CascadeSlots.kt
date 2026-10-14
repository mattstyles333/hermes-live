package com.folio.hermeslive.cascade

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNet
import android.net.NetworkCapabilities
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.Network
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.AudioSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/*
 * The cascade actor is built once per runtime (its tasks outlive calls, R-CONV-3), but the mic,
 * the speech models and the speaker exist only during a call. These slots are the ports the
 * actor holds; a call plugs the real ones in and pulls them out again.
 */

/** The [SpeechToText] port while a call has one plugged in; silent between calls. */
class SttSlot : SpeechToText {
    private val current = MutableStateFlow<SpeechToText?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val events: Flow<SttEvent> = current.flatMapLatest { it?.events ?: emptyFlow() }

    val attached: SpeechToText? get() = current.value

    fun attach(stt: SpeechToText) {
        current.value = stt
    }

    fun detach() {
        current.value = null
    }

    override suspend fun accurate(turnId: String): Transcript? = current.value?.accurate(turnId)

    override fun drop(turnId: String) {
        current.value?.drop(turnId)
    }
}

/** The [AudioOut] port while a call has a speaker plugged in; plays nothing between calls. */
class AudioSlot : AudioOut {
    @Volatile private var current: AudioOut? = null

    fun attach(out: AudioOut) {
        current = out
    }

    fun detach() {
        current?.stop()
        current = null
    }

    override suspend fun play(clip: AudioClip): Long = current?.play(clip) ?: 0L

    override fun stop() {
        current?.stop()
    }
}

/** [source] with mute: while [muted], every read is silence (the pipeline keeps its timing; nothing is heard). */
class MutableSource(private val source: AudioSource) : AudioSource by source {
    @Volatile var muted: Boolean = false

    override fun read(buffer: ShortArray): Int {
        val n = source.read(buffer)
        if (muted && n > 0) buffer.fill(0, 0, n)
        return n
    }
}

/** The [Network] port on the default network: online when it has validated internet. */
class AndroidNetwork(private val context: Context) : Network {
    override val online: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        fun now(): Boolean {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: AndroidNet, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }

            override fun onLost(network: AndroidNet) {
                trySend(now())
            }
        }
        trySend(now())
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
