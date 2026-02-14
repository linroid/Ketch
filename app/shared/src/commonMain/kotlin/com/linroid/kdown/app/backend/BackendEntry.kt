package com.linroid.kdown.app.backend

import com.linroid.kdown.remote.ConnectionState
import kotlinx.coroutines.flow.StateFlow

data class BackendEntry(
  val id: String,
  val label: String,
  val config: BackendConfig,
  val connectionState: StateFlow<ConnectionState>,
) {
  val isEmbedded: Boolean get() = config is BackendConfig.Embedded
}
