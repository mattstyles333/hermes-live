package com.folio.hermeslive.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Debug only: the visible activity of the off-call start probe (spec v2 §14 item 14). Started with
 * `--ez probe_hold true`, it starts [ProbeHolderService] (the call's microphone service) while
 * visible, as a call does. It shows nothing and has no launcher entry.
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(ProbeReceiver.EXTRA_HOLD, false) == true) {
            ContextCompat.startForegroundService(this, Intent(this, ProbeHolderService::class.java))
        }
    }
}
