package com.linroid.ketch.app.instance

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.config.RemoteConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.flow.StateFlow

interface InstanceEntry {
  val instance: KetchApi
  val label: String
}

data class EmbeddedInstance(
  override val instance: Ketch,
  override val label: String,
) : InstanceEntry

data class RemoteInstance(
  override val instance: RemoteKetch,
  override val label: String,
  val remoteConfig: RemoteConfig,
) : InstanceEntry {
  val host: String get() = instance.host
  val port: Int get() = instance.port
  val connectionState: StateFlow<ConnectionState>
    get() = instance.connectionState
}
