package com.linroid.kdown.app.instance

import javax.jmdns.JmDNS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SERVICE_TYPE = "_kdown._tcp.local."

internal actual suspend fun discoverLanServicesViaMdns(
  timeoutMs: Long,
): List<DiscoveredServer> = withContext(Dispatchers.IO) {
  val jmDNS = JmDNS.create()
  try {
    jmDNS.list(SERVICE_TYPE, timeoutMs)
      .asSequence()
      .mapNotNull { info ->
        val host = info.inet4Addresses
          .firstOrNull()
          ?.hostAddress
          ?.substringBefore('%')
          ?: return@mapNotNull null
        DiscoveredServer(
          name = info.name,
          host = host,
          port = info.port,
          tokenRequired = info.getPropertyString("token") == "required",
        )
      }
      .toList()
  } finally {
    jmDNS.close()
  }
}
