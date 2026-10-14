package com.folio.hermes.openrouter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform

/** The upstream's failure, carried through the channel behind the items emitted before it. */
private class UpstreamFailure(val error: Throwable)

/**
 * [flowOn] that delivers every item the upstream emitted before it failed, then the failure.
 *
 * Plain `flowOn` runs the upstream as a child coroutine: when it throws, the collector's scope is
 * cancelled, and items still in the channel (or handed to a collector not yet resumed) are lost.
 * A stream that emitted text or audio and then broke could then deliver nothing, although it was
 * already counted as emitted and so never retried. Here the failure travels as the last item.
 */
internal fun <T> Flow<T>.flowOnInOrder(dispatcher: CoroutineDispatcher): Flow<T> {
    val upstream = this
    return flow<Any?> {
        try {
            upstream.collect { emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(UpstreamFailure(e))
        }
    }.flowOn(dispatcher).transform { item ->
        if (item is UpstreamFailure) throw item.error
        @Suppress("UNCHECKED_CAST")
        emit(item as T)
    }
}
