package com.linroid.ketch.ai.fetch

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * Answers the first lookup of any host with [first] and every later one
 * with [then], like a DNS-rebinding server with a zero TTL.
 */
internal fun rebindingDns(first: String, then: String): (String) -> Array<InetAddress> {
  val lookups = AtomicInteger()
  return {
    val ip = if (lookups.getAndIncrement() == 0) first else then
    arrayOf(InetAddress.getByName(ip))
  }
}
