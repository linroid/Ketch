package com.linroid.ketch.ai.fetch

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves each host in [hosts] to its fixed IP literal without touching
 * the network; any other host fails like an unknown name.
 */
internal fun fakeDns(vararg hosts: Pair<String, String>): (String) -> Array<InetAddress> {
  val addresses = hosts.toMap()
  return { host ->
    val ip = addresses[host] ?: throw UnknownHostException(host)
    arrayOf(InetAddress.getByName(ip))
  }
}
