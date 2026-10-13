package com.folio.hermeslive.hooks

/**
 * Debug and staging only (`src/hooks`): the fake Hermes on the emulator's host. Android and
 * `HermesEndpoint` refuse cleartext to `10.0.2.2`, but allow it for `*.ts.net` names, so a
 * typed `http://10.0.2.2:<port>` becomes `http://fake-hermes.ts.net:<port>` and the build's
 * `BuildHooks.dns` resolves that name to 10.0.2.2. (A real tailnet name always has a tailnet
 * part, `<machine>.<tailnet>.ts.net`, so this one can never be a real machine.)
 */
object TestServer {
    const val HOST = "fake-hermes.ts.net"
    const val EMULATOR_HOST = "10.0.2.2"
    private val EMULATOR = Regex("^(http://)10\\.0\\.2\\.2(?=[:/]|$)", RegexOption.IGNORE_CASE)

    fun alias(raw: String): String = EMULATOR.replace(raw.trim(), "$1$HOST")
}
