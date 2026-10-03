package com.linroid.ketch.app.ui.connect

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.deviceNameOrNull
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.app.util.toCopy
import com.linroid.ketch.remote.PairingResult
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.connect_error_host
import ketch.app.shared.generated.resources.connect_error_link_empty
import ketch.app.shared.generated.resources.connect_error_link_invalid
import ketch.app.shared.generated.resources.connect_error_port
import ketch.app.shared.generated.resources.connect_link_has_code
import ketch.app.shared.generated.resources.error_title_hint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Why the last attempt of a [ConnectForm] did not connect. */
internal sealed interface ConnectProblem {
  /**
   * The device at [address] asks for an access code: none was given, or it [rejected] the one
   * that was.
   */
  data class NeedsCode(val address: String, val rejected: Boolean) : ConnectProblem

  /** Nothing answered at [address]. */
  data class Unreachable(val address: String) : ConnectProblem

  /** The device could not be added, for the reason in [message]. */
  data class Failed(val message: UiText) : ConnectProblem

  /**
   * The owner of [server], which goes by [name], did not let this device in: they turned it
   * away, did not answer, or are still answering another request from it ([reason]).
   */
  data class NotPaired(
    val server: DiscoveredServer,
    val name: String,
    val reason: PairingResult,
  ) : ConnectProblem
}

/**
 * A request for the access code of [name], found on the network, waiting for its owner, who
 * checks that it shows [code] too.
 */
internal data class PairingWait(val name: String, val code: String)

/**
 * What the Add device sheet, the pairing confirmation and the connect page hold: the pairing
 * link or address, the details typed by hand, the access code and how the last attempt went.
 *
 * The details typed by hand ([host], [port], [secure]) stand in for [link] while [manual] is
 * open and a host is typed. The access code field shows once a device asks for one
 * ([codeShown]); a code typed there wins over the one a pairing link carries.
 *
 * @param link the pairing link or address to start from.
 * @param askForCode shows the access code field from the start, for a device that rejected its
 *   code.
 */
@Stable
internal class ConnectForm(link: String = "", askForCode: Boolean = false) {
  private val log = KetchLogger("ConnectForm")
  private val initialLink = link
  private var job: Job? = null

  private var linkState by mutableStateOf(link)
  private var hostState by mutableStateOf("")
  private var portState by mutableStateOf(PairingLink.DEFAULT_PORT.toString())
  private var secureState by mutableStateOf(false)
  private var codeState by mutableStateOf("")
  private var nameState by mutableStateOf<String?>(null)

  // Whether the details were typed by hand, rather than filled from the link.
  private var detailsTyped by mutableStateOf(false)

  /** The pairing link or address, as typed or pasted. */
  var link: String
    get() = linkState
    set(value) {
      linkState = value
      nameState = null
      edited(addressChanged = true)
    }

  /** Host typed by hand. */
  var host: String
    get() = hostState
    set(value) {
      hostState = value
      detailsTyped = true
      edited(addressChanged = true)
    }

  /** Port typed by hand. */
  var port: String
    get() = portState
    set(value) {
      portState = value.filter { it.isDigit() }.take(PORT_DIGITS)
      detailsTyped = true
      edited(addressChanged = true)
    }

  /** Whether the device typed by hand is reached over HTTPS. */
  var secure: Boolean
    get() = secureState
    set(value) {
      secureState = value
      detailsTyped = true
      edited(addressChanged = true)
    }

  /** The access code typed. */
  var code: String
    get() = codeState
    set(value) {
      codeState = value
      edited(addressChanged = false)
    }

  /** Whether the details typed by hand are open. */
  var manual by mutableStateOf(false)
    private set

  /** Whether the access code field shows. */
  var codeShown by mutableStateOf(askForCode)
    private set

  /** Whether a connection is being tried. */
  var connecting by mutableStateOf(false)
    private set

  /** The request waiting for the owner of a device found on the network, or `null`. */
  var pairing by mutableStateOf<PairingWait?>(null)
    private set

  /** Why the last attempt did not connect; cleared by the next edit. */
  var problem by mutableStateOf<ConnectProblem?>(null)
    private set

  // Whether to point out what keeps the form from connecting; set by an attempt.
  private var attempted by mutableStateOf(false)

