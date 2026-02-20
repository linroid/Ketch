package com.linroid.kdown.app.instance

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.core.KDown
import com.linroid.kdown.remote.ConnectionState
import com.linroid.kdown.remote.RemoteKDown
import kotlinx.coroutines.flow.StateFlow

interface InstanceEntry {
  val instance: KDownApi
  val label: String
}

data class EmbeddedInstance(
  override val instance: KDown,
  override val label: String,
) : InstanceEntry

data class RemoteInstance(
  override val instance: RemoteKDown,
  override val label: String,
) : InstanceEntry {
  val host: String get() = instance.host
  val port: Int get() = instance.port
  val connectionState: StateFlow<ConnectionState>
    get() = instance.connectionState
}
