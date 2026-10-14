package com.folio.hermes.openrouter

/**
 * OpenRouter settings for the cascade. Models and voice are user settings with these defaults;
 * timings follow the contract (`hermes-cascade-contract-2026-10-13`). Tests point [baseUrl] at
 * a MockWebServer and shorten the timings. There is no key here: [OpenRouter] asks for it.
 */
data class OpenRouterConfig(
    /** `https://openrouter.ai/api/v1`; no trailing slash. */
    val baseUrl: String = "https://openrouter.ai/api/v1",
    /** Sent as `X-Title` and `HTTP-Referer` (OpenRouter's app attribution). */
    val title: String = "Hermes Live",
    val referer: String = "https://github.com/mattstyles333/hermes-live",
    /** Jev on the System One endpoint. */
    val jevModel: String = "typesafe/jev-1.13",
    /** Writes the spoken lines and agent prompts. */
    val writerModel: String = "deepseek/deepseek-v4.1-flash",
    /** Cleans up each committed turn's transcript (hub decision 2026-10-14, cleanup bench). */
    val cleanupModel: String = "deepseek/deepseek-v4.1-flash",
    val ttsModel: String = "elevenlabs/eleven-v4-turbo",
    val voice: String = "bill",
    val connectTimeoutMs: Long = 4_000L,
    /** HTTP/2 ping interval on OpenRouter connections (keeps a warmed connection alive during a call). */
    val pingIntervalMs: Long = 15_000L,
    /** One Jev call, end to end (contract: 2.5 s read timeout). */
    val jevTimeoutMs: Long = 2_500L,
    /**
     * One non-streamed writer call, end to end. Whole answers (agent briefs, offline segmentation)
     * may run to a few thousand tokens and are off the spoken path (the start line already played).
     */
    val writerTimeoutMs: Long = 30_000L,
    /** Longest gap between bytes of a streamed writer or speech body. */
    val streamReadTimeoutMs: Long = 12_000L,
    /** One whole speech clip, end to end. */
    val speechTimeoutMs: Long = 15_000L,
    /** Writer and speech retry once after this (Jev does not: the TurnTaker's commit call is its retry). */
    val retryBackoffMs: Long = 2_000L,
    val writerRetries: Int = 1,
    val speechRetries: Int = 1,
)
