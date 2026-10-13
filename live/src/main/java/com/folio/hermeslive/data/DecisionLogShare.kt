package com.folio.hermeslive.data

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.folio.hermes.cascade.DecisionLog
import java.io.File
import java.io.FileNotFoundException

/**
 * Share › Decision log (R-Q-2): the log as one JSON file through the Android share sheet. The
 * file is written to `cache/share/` only when the user taps Share and is served read-only by
 * [DecisionLogProvider] to the app they pick (a one-off URI grant). Clear deletes it too.
 */
object DecisionLogShare {
    /** `com.folio.hermeslive.share` (`.staging.share` in the staging build, which sits beside the app). */
    val AUTHORITY = "${com.folio.hermeslive.BuildConfig.APPLICATION_ID}.share"
    const val FILE_NAME = "hermes-live-decision-log.json"
    const val MIME = "application/json"
    val URI: Uri = Uri.parse("content://$AUTHORITY/$FILE_NAME")

    fun file(dirs: CascadeDirs): File = File(dirs.shareDir, FILE_NAME)

    /** Write the export and return the share-sheet intent (null when the log is empty). Call off the main thread. */
    fun prepare(context: Context, log: DecisionLog, dirs: CascadeDirs): Intent? {
        if (log.records().isEmpty()) return null
        val out = file(dirs)
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        tmp.writeText(log.exportJson())
        if (!tmp.renameTo(out)) {
            out.writeText(tmp.readText())
            tmp.delete()
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, URI)
            .putExtra(Intent.EXTRA_SUBJECT, "Hermes Live decision log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, FILE_NAME, URI)
        return Intent.createChooser(send, "Share the decision log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Clear: the log and any export. */
    fun clear(log: DecisionLog, dirs: CascadeDirs) {
        log.clear()
        file(dirs).delete()
    }
}

/** Serves only the decision-log export, read-only, to apps granted the URI. Not exported. */
class DecisionLogProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun export(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.authority != DecisionLogShare.AUTHORITY || uri.lastPathSegment != DecisionLogShare.FILE_NAME) throw FileNotFoundException()
        val f = DecisionLogShare.file(CascadeDirs.of(ctx))
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(export(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = if (uri.lastPathSegment == DecisionLogShare.FILE_NAME) DecisionLogShare.MIME else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = runCatching { export(uri) }.getOrNull() ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) DecisionLogShare.FILE_NAME else f.length() })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
