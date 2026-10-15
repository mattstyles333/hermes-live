package com.folio.hermeslive.data

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.HermesConfig
import com.folio.hermes.live.AudioDecoder
import com.folio.hermes.live.DirectVoiceWires
import com.folio.hermes.live.HermesVoice
import com.folio.hermes.live.HermesVoiceApi
import com.folio.hermes.live.HermesVoiceConfig
import com.folio.hermes.live.HermesVoiceState
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient

/**
 * The signed-in account's [HermesVoice] (Hermes voice slice): one per account, rebuilt when the
 * server or session changes, dropped at sign-out ([forget]). Its `voice-config` (provider keys
 * included) lives in memory only.
 *
 * Off state: nothing is fetched unless [wanted] (signed in, engine Custom, or cloud speech
 * recognition on for meeting mode) — a GPT-Live or Grok user never pulls a provider key into the
 * app. Refreshed with `force` at app start ([refresh] from `LiveApp`), at each Custom call start
 * and when Settings › Voice opens; otherwise at most every 30 minutes.
 */
class HermesVoices(
    /** The signed-in server and its tokens, or null. */
    private val account: () -> Pair<HermesConfig, AccessTokenProvider>?,
    private val client: OkHttpClient,
    private val decoder: () -> AudioDecoder,
    private val telemetry: TelemetrySink,
    /** True when anything may use Hermes's voice now (see the class comment). */
    private val wanted: () -> Boolean,
    scope: CoroutineScope,
    /** Tests widen it to their fake provider. */
    private val reachable: (String) -> Boolean = HermesVoiceConfig::phoneReachable,
) {
    private var built: Pair<Any, HermesVoice>? = null
    private val current = MutableStateFlow<HermesVoice?>(null)

    /** What Settings shows: what Hermes uses, or why it can't say. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HermesVoiceState> = current
        .flatMapLatest { it?.state ?: flowOf(HermesVoiceState.Unknown) }
        .stateIn(scope, SharingStarted.Eagerly, HermesVoiceState.Unknown)

    /** This account's voice, or null (signed out, or not [wanted]: nothing is built). */
    @Synchronized
    fun voice(): HermesVoice? {
        if (!wanted()) return null
        val (config, tokens) = account() ?: run {
            forget()
            return null
        }
        val id = config.endpoint.toString() to System.identityHashCode(tokens)
        built?.takeIf { it.first == id }?.let { return it.second }
        built?.second?.forget()
        val v = HermesVoice(HermesVoiceApi(config, tokens, client, reachable = reachable), DirectVoiceWires(client), decoder(), telemetry)
        built = id to v
        current.value = v
        return v
    }

    /** The config in memory, without a request. */
    fun cached(): HermesVoiceConfig? = if (wanted()) built?.second?.cached() else null

    /** Fetch now ([force]) or when stale. Null: none (signed out, not wanted, or the server can't say). */
    suspend fun refresh(force: Boolean = true): HermesVoiceConfig? = voice()?.config(force)

    /** Drop the config and its keys (sign-out). */
    @Synchronized
    fun forget() {
        built?.second?.forget()
        built = null
        current.value = null
    }
}
