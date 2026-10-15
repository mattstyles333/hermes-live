package com.folio.hermes

/**
 * Everything the client needs to know about one Hermes server, plus the timings it runs on.
 * Defaults follow the deployed server and desktop client; tests shorten them.
 */
data class HermesConfig(
    /** The server. */
    val endpoint: HermesEndpoint,
    /** Dashboard auth provider name used for password login and refresh. */
    val authProvider: String = "basic",
    /** Renew the access token when it has less than this left. */
    val tokenRefreshMarginMs: Long = 5 * 60_000L,
    /** Used when the login cookie carries no Max-Age (the basic provider's TTL). */
    val defaultAccessTtlMs: Long = 12 * 60 * 60_000L,
    /** The client sends `gateway.ping` this often (the desktop client uses 15 s). */
    val pingIntervalMs: Long = 15_000L,
    /** No inbound frame for this long means the socket is dead; reconnect. */
    val heartbeatTimeoutMs: Long = 45_000L,
    /** Default JSON-RPC call timeout. */
    val rpcTimeoutMs: Long = 30_000L,
    /** `prompt.submit` timeout (an agent build can be slow on a cold session). */
    val promptSubmitTimeoutMs: Long = 60_000L,
    /** How long a connect may take, from ticket to `gateway.ready`. */
    val connectTimeoutMs: Long = 20_000L,
    /** After `disconnect()` sends close(1000), how long the server has to answer before the socket is cut. */
    val closeTimeoutMs: Long = 5_000L,
    /** First reconnect delay; doubles per attempt up to [reconnectMaxDelayMs]. */
    val reconnectBaseDelayMs: Long = 1_000L,
    /** Reconnect delay cap. */
    val reconnectMaxDelayMs: Long = 30_000L,
    /** Consecutive WebSocket handshake refusals (HTTP 403) before the gateway stops trying. */
    val maxHandshakeRefusals: Int = 3,
    /** A turn with no event for this long fails instead of hanging. */
    val turnInactivityTimeoutMs: Long = 15 * 60_000L,
    /** After an interrupt, wait at most this long for the old turn's `message.complete`. */
    val interruptSettleMs: Long = 5_000L,
    /** `prompt.submit` answered "session busy" (4009): retry for this long. */
    val busyRetryWindowMs: Long = 6_000L,
    /** Gap between busy retries. */
    val busyRetryIntervalMs: Long = 150L,
    /** `POST /api/audio/voice-live/session` timeout (vendor round trip). */
    val voiceSessionTimeoutMs: Long = 45_000L,
)
