package com.folio.hermeslive.service

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.LiveIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MemoEntryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Offline-memo slice: keyed on Settings › Voice › Offline memos, whatever the engine. */
    @Test fun offlineWithMemosOnIsAMemoNotAFailure() {
        assertEquals(MemoEntry.Mode.Memo, MemoEntry.mode(memosOn = true, online = false))
        assertEquals(MemoEntry.Mode.Call, MemoEntry.mode(memosOn = true, online = true))
        assertEquals(MemoEntry.Mode.Memo, MemoEntry.mode(memosOn = true, online = true, memoAsked = true))
        assertEquals("memos off: today's call, unchanged", MemoEntry.Mode.Call, MemoEntry.mode(memosOn = false, online = false))
        assertEquals(MemoEntry.Mode.Call, MemoEntry.mode(memosOn = false, online = true, memoAsked = true))
    }

    @Test fun theTileMarksItsIntentMemoOnlyWhenOfflineWithMemosOn() {
        fun tile() = Intent(app, LiveActivity::class.java).setAction(LiveIntents.ACTION_TILE)
        assertTrue(MemoEntry.isMemo(MemoEntry.forTile(tile(), memosOn = true, online = false)))
        assertFalse(MemoEntry.isMemo(MemoEntry.forTile(tile(), memosOn = true, online = true)))
        assertFalse(MemoEntry.isMemo(MemoEntry.forTile(tile(), memosOn = false, online = false)))
        assertEquals(LiveIntents.ACTION_TILE, MemoEntry.forTile(tile(), true, false).action)
        assertTrue(MemoEntry.isMemo(Intent(MemoEntry.ACTION_MEMO)))
        assertFalse(MemoEntry.isMemo(null))
    }

    @Test fun theMemoShortcutExistsOnlyWhileMemosAreOn() {
        val sm = app.getSystemService(ShortcutManager::class.java)
        MemoEntry.syncShortcut(app, memosOn = true)
        val memo = sm.dynamicShortcuts.single { it.id == MemoEntry.SHORTCUT_ID }
        assertEquals(MemoEntry.ACTION_MEMO, memo.intent!!.action)
        assertEquals(LiveActivity::class.java.name, memo.intent!!.component!!.className)
        MemoEntry.syncShortcut(app, memosOn = false)
        assertTrue(sm.dynamicShortcuts.none { it.id == MemoEntry.SHORTCUT_ID })
    }
}
