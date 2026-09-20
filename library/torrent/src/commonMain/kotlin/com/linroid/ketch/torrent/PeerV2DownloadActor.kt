package com.linroid.ketch.torrent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns one negotiated connection; the session only exchanges bounded commands and owned events. */
internal class PeerV2DownloadActor private constructor(
  val commands: SendChannel<Command>,
  val events: ReceiveChannel<Event>,
) {
  sealed interface Command {
    data class Request(val plan: TorrentV2PieceScheduler.RequestPlan) : Command
    data class Interest(val interested: Boolean) : Command
    data class Cancel(val ticket: PeerBlockExchange.Ticket) : Command
    data class RequestHashes(val selector: PeerHashSelector) : Command
    data class RejectHashes(val selector: PeerHashSelector) : Command
  }

  sealed interface Event {
    /** Consumer closes every event, including events ignored after session shutdown. */
    fun close() = Unit

    data class Requested(
      val plan: TorrentV2PieceScheduler.RequestPlan,
      val ticket: PeerBlockExchange.Ticket?,
    ) : Event
    data class Interest(val interested: Boolean, val sent: Boolean) : Event
    data class Canceled(val ticket: PeerBlockExchange.Ticket, val sent: Boolean) : Event
    data class Response(val value: PeerBlockExchange.Response) : Event {
      override fun close() { if (value is PeerBlockExchange.Response.Block) value.close() }
    }
    /** Validated ordinary messages retain their frame credit while the session updates its view. */
    data class Update(val frame: PeerHashTransport.Frame) : Event {
      override fun close() = frame.close()
    }
    data class Hash(val value: PeerHashTransport.Event) : Event {
      override fun close() {
        if (value is PeerHashTransport.Event.Verified) value.result.close()
      }
    }
    data class HashRequested(
      val selector: PeerHashSelector,
      val ticket: PeerHashExchange.Ticket?,
    ) : Event
    data class HashTimeout(val tickets: List<PeerHashExchange.Ticket>) : Event
  }

  companion object {
    /**
     * Caller admits the peer's availability state before construction. This scope admits both
     * queues, owns transport/blocks exclusively, and joins their reader before releasing credit.
     * A failed peer closes events with its cause without canceling the session body. Delivered
     * events remain consumer-owned; queued and undelivered events are reclaimed on scope exit.
     */
    suspend fun <T> run(
      transport: PeerHashTransport,
      blocks: PeerBlockExchange,
      state: TorrentBufferBudget,
      capacity: Int = 8,
      dispatchTimeoutMs: Long = 30_000,
      body: suspend (PeerV2DownloadActor) -> T,
    ): T = coroutineScope {
      require(capacity in 1..256 && dispatchTimeoutMs in 1..180_000)
      val lease = checkNotNull(state.reserve(capacity * 16_384 + 4096)) {
        "Peer actor queue budget exhausted"
      }
      val commands = Channel<Command>(capacity)
      val events = Channel<Event>(capacity, onUndeliveredElement = { it.close() })
      val actor = launch {
        try {
          PeerV2Inbox.run(transport, blocks) { inbox ->
            fun deadline(): Long = minOf(dispatchTimeoutMs,
              blocks.nextDeadlineMs() ?: dispatchTimeoutMs,
              transport.nextDeadlineMs() ?: dispatchTimeoutMs)

            suspend fun publish(event: Event) {
              var handedToChannel = false
              try {
                val time = deadline()
                check(time > 0) { "Peer deadline expired before event dispatch" }
                withTimeout(time) {
                  handedToChannel = true
                  // Channel owns failed/canceled sends via onUndeliveredElement. Do not close a
                  // delivered event if cancellation wins at the enclosing timeout boundary.
                  events.send(event)
                }
              } finally {
                if (!handedToChannel) event.close()
              }
            }
            while (true) {
              when (val next = inbox.next(commands)) {
                is PeerV2Inbox.Event.Command -> withTimeout(deadline()) {
                  when (val command = next.value) {
                    is Command.Request -> {
                      require(command.plan.peer === blocks) { "Wrong request peer actor" }
                      val ticket = blocks.request(command.plan.request)
                      publish(Event.Requested(command.plan, ticket))
                    }
                    is Command.Interest -> publish(Event.Interest(command.interested,
                      blocks.setInterested(command.interested)))
                    is Command.Cancel -> publish(Event.Canceled(command.ticket,
                      blocks.cancel(command.ticket)))
                    is Command.RequestHashes -> publish(Event.HashRequested(command.selector,
                      transport.request(command.selector)))
                    is Command.RejectHashes -> transport.respond(
                      PeerHashMessage.Reject(command.selector))
                  }
                }
                is PeerV2Inbox.Event.HashTimeout -> publish(Event.HashTimeout(next.tickets))
                is PeerV2Inbox.Event.Frame -> {
                  val frame = next.value
                  var retainFrame = false
                  val event = try {
                    val hash = transport.accept(frame)
                    if (hash != null) Event.Hash(hash) else {
                      val response = blocks.receive(frame)
                      if (response != null) Event.Response(response) else {
                        retainFrame = true
                        Event.Update(frame)
                      }
                    }
                  } finally {
                    if (!retainFrame) frame.close()
                  }
                  publish(event)
                }
              }
            }
          }
        } catch (error: Throwable) {
          // A connection failure is observed by its consumer after already queued events.
          events.close(error)
        } finally {
          commands.cancel()
          events.close()
        }
      }
      try {
        body(PeerV2DownloadActor(commands, events))
      } finally {
        withContext(NonCancellable) {
          try { actor.cancelAndJoin() } finally {
            // A canceled-before-start child never entered the inbox cleanup scope.
            try { blocks.close() } finally {
              try { transport.close() } finally {
                commands.cancel()
                try { events.cancel() } finally { lease.close() }
              }
            }
          }
        }
      }
    }
  }
}
