package com.linroid.ketch.app.instance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

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
 * The pairing requests the shared device waits on, oldest first. The app shows them one at a
 * time and the owner [answer]s; the server's request waits in [ask] until then.
 */
class PairingRequests {
  private val state = MutableStateFlow<List<PairingAsk>>(emptyList())

  /** Requests waiting for an answer, oldest first. */
  val pending: StateFlow<List<PairingAsk>> = state.asStateFlow()

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

  /** Answers the request [id]: [allow] lets the device in. One no longer waiting is ignored. */
  fun answer(id: Long, allow: Boolean) {
    state.value.firstOrNull { it.id == id }?.answer?.complete(allow)
  }

  /**
   * Calls [onArrived] for each request that starts waiting and [onLeft] with the id of each that
   * stops, answered or withdrawn, until cancelled; for notifications about them.
   */
  suspend fun watch(onArrived: suspend (PairingAsk) -> Unit, onLeft: (Long) -> Unit) {
    var waiting = emptySet<Long>()
    pending.collect { asks ->
      val ids = asks.mapTo(HashSet()) { it.id }
      (waiting - ids).forEach(onLeft)
      asks.filter { it.id !in waiting }.forEach { onArrived(it) }
      waiting = ids
    }
  }
}
