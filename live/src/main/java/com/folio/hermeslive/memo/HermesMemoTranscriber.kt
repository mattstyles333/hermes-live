package com.folio.hermeslive.memo

import com.folio.hermes.live.HermesVoice
import com.folio.hermes.live.HermesVoiceState
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult

/**
 * A memo chunk through Hermes's speech-to-text ([HermesVoice.transcribe]: direct or the relay's
 * `/api/audio/transcribe`, with its breaker and `hermes_voice.stt` telemetry). [voice] is null
 * signed out (or offline memos off): held as `signed_out`, no request. Failures become hold reasons:
 * `offline`, `server_old` (404, or no `voice-config` route: an older server), `no_stt` (Hermes has no
 * speech-to-text), else the failure kind; 413 is [MemoText.TooLarge].
 */
class HermesMemoTranscriber(private val voice: () -> HermesVoice?) : MemoTranscriber {
    override suspend fun transcribe(wav: ByteArray, timeoutMs: Long): MemoText {
        val v = voice() ?: return MemoText.Hold("signed_out")
        return when (val r = v.transcribe(wav, emptyList(), timeoutMs)) {
            is OpenRouterResult.Ok -> MemoText.Text(r.value)
            is OpenRouterResult.Failed -> when {
                r.kind == FailureKind.Offline -> MemoText.Hold("offline")
                r.httpStatus == 413 -> MemoText.TooLarge
                r.httpStatus == 404 -> MemoText.Hold("server_old")
                r.kind == FailureKind.Http && r.httpStatus == null && v.state.value == HermesVoiceState.Unavailable(HermesVoice.NO_ROUTE) -> MemoText.Hold("server_old")
                r.kind == FailureKind.NoKey -> MemoText.Hold(if (v.state.value is HermesVoiceState.Ready) "no_stt" else "signed_out")
                else -> MemoText.Hold(r.kind.wire)
            }
        }
    }
}
