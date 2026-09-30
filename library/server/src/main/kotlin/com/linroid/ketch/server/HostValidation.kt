package com.linroid.ketch.server

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respond
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException

/**
 * Decides whether a request's `Host` header names this server.
 *
 * This stops DNS rebinding: a page whose domain re-resolves to this machine becomes
 * same-origin with the API, but the browser still sends that domain as `Host`.
 *
 * Hosts are compared without their port and case-insensitively. Accepted are:
 * - loopback names and addresses: `localhost`, `*.localhost`, `127.0.0.0/8` and `[::1]`
 * - addresses of this machine's network interfaces, re-read when an unknown address arrives
 * - this machine's host name and its mDNS name, `<host>.local`
 * - [allowedHosts], names or IP addresses the user trusts
 *
 * A request without a `Host` header passes: browsers always send one, so it cannot come
 * from a rebound page. HTTP/1.0 clients and Ktor's test client leave it out.
 */
internal class HostValidator(
  allowedHosts: Collection<String>,
  private val interfaceAddresses: () -> Set<InetAddress> = ::systemInterfaceAddresses,
  machineHostNames: () -> List<String> = ::systemHostNames,
) {
  private val allowed = allowedHosts.mapNotNull(::parseHost)
  private val allowedNames = allowed.filterIsInstance<RequestHost.Name>().map { it.name }.toSet()
  private val allowedAddresses =
    allowed.filterIsInstance<RequestHost.Address>().map { it.address }.toSet()

  // Each name plus `<first label>.local`, the name mDNS responders (Bonjour, Avahi, Windows)
  // answer for this machine. `localhost.local` is left out: anyone on the network could
  // answer for it. Read on first use, as looking up the host name can be slow.
  private val machineNames by lazy {
    machineHostNames()
      .mapNotNull { (parseHost(it) as? RequestHost.Name)?.name }
      .filter { it.substringBefore('.') != "localhost" }
      .flatMapTo(mutableSetOf()) { listOf(it, it.substringBefore('.') + ".local") }
  }
  @Volatile private var knownAddresses: Set<InetAddress>? = null

  /** Whether [hostHeader], the raw `Host` header value, names this server. */
  fun isAllowed(hostHeader: String?): Boolean {
    if (hostHeader == null) return true
    return when (val host = parseHost(hostHeader)) {
      null -> false
      is RequestHost.Address -> isAllowedAddress(host.address)
      is RequestHost.Name -> isAllowedName(host.name)
    }
  }

  private fun isAllowedAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address in allowedAddresses) return true
    if (knownAddresses?.contains(address) == true) return true
    // Interfaces come and go (DHCP, VPN), so re-read them before rejecting.
    val current = interfaceAddresses()
    knownAddresses = current
    return address in current
  }

  private fun isAllowedName(name: String): Boolean {
    return name == "localhost" || name.endsWith(".localhost") ||
      name in allowedNames || name in machineNames
  }
}

/**
 * Responds `403 Forbidden` to requests whose `Host` header [validator] rejects, before
 * CORS, authentication or routing see them.
 */
internal fun hostValidation(validator: HostValidator): ApplicationPlugin<Unit> =
  createApplicationPlugin("HostValidation") {
    onCall { call ->
      val host = call.request.headers[HttpHeaders.Host]
      if (!validator.isAllowed(host)) {
        log.w { "Rejected request for Host '$host': not a name of this server" }
        call.respond(
          HttpStatusCode.Forbidden,
          ErrorResponse(
            "host_not_allowed",
            "Host '$host' is not allowed. Add it to the server's allowedHosts" +
              " or set an API token.",
          ),
        )
      }
    }
  }

internal sealed interface RequestHost {
  data class Address(val address: InetAddress) : RequestHost
  data class Name(val name: String) : RequestHost
}

/**
 * Parses a `Host` header value, or an `allowedHosts` entry, without its port.
 *
 * Returns `null` for anything that is neither an IP address literal nor a DNS name. Names
 * are lowercased and lose a trailing dot. IP literals are parsed without DNS lookups; only
 * dotted-quad IPv4 addresses count, as browsers send no other form.
 */
internal fun parseHost(value: String): RequestHost? {
  val text = value.trim().lowercase()
  if (text.startsWith("[")) {
    val end = text.indexOf(']')
    if (end < 0 || !PORT_SUFFIX.matches(text.substring(end + 1))) return null
    return parseIpv6(text.substring(1, end))
  }
  // Not valid in a Host header, but convenient in allowedHosts.
  if (text.count { it == ':' } > 1) return parseIpv6(text)
  val hostPart = text.substringBefore(':')
  if (!PORT_SUFFIX.matches(text.removePrefix(hostPart))) return null
  val host = hostPart.removeSuffix(".")
  IPV4.matchEntire(host)?.let { match ->
    val octets = match.groupValues.drop(1).map { it.toInt() }
    if (octets.any { it > 255 }) return null
    return RequestHost.Address(InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() }))
  }
  return if (DNS_NAME.matches(host)) RequestHost.Name(host) else null
}

private fun parseIpv6(text: String): RequestHost? {
  // Interface addresses are compared without their zone, so drop it (`%25eth0` in a URL).
  val literal = text.substringBefore('%')
  if (literal.isEmpty() || !literal.all { it in IPV6_CHARS }) return null
  return try {
    // Brackets make InetAddress parse a literal and never fall back to a DNS lookup.
    RequestHost.Address(InetAddress.getByName("[$literal]"))
  } catch (_: UnknownHostException) {
    null
  }
}

private val PORT_SUFFIX = Regex("(:\\d*)?")
private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
private val DNS_NAME = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)*")
private const val IPV6_CHARS = "0123456789abcdef:."

private val log = KetchLogger("KetchServer")

private fun systemInterfaceAddresses(): Set<InetAddress> {
  return try {
    NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
      .flatMap { it.inetAddresses.asSequence() }
      .toSet()
  } catch (e: IOException) {
    log.w(e) { "Could not list network interfaces" }
    emptySet()
  }
}

/**
 * This machine's host name and, on macOS, its Bonjour name (`LocalHostName`), which can
 * differ from the host name.
 */
private fun systemHostNames(): List<String> {
  val hostName = try {
    InetAddress.getLocalHost().hostName
  } catch (e: UnknownHostException) {
    log.d { "Could not read the host name: ${e.message}" }
    null
  }
  return listOfNotNull(hostName, macLocalHostName()?.let { "$it.local" })
}

private fun macLocalHostName(): String? {
  if (!System.getProperty("os.name", "").lowercase().contains("mac")) return null
  return try {
    val process = ProcessBuilder("scutil", "--get", "LocalHostName")
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    output.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
  } catch (e: IOException) {
    log.d { "Could not read the Bonjour host name: ${e.message}" }
    null
  }
}
