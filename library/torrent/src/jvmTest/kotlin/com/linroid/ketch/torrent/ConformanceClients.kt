package com.linroid.ketch.torrent

import java.util.Properties

/** Shared with the fixture installer and Gradle's test-only dependencies. */
internal object ConformanceClients {
  private val pins = Properties().apply {
    val resource = checkNotNull(ConformanceClients::class.java
      .getResourceAsStream("/clients.properties")) {
      "Missing pinned conformance client versions"
    }
    resource.use { load(it) }
  }

  fun version(client: String): String = checkNotNull(pins.getProperty("$client.version"))
}
