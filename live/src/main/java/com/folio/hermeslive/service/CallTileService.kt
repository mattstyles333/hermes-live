package com.folio.hermeslive.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.LiveIntents
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.graph

/**
 * Quick Settings tile: tap to talk (opens the call screen, which starts the call), tap again to
 * end. With offline memos on and no network it starts a memo instead ([MemoEntry]).
 */
class CallTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val inCall = graph.calls.state.value.inCall
        tile.state = if (inCall) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Hermes Live"
        tile.subtitle = when {
            inCall -> "End call"
            memoMode() == MemoEntry.Mode.Memo -> "Memo"
            else -> "Talk"
        }
        tile.updateTile()
    }

    override fun onClick() {
        if (graph.calls.state.value.inCall) {
            graph.calls.end()
            return
        }
        val shell = VoiceShell.of(this)
        val intent = MemoEntry.forTile(
            Intent(this, LiveActivity::class.java)
                .setAction(LiveIntents.ACTION_TILE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            memosOn = shell.memosOn(),
            online = shell.network.online(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startLegacy(intent)
        }
    }

    private fun memoMode(): MemoEntry.Mode {
        val shell = VoiceShell.of(this)
        return MemoEntry.mode(shell.memosOn(), shell.network.online())
    }

    // API 31-33 have only the Intent overload; 34+ never reaches this.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startLegacy(intent: Intent) = startActivityAndCollapse(intent)
}