  /** Whether anything differs from how the form started, which typed input would lose. */
  val isEdited: Boolean
    get() = linkState != initialLink || detailsTyped || codeState.isNotBlank()

  /** What [link] reads as, or `null` when it is no pairing link or address. */
  val parsed: PairingLink?
    get() = PairingLink.parse(linkState)

  /** Whether [link] is a pairing link with an access code, which is all a connection needs. */
  val isComplete: Boolean
    get() = parsed?.token != null && !usesManual

  /** The device the form would connect to, or `null` while what is typed names none. */
  val target: PairingLink?
    get() {
      val base = if (usesManual) manualLink() else parsed
      val token = codeState.trim().ifEmpty { null }
      return base?.copy(
        token = token ?: base.token,
        name = base.name ?: nameState,
      )
    }

  /** What the form read from a pairing link, to confirm it; `null` for a plain address. */
  val linkSummary: UiText?
    get() {
      val link = parsed?.takeIf { !usesManual } ?: return null
      val name = deviceNameOrNull(link.name ?: nameState)
      if (name == null && link.token == null) return null
      return listOfNotNull(
        name?.let(::verbatim),
        verbatim(link.address),
        Res.string.connect_link_has_code.text().takeIf { link.token != null },
      ).joinText()
    }

  /** What is wrong with [link], once an attempt pointed it out. */
  val linkError: UiText?
    get() = when {
      !attempted || manual -> null
      linkState.isBlank() -> Res.string.connect_error_link_empty.text()
      parsed == null -> Res.string.connect_error_link_invalid.text()
      else -> null
    }

  /** What is wrong with [host], once an attempt pointed it out. */
  val hostError: UiText?
    get() = when {
      !manual || !attempted || hostState.isBlank() && parsed != null -> null
      !isHost(hostState.trim()) -> Res.string.connect_error_host.text()
      else -> null
    }

  /** What is wrong with [port], as soon as it is typed. */
  val portError: UiText?
    get() = when {
      !manual || portState.isEmpty() && !attempted -> null
      portState.toIntOrNull() !in PORTS -> Res.string.connect_error_port.text()
      else -> null
    }

  private val usesManual: Boolean get() = manual && hostState.isNotBlank()

  /** Opens or closes the details typed by hand; opening fills them from [link]. */
  fun toggleManual() {
    if (!manual && hostState.isBlank()) {
      parsed?.let { link ->
        hostState = link.host
        portState = link.port.toString()
        secureState = link.secure
      }
    }
    manual = !manual
  }

  /**
   * Fills the form with [server], found on the network, and shows the code field when it says
   * it needs one.
   */
  fun pick(server: DiscoveredServer) {
    val address = PairingLink(server.host, server.port).address
    // A code typed for another device is no code of this one.
    if (address != linkState) codeState = ""
    linkState = address
    nameState = deviceNameOrNull(server.name)
    manual = false
    if (server.tokenRequired) {
      codeShown = true
      problem = ConnectProblem.NeedsCode(linkState, rejected = false)
    } else {
      problem = null
    }
  }

