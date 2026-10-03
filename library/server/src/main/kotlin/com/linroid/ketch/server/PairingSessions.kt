package com.linroid.ketch.server

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.endpoints.model.PairingRequest
import com.linroid.ketch.endpoints.model.PairingState
import com.linroid.ketch.endpoints.model.PairingStatus
import com.linroid.ketch.endpoints.model.PairingTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The pairing requests a [KetchServer] waits on: each asks [approver] for up to [timeout], and
 * its answer stays readable for [retention] afterwards. Only one request per address and
 * [MAX_PENDING] in all wait at a time, so a device on the network cannot flood the owner.
 *
 * @param token the access token an allowed request receives.
 * @param scope runs the requests; cancelling it withdraws them.
 */
internal class PairingSessions(
  private val approver: PairingApprover,
  private val token: String,
  private val scope: CoroutineScope,
  private val timeout: Duration = PAIRING_TIMEOUT,
  private val retention: Duration = ANSWER_RETENTION,
) {
  private val log = KetchLogger("Pairing")
  private val mutex = Mutex()
  private val sessions = mutableMapOf<String, Session>()
  private val random = SecureRandom()

  private class Session(val address: String) {
    @Volatile var state: PairingState = PairingState.PENDING
    var job: Job? = null
  }

  /**
   * Asks the owner about [request] from [address]. Returns `null` without asking while a
   * request from [address], or [MAX_PENDING] requests in all, wait for an answer.
   */
  suspend fun open(request: PairingRequest, address: String): PairingTicket? {
    val id = newId()
    mutex.withLock {
      val waiting = sessions.values.filter { it.state == PairingState.PENDING }
      if (waiting.size >= MAX_PENDING || waiting.any { it.address == address }) return null
      val session = Session(address)
      sessions[id] = session
      session.job = scope.launch { decide(id, session, request) }
    }
    log.i { "Pairing requested by \"${request.name}\" from $address" }
    return PairingTicket(id, timeout.inWholeSeconds)
  }

  /** Where the request [id] stands, or `null` when there is none, or no longer. */
  suspend fun status(id: String): PairingStatus? = mutex.withLock {
    val session = sessions[id] ?: return null
    val state = session.state
    PairingStatus(state, token.takeIf { state == PairingState.ALLOWED })
  }

  /** Withdraws the request [id], so the owner is no longer asked; `false` when there is none. */
  suspend fun withdraw(id: String): Boolean {
    val session = mutex.withLock { sessions.remove(id) } ?: return false
    session.job?.cancel()
    log.i { "Pairing withdrawn from ${session.address}" }
    return true
  }

  private suspend fun decide(id: String, session: Session, request: PairingRequest) {
    val allowed = try {
      withTimeoutOrNull(timeout) { approver.approve(request, session.address) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't ask about pairing with ${session.address}: ${e.describeCauses()}" }
      false
    }
    session.state = when (allowed) {
      true -> PairingState.ALLOWED
      false -> PairingState.DENIED
      null -> PairingState.EXPIRED
    }
    log.i { "Pairing with \"${request.name}\" from ${session.address}: ${session.state}" }
    delay(retention)
    mutex.withLock { sessions.remove(id) }
  }

  private fun newId(): String {
    val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }

  companion object {
    /** How long the owner has to answer a request. */
    val PAIRING_TIMEOUT: Duration = 2.minutes

    /** How long an answer stays readable for the device that asked. */
    val ANSWER_RETENTION: Duration = 1.minutes

    /** How many requests may wait for the owner at a time. */
    const val MAX_PENDING: Int = 4

    private const val ID_BYTES = 16
  }
}
