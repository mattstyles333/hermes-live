package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.CascadeEnv
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.budget.CappedJevStateBudget
import com.folio.hermes.cascade.conversation.ConversationPolicy
import com.folio.hermes.cascade.registry.TaskRegistry
import com.folio.hermes.cascade.store.StatePersistence
import java.time.ZoneId

/**
 * The routing slice (S-A) as one switch for S-F: spec v2's routing, writing and context rules.
 * `CascadeEnv(bank)` keeps C1's behaviour ([HandlerRegistry.default], no state cap); the cascade
 * engine opts in with [env] (or [registry] plus [CappedJevStateBudget]).
 *
 * Public entry points for other slices:
 * - [MemoRouting.begin]: S-B's `ConversationPolicy.online` calls it with held dictation (R-OFF-4);
 * - [CeoTasks.retry]: S-B's registry "tap to retry" for a failed task (R-TASK-7);
 * - [CeoTasks.readAll], [CeoTasks.tell], [CeoTasks.cancelTapped]: the card taps (wired here).
 */
object CeoRouting {
    /** The registry: CEO handlers for every route, [CeoHooks] and [CeoResults]. */
    fun registry(memo: MemoRouting = MemoRouting(), summaries: BackgroundSummaries = BackgroundSummaries()): HandlerRegistry = HandlerRegistry(
        mapOf(
            Route.Reply.wire to CeoReplyHandler,
            Route.Clarify.wire to ClarifyHandler,
            Route.AskRepeat.wire to AskRepeatHandler,
            Route.NewTask.wire to NewTaskDispatch(CeoNewTaskHandler(memo), MemoSegmentHandler(memo)),
            Route.UpdateTask("", UpdateMode.Queue).wire to CeoUpdateTaskHandler(memo),
            Route.Status(null).wire to CeoStatusHandler,
            Route.CancelTask("").wire to CeoCancelTaskHandler(memo),
            Route.ReadResult("").wire to CeoReadResultHandler,
            Route.EndCall.wire to EndCallHandler,
        ).mapValues { (_, h) -> MemoAware(h, memo) },
        hooks = CeoHooks(memo, summaries),
        result = CeoResults,
    )

    /** The hooks alone (the eval harness builds requests through them). */
    fun hooks(memo: MemoRouting = MemoRouting()): TurnHooks = CeoHooks(memo)

    /** A cascade environment with the routing slice switched on and the R-CTX-1 cap. */
    fun env(
        bank: PhraseBank,
        memo: MemoRouting = MemoRouting(),
        persistence: StatePersistence = StatePersistence.None,
        registry: TaskRegistry = TaskRegistry.None,
        conversation: ConversationPolicy = ConversationPolicy.None,
    ): CascadeEnv = CascadeEnv(bank, registry(memo), CappedJevStateBudget(), persistence, registry, conversation)

    /** The zone used for "dictated offline hh:mm" (tests pass UTC). */
    fun memo(zone: ZoneId = ZoneId.systemDefault()): MemoRouting = MemoRouting(zone)
}

/** `new_task` actions: offline segmentation batches go to [segment], everything else to [task]. */
internal class NewTaskDispatch(private val task: RouteHandler, private val segment: RouteHandler) : RouteHandler {
    private fun pick(tx: Tx) = if (tx.action.data[MemoRouting.KIND] == MemoRouting.SEGMENT) segment else task
    override fun playsSlotFirst(tx: Tx): Boolean = pick(tx).playsSlotFirst(tx)
    override fun start(tx: Tx): Next = pick(tx).start(tx)
    override fun resume(tx: Tx, result: ActionResult): Next = pick(tx).resume(tx, result)
}

/** Every route: an offline instruction that ends without recording itself still counts for the batch summary. */
internal class MemoAware(private val inner: RouteHandler, private val memo: MemoRouting) : RouteHandler {
    override fun playsSlotFirst(tx: Tx): Boolean = inner.playsSlotFirst(tx)
    override fun start(tx: Tx): Next = after(tx, inner.start(tx))
    override fun resume(tx: Tx, result: ActionResult): Next = after(tx, inner.resume(tx, result))

    private fun after(tx: Tx, next: Next): Next {
        if (next == Next.Done) {
            val a = tx.s.actions.active
            if (a != null && MemoRouting.isMemo(tx, a.turnId)) memo.record(tx, a.turnId, a.decision.route.wire, 0)
        }
        return next
    }
}
