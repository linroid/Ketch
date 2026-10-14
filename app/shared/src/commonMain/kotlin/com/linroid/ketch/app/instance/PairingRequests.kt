package com.linroid.ketch.app.instance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A device on the network that asked this one, while it is shared, for its access code.
 *
 * @property id identifies the request while it waits.
 * @property name the name the device gave, such as "Pixel 9".
 * @property code four digits the device shows too, so the owner can tell it is theirs.
 * @property os the system the device runs, or `null` when it did not say.
 * @property address the IP address the request came from.
 */
class PairingAsk(
  val id: Long,
  val name: String,
  val code: String,
  val os: String?,
  val address: String,
) {
  internal val answer = CompletableDeferred<Boolean>()
}

/**
 * The pairing requests the shared device waits on, oldest first, and whether it takes them. The
 * owner turns pairing on for [WINDOW] at a time ([open]); the server only asks while it is
 * [isOpen], and the owner [answer]s in Settings › Sharing. The server's request waits in [ask]
 * until then.
 *
 * @param clock tells when the window ends.
 */
class PairingRequests(private val clock: Clock = Clock.System) {
  private val state = MutableStateFlow<List<PairingAsk>>(emptyList())
  private val until = MutableStateFlow<Instant?>(null)

  /** Requests waiting for an answer, oldest first. */
  val pending: StateFlow<List<PairingAsk>> = state.asStateFlow()

  /**
   * When the pairing window the owner opened ends, or `null` while it is closed. It may lie in
   * the past until something [close]s it; [isOpen] tells.
   */
  val openUntil: StateFlow<Instant?> = until.asStateFlow()

  /** Whether devices may ask to pair now. */
  val isOpen: Boolean
    get() = until.value?.let { clock.now() < it } == true

  /** Takes requests for [duration] from now, as the owner asked. */
  fun open(duration: Duration = WINDOW) {
    until.value = clock.now() + duration
  }

  /** Takes no more requests; those waiting still wait for an answer. */
  fun close() {
    until.value = null
  }

  /** How long devices may still ask to pair, or `null` while they may not. */
  fun timeLeft(): Duration? = until.value?.let { it - clock.now() }?.takeIf { it.isPositive() }

  /** Closes the window once it has ended, so [openUntil] no longer names it. */
  fun closeIfEnded() {
    until.update { end -> end?.takeIf { clock.now() < it } }
  }

  /**
   * Shows a request until the owner answers it and returns whether they allowed it. Cancelling
   * the call, as the server does when the device withdraws the request, takes it back.
   */
  suspend fun ask(name: String, code: String, os: String?, address: String): Boolean {
    val ask = PairingAsk(Random.nextLong(), name, code, os, address)
    state.update { it + ask }
    try {
      return ask.answer.await()
    } finally {
      state.update { asks -> asks.filterNot { it === ask } }
    }
  }

  /**
   * Answers the request [id]: [allow] lets the device in, closes the window and turns away every
   * other request still waiting, so each time pairing is turned on lets one device in at most.
   * One no longer waiting is ignored.
   */
  fun answer(id: Long, allow: Boolean) {
    val asks = state.value
    val ask = asks.firstOrNull { it.id == id } ?: return
    if (allow) {
      close()
      asks.filter { it !== ask }.forEach { it.answer.complete(false) }
    }
    ask.answer.complete(allow)
  }

  companion object {
    /** How long pairing stays on once the owner turns it on. */
    val WINDOW: Duration = 3.minutes
  }
}