  /**
   * Asks the owner of [server], found on the network, to let this device in, and connects with
   * the access code once they do. [pairing] shows the code they check meanwhile. When they turn
   * it away, do not answer or the device takes no requests, the form asks for the code instead.
   *
   * @param code the four digits both devices show; random unless given.
   */
  fun pair(
    server: DiscoveredServer,
    scope: CoroutineScope,
    connector: DeviceConnector,
    code: String = newPairingCode(),
    onConnected: (RemoteInstance) -> Unit,
  ) {
    if (connecting || pairing != null) return
    val address = PairingLink(server.host, server.port).address
    if (address != linkState) codeState = ""
    linkState = address
    nameState = deviceNameOrNull(server.name)
    manual = false
    attempted = false
    codeShown = false
    problem = null
    val target = checkNotNull(target)
    val wait = PairingWait(name = target.name ?: server.host, code = code)
    pairing = wait
    job = scope.launch {
      try {
        when (val result = connector.pair(target, wait.code)) {
          is PairingResult.Allowed -> {
            pairing = null
            connecting = true
            when (val outcome = connector.connect(target.copy(token = result.token))) {
              is ConnectOutcome.Connected -> onConnected(outcome.device)
              is ConnectOutcome.NeedsCode -> askForCode(address, rejected = true)
              ConnectOutcome.Unreachable -> problem = ConnectProblem.Unreachable(address)
            }
          }
          PairingResult.Unsupported -> askForCode(address, rejected = false)
          else -> {
            codeShown = true
            problem = ConnectProblem.NotPaired(server, wait.name, result)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't ask $address to pair: ${e.describeCauses()}" }
        problem = ConnectProblem.Unreachable(address)
      } finally {
        pairing = null
        connecting = false
      }
    }
  }

  /** Stops waiting for the owner of a device and asks for its access code instead. */
  fun enterCodeInstead() {
    if (pairing == null) return
    job?.cancel()
    pairing = null
    askForCode(linkState, rejected = false)
  }

  private fun askForCode(address: String, rejected: Boolean) {
    codeShown = true
    problem = ConnectProblem.NeedsCode(address, rejected)
  }

  /**
   * Fills the form with [device], added before, whose access code no longer works, and asks
   * for the current one.
   */
  fun askForCode(device: RemoteInstance) {
    linkState = device.addressText()
    nameState = device.remoteConfig.name
    codeState = ""
    manual = false
    attempted = false
    codeShown = true
    val address = PairingLink(device.host, device.port).address
    problem = ConnectProblem.NeedsCode(address, rejected = false)
  }

  /**
   * Tries [target] in [scope] and adds it through [connector]; [onConnected] runs once it is.
   * A device that asks for a code shows the code field, and one that does not answer stays in
   * the form with a [problem]. With [check] off the device is added without trying it.
   */
  fun connect(
    scope: CoroutineScope,
    connector: DeviceConnector,
    check: Boolean = true,
    onConnected: (RemoteInstance) -> Unit,
  ) {
    attempted = true
    if (connecting) return
    val target = target ?: return
    connecting = true
    problem = null
    job = scope.launch {
      try {
        when (val outcome = connector.connect(target, check)) {
          is ConnectOutcome.Connected -> onConnected(outcome.device)
          is ConnectOutcome.NeedsCode -> {
            codeShown = true
            problem = ConnectProblem.NeedsCode(target.address, outcome.rejected)
          }
          ConnectOutcome.Unreachable -> problem = ConnectProblem.Unreachable(target.address)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't add ${target.address}: ${e.describeCauses()}" }
        val copy = e.toCopy()
        val hint = copy.hint
        problem = ConnectProblem.Failed(
          if (hint == null) copy.title else Res.string.error_title_hint.text(copy.title, hint),
        )
      } finally {
        connecting = false
      }
    }
  }

  /** Stops the attempt under way, if any. */
  fun cancel() {
    job?.cancel()
  }

  // A new code keeps the note that the device asks for one, without saying the last one failed.
  private fun edited(addressChanged: Boolean) {
    attempted = false
    val request = problem as? ConnectProblem.NeedsCode
    problem = if (request != null && !addressChanged) request.copy(rejected = false) else null
  }

  private fun manualLink(): PairingLink? {
    val host = hostState.trim().removePrefix("[").removeSuffix("]")
    val port = portState.toIntOrNull()
    if (!isHost(host) || port !in PORTS) return null
    return PairingLink(host, checkNotNull(port), secure = secureState)
  }

  private fun isHost(text: String): Boolean =
    text.isNotEmpty() && text.none { it.isWhitespace() || it in "/?#@" }

  private companion object {
    val PORTS = 1..65535
    const val PORT_DIGITS = 5
  }
}

// Four digits, which both devices show while the owner of one decides.
private fun newPairingCode(): String =
  Random.nextInt(PAIRING_CODES).toString().padStart(PAIRING_CODE_DIGITS, '0')

private const val PAIRING_CODES = 10_000
private const val PAIRING_CODE_DIGITS = 4

/** A [ConnectForm] to give [device], which rejected its access code, a new one. */
internal fun codeForm(device: RemoteInstance): ConnectForm =
  ConnectForm(device.addressText(), askForCode = true)

// The device's address as the link field takes it; an HTTPS one keeps its scheme.
private fun RemoteInstance.addressText(): String {
  val link = PairingLink(host, port, secure = remoteConfig.secure)
  return if (link.secure) link.webAppUrl().removeSuffix("/") else link.address
}
