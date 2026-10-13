package com.folio.hermeslive.lab

import java.util.concurrent.ConcurrentHashMap

/** Numbers gathered while captions run, for the report. No text is kept here. */
class LiveStats {
    val aec = ConcurrentHashMap<CaptureSource, AecReport>()
    val confidence = ConfidenceStats()

    @Volatile var slowChunks = 0

    @Volatile var readErrors = 0

    @Volatile var streamProcNanos = 0L

    @Volatile var streamAudioNanos = 0L

    private var utteranceIds = 0
    private var speechLevelSum = 0.0
    private var speechLevelCount = 0
    private val secondMs = ArrayList<Double>()
    private val secondRtf = ArrayList<Double>()
    private var streamingWords = 0
    private var streamingDiffering = 0
    private var secondWords = 0
    private var secondDiffering = 0

    @Synchronized fun nextUtteranceId(): Int = ++utteranceIds

    @Synchronized fun addSpeechLevel(dbfs: Double) {
        speechLevelSum += dbfs
        speechLevelCount++
    }

    @Synchronized fun recordSecondPass(u: Utterance, diff: WordDiff.Result, ms: Double, rtf: Double) {
        secondMs += ms
        secondRtf += rtf
        streamingWords += diff.left.size
        streamingDiffering += diff.leftDiffering
        secondWords += diff.right.size
        secondDiffering += diff.rightDiffering
        // Token-grouped words line up with the text's words; skip the rare utterance where not.
        if (u.streaming.size == diff.left.size) {
            u.streaming.forEachIndexed { i, w ->
                w.confidence?.let { confidence.add(it, agreed = !diff.left[i].differs) }
            }
        }
    }

    @Synchronized fun reset() {
        aec.clear()
        confidence.clear()
        slowChunks = 0
        readErrors = 0
        streamProcNanos = 0
        streamAudioNanos = 0
        utteranceIds = 0
        speechLevelSum = 0.0
        speechLevelCount = 0
        secondMs.clear()
        secondRtf.clear()
        streamingWords = 0
        streamingDiffering = 0
        secondWords = 0
        secondDiffering = 0
    }

    @Synchronized fun report(): LiveReport = LiveReport(
        utterances = utteranceIds,
        streamRtf = if (streamAudioNanos > 0) streamProcNanos.toDouble() / streamAudioNanos else null,
        slowChunks = slowChunks,
        readErrors = readErrors,
        meanSpeechDbfs = if (speechLevelCount > 0) speechLevelSum / speechLevelCount else null,
        secondPassMsP50 = ConfidenceStats.percentile(secondMs.map { it.toFloat() }, 50.0),
        secondPassMsMax = secondMs.maxOrNull(),
        secondPassRtfMean = if (secondRtf.isEmpty()) null else secondRtf.average(),
        streamingWords = streamingWords,
        streamingWordsDiffering = streamingDiffering,
        secondPassWords = secondWords,
        secondPassWordsDiffering = secondDiffering,
    )

    @Synchronized fun confidenceReport(): ConfidenceReport = ConfidenceReport(
        agreeWords = confidence.agreeCount,
        disagreeWords = confidence.disagreeCount,
        agreeP10 = confidence.percentile(true, 10.0),
        agreeP50 = confidence.percentile(true, 50.0),
        disagreeP50 = confidence.percentile(false, 50.0),
        disagreeP90 = confidence.percentile(false, 90.0),
        suggestedThreshold = confidence.suggestedThreshold(),
    )
}
