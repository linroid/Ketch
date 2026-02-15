package com.linroid.kdown.app.instance

internal actual suspend fun discoverLanServicesViaMdns(
  timeoutMs: Long,
): List<DiscoveredServer> = emptyList()
