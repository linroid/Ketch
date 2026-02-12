package com.linroid.kdown.app.backend

sealed class BackendConfig {
  data object Embedded : BackendConfig()

  data class Remote(
    val host: String,
    val port: Int = 8642,
    val apiToken: String? = null,
  ) : BackendConfig() {
    val baseUrl: String get() = "http://$host:$port"
  }
}
