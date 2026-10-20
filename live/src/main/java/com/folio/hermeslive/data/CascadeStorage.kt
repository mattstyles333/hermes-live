package com.folio.hermeslive.data

import android.content.Context
import com.folio.hermes.cascade.DecisionLog
import com.folio.hermes.cascade.DirectoryClipStore
import com.folio.hermes.cascade.FileDecisionStore
import com.folio.hermes.cascade.FileRotationStore
import com.folio.hermes.cascade.RotationStore
import com.folio.hermes.cascade.TtsCache
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.telemetry.TelemetrySink
import java.io.File

/**
 * Where the cascade keeps its files, all app-private and outside backups (`noBackupFilesDir`):
 *
 * | Path | What |
 * |---|---|
 * | `cascade/store/` | the actor's [Store] ([FileStore]: `kv/`, `logs/`, `corrupt/`) |
 * | `cascade/tts/` | Bill's clips ([TtsCache], LRU 50 MB; R-SPK-3) |
 * | `cascade/rotations.json` | each stock slot's line rotation (R-SPK-1) |
 * | `cascade/decisions.jsonl` | the decision log (on-device only; R-Q-2) |
 * | `cascade/models/` | speech models, for S-C's model manager |
 * | `cascade/memo/<id>/` | an offline memo's WAV chunks, until Hermes has transcribed each (offline-memo slice) |
 * | `cache/share/` | the decision-log export, only while sharing |
 *
 * Mic audio is written here for offline memos only (Settings › Voice › Offline memos), and each
 * chunk is deleted as soon as its transcript is stored; sign-out deletes the rest. Calls and meeting
 * mode never write audio (R-STT-3).
 */
class CascadeDirs(val root: File, val shareDir: File) {
    val store: File get() = File(root, "store")
    val tts: File get() = File(root, "tts")
    val rotations: File get() = File(root, "rotations.json")
    val decisions: File get() = File(root, "decisions.jsonl")
    val models: File get() = File(root, "models")
    val memo: File get() = File(root, "memo")

    companion object {
        fun of(context: Context): CascadeDirs =
            CascadeDirs(File(context.noBackupFilesDir, "cascade"), File(context.cacheDir, "share"))
    }
}

/**
 * Builds the file-backed pieces the cascade needs. Each is cheap and touches only its own path
 * on first use. S-F hands these to the actor and `BillVoice`.
 */
class CascadeStorage(val dirs: CascadeDirs, private val telemetry: TelemetrySink = TelemetrySink.Default) {
    /** The actor's durable store. Every key and log is fsynced (the actor writes little, and only at turn boundaries). */
    val store: Store by lazy { FileStore(dirs.store, telemetry = telemetry) }

    /** Rotation cursors: a few KB rewritten per play (temp file then rename). */
    val rotations: RotationStore by lazy { FileRotationStore(dirs.rotations) }

    /** The TTS cache for [maxBytes] (CascadeSettings.ttsCacheBytes). */
    fun ttsCache(maxBytes: Long): TtsCache = TtsCache(DirectoryClipStore(dirs.tts), maxBytes)

    /** The decision log; records nothing while [enabled] says no. */
    fun decisionLog(enabled: () -> Boolean): DecisionLog = DecisionLog(FileDecisionStore(dirs.decisions), enabled)
}
