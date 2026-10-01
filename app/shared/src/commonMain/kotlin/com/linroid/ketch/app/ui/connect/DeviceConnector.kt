package com.linroid.ketch.app.ui.connect

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.deviceNameOrNull
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * How a device answered a [ConnectionProbe].
 *
 * @property state [ConnectionState.Connected] when it let Ketch in,
 *   [ConnectionState.Unauthorized] when it asked for an access code, and
 *   [ConnectionState.Disconnected] when it did not answer in time.
 * @property name the name it announces, read once it let Ketch in; `null` when unknown.
 */
internal data class ProbeResult(val state: ConnectionState, val name: String? = null)

/** Tries a device before it is added, so a typo or a missing code is caught in the form. */
internal fun interface ConnectionProbe {
  /** Connects to the device [config] describes once and reports how it answered. */
  suspend fun check(config: RemoteConfig): ProbeResult
}

/** A [ConnectionProbe] that connects a [RemoteKetch] of its own and closes it again. */
internal object RemoteProbe : ConnectionProbe {
  private val log = KetchLogger("DeviceConnector")
  private val TIMEOUT = 8.seconds

  override suspend fun check(config: RemoteConfig): ProbeResult {
    val client = RemoteKetch(config.host, config.port, config.apiToken, config.secure)
    try {
      client.start()
      val state = withTimeoutOrNull(TIMEOUT) {
        client.connectionState.first { it != ConnectionState.Connecting }
      } ?: ConnectionState.Disconnected(NO_ANSWER)
      if (state != ConnectionState.Connected) return ProbeResult(state)
      val name = try {
        client.status().name
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.d { "Couldn't read the name of ${config.host}:${config.port}: ${e.describeCauses()}" }
        null
      }
      return ProbeResult(state, name)
    } finally {
      client.close()
    }
  }

  private const val NO_ANSWER = "No answer"
}

/** What [DeviceConnector.connect] did. */
internal sealed interface ConnectOutcome {
  /** The device is added, or was already, and has the code it was given. */
  data class Connected(val device: RemoteInstance) : ConnectOutcome

  /**
   * The device asks for an access code: none was given, or it [rejected] the one that was.
   */
  data class NeedsCode(val rejected: Boolean) : ConnectOutcome

  /** Nothing answered at the address. */
  data object Unreachable : ConnectOutcome
}

/**
 * Connects this app to the device a [PairingLink] names: tries it first, then adds it to
 * [manager], or gives a device already added the new access code and the name it lacks, and
 * shows it through [switchTo].
 *
 * @param switchTo shows a device, such as [com.linroid.ketch.app.state.AppState.switchInstance].
 */
internal class DeviceConnector(
  private val manager: InstanceManager,
  private val switchTo: (InstanceEntry) -> Unit,
  private val probe: ConnectionProbe = RemoteProbe,
) {
  private val log = KetchLogger("DeviceConnector")

  /**
   * Connects to [link]. A device added before is tried with the code it has unless [link]
   * brings one. With [check] off the device is added without trying it first, such as one that
   * is switched off now, and by default not shown, since there is nothing to show yet.
   *
   * @param show whether to show the device once it is added.
   */
  suspend fun connect(
    link: PairingLink,
    check: Boolean = true,
    show: Boolean = check,
  ): ConnectOutcome {
    var name = deviceNameOrNull(link.name)
    if (check) {
      val saved = remoteAt(link)?.remoteConfig?.apiToken
      val result = probe.check(link.copy(token = link.token ?: saved).toRemoteConfig())
      log.d { "${link.address} answered ${result.state}" }
      when (result.state) {
        ConnectionState.Connected -> name = name ?: deviceNameOrNull(result.name)
        ConnectionState.Unauthorized -> return ConnectOutcome.NeedsCode(link.token != null)
        else -> return ConnectOutcome.Unreachable
      }
    }
    val device = save(link.copy(name = name))
    if (show) switchTo(device)
    return ConnectOutcome.Connected(device)
  }

  // A device already added keeps its connection unless the code changed.
  private suspend fun save(link: PairingLink): RemoteInstance {
    val existing = remoteAt(link) ?: run {
      log.i { "Adding ${link.address}" }
      return manager.addRemote(link.toRemoteConfig())
    }
    val token = link.token
    if (token != null && token != existing.remoteConfig.apiToken) {
      log.i { "Giving ${existing.deviceId} a new access code" }
      manager.reconnectWithToken(existing, token)
    }
    val name = link.name
    val current = remoteAt(link) ?: existing
    if (name != null && current.remoteConfig.name == null) manager.rename(current, name)
    return remoteAt(link) ?: current
  }

  private fun remoteAt(link: PairingLink): RemoteInstance? =
    manager.instances.value.filterIsInstance<RemoteInstance>()
      .firstOrNull { it.host == link.host && it.port == link.port }
}

/**
 * Adds the device [link] names, or gives the one added already its access code, and shows it,
 * without trying it first: for the server the web app was loaded from, which answered already.
 */
suspend fun AppState.connectTo(link: PairingLink) {
  DeviceConnector(instanceManager, ::switchInstance).connect(link, check = false, show = true)
}

/** The saved connection of the device this link names. */
internal fun PairingLink.toRemoteConfig(): RemoteConfig = RemoteConfig(
  host = host,
  port = port,
  apiToken = token,
  secure = secure,
  name = deviceNameOrNull(name),
)
