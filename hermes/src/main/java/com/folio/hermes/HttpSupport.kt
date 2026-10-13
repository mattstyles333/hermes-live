package com.folio.hermes

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

internal fun String.jsonBody(): RequestBody = toRequestBody(JSON_MEDIA)

/** Run an OkHttp call without blocking a coroutine thread; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/** Read the body as text (bounded) and close the response. */
internal fun Response.readText(limit: Long = 1_000_000L): String = use {
    body.source().let { source ->
        source.request(limit)
        val buffer = source.buffer
        buffer.readUtf8(minOf(buffer.size, limit))
    }
}
