package com.linroid.ketch.ai.fetch

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A [Dns] that answers from [records] (host to IP literal) and fails
 * every other lookup, so tests never reach a real resolver.
 */
internal fun fakeDns(vararg records: Pair<String, String>): Dns {
  // An IP literal is parsed, never looked up.
  val table = records.associate { (host, ip) -> host.lowercase() to InetAddress.getByName(ip) }
  return Dns { host -> listOf(table[host.lowercase()] ?: throw UnknownHostException(host)) }
}
