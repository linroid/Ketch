package com.linroid.ketch.app.instance

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.flow.StateFlow

/** A device the app can show: the engine inside the app, or a remote one. */
interface InstanceEntry {
  /** The device's engine, or the client that reaches it. */
  val instance: KetchApi

  /** The device's own name: the host name of the embedded one, else the remote's name. */
  val label: String
}

/**
 * The engine running inside the app.
 *
 * @property label the device's host name, or the name set for it in Settings.
 */
data class EmbeddedInstance(
  override val instance: KetchApi,
  override val label: String,
) : InstanceEntry

/**
 * A remote device and the client the app reaches it through.
 *
 * Each client connects at most once: [InstanceManager] replaces the entry with one holding a
 * fresh client whenever it connects again, so an entry never holds a closed client.
 *
 * @property instance the client; a [RemoteKetch] outside tests.
 * @property remoteConfig the saved connection, with the device's name and whether it is watched.
 * @property connectionState how well [instance] is connected.
 */
data class RemoteInstance(
  override val instance: KetchApi,
  val remoteConfig: RemoteConfig,
  val connectionState: StateFlow<ConnectionState>,
) : InstanceEntry {
  /** Wraps [client], which was built for [remoteConfig]. */
  constructor(client: RemoteKetch, remoteConfig: RemoteConfig) :
    this(client, remoteConfig, client.connectionState)

  /** Host name or address of the device. */
  val host: String get() = remoteConfig.host

  /** Port of the device's server. */
  val port: Int get() = remoteConfig.port

  /** [RemoteConfig.name], or `host:port` while the device has none. */
  override val label: String
    get() = remoteConfig.name ?: "$host:$port"
}

/**
 * Name the app shows for this device: [localDeviceNoun] ("This Mac") for the embedded one, else
 * [InstanceEntry.label].
 */
val InstanceEntry.displayName: UiText
  get() = if (this is EmbeddedInstance) localDeviceNoun() else verbatim(label)

/**
 * Secondary text under [displayName]: the host name of the embedded device, else `host:port`.
 */
val InstanceEntry.detail: String
  get() = if (this is RemoteInstance) "$host:$port" else label

/**
 * [name] as a device name, or `null` when it names no device in particular: blank, or the
 * "Ketch" every server announces until it is given a name.
 */
fun deviceNameOrNull(name: String?): String? =
  name?.trim()?.takeUnless { it.isEmpty() || it.equals(GENERIC_NAME, ignoreCase = true) }

private const val GENERIC_NAME = "Ketch"
