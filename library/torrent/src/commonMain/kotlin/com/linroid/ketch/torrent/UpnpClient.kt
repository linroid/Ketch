package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import okio.IOException

/** A UPnP gateway's WAN connection service: where to send its SOAP actions. */
internal data class UpnpGateway(
  val host: String,
  val port: Int,
  val controlPath: String,
  val serviceType: String,
)

/** The gateway answered an action with a UPnP error, such as 718 for a port mapped already. */
internal class UpnpException(val code: Int, message: String) : IOException(message)

/**
 * UPnP Internet Gateway Device control: finds a gateway by SSDP, reads its description and
 * maps ports with SOAP actions on its WANIPConnection or WANPPPConnection service. Only devices
 * on one of our private networks are used, so a spoofed SSDP reply cannot send requests
 * elsewhere. HTTP goes over [network] directly, never through a proxy.
 */
internal class UpnpClient(
  private val network: TorrentNetwork,
  /** Where SSDP searches go; tests point it at a local responder. */
  private val ssdp: PeerEndpoint = PeerEndpoint("239.255.255.250", 1900),
  private val searchMs: Long = 2_000,
  private val requestMs: Long = 5_000,
) {
  /** The first gateway on [networks] whose description lists a WAN connection service. */
  suspend fun discover(networks: List<LocalIpv4Network>): UpnpGateway? {
    for (location in search()) {
      val url = parseUrl(location) ?: continue
      val address = numericAddress(url.host) ?: continue
      if (networks.none { it.contains(address) }) continue
      val description = try {
        request(url.host, url.port, "GET", url.path, emptyMap(), null).second
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        continue
      }
      service(description.decodeToString(), url)?.let { return it }
    }
    return null
  }

  /** Locations of the gateways answering an SSDP search within [searchMs]. */
  private suspend fun search(): List<String> {
    val socket = try {
      network.bindUdp(PeerEndpoint("0.0.0.0", 0))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return emptyList()
    }
    val locations = linkedSetOf<String>()
    try {
      for (target in SEARCH_TARGETS) {
        val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
          "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"
        try {
          socket.send(ssdp, message.encodeToByteArray())
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return emptyList()
        }
      }
      withTimeoutOrNull(searchMs) {
        while (locations.size < 8) {
          val reply = socket.receive().bytes.decodeToString()
          header(reply, "LOCATION")?.let { locations += it }
        }
      }
    } finally {
      socket.close()
    }
    return locations.toList()
  }

  /** Our external IPv4 address as [gateway] reports it. */
  suspend fun externalAddress(gateway: UpnpGateway): String? =
    action(gateway, "GetExternalIPAddress", emptyList())
      .let { tag(it, "NewExternalIPAddress") }?.takeIf { numericAddress(it) != null }

  /** Forwards [externalPort] to [client]:[internalPort] for [leaseSeconds] (0: until removed). */
  suspend fun addPortMapping(
    gateway: UpnpGateway,
    protocol: MappedProtocol,
    externalPort: Int,
    internalPort: Int,
    client: String,
    leaseSeconds: Long,
  ) {
    action(gateway, "AddPortMapping", listOf(
      "NewRemoteHost" to "",
      "NewExternalPort" to externalPort.toString(),
      "NewProtocol" to protocol.name,
      "NewInternalPort" to internalPort.toString(),
      "NewInternalClient" to client,
      "NewEnabled" to "1",
      "NewPortMappingDescription" to "Ketch",
      "NewLeaseDuration" to leaseSeconds.toString(),
    ))
  }

  suspend fun deletePortMapping(gateway: UpnpGateway, protocol: MappedProtocol, externalPort: Int) {
    action(gateway, "DeletePortMapping", listOf(
      "NewRemoteHost" to "",
      "NewExternalPort" to externalPort.toString(),
      "NewProtocol" to protocol.name,
    ))
  }

  /** Calls [name] on [gateway]'s service; a fault throws [UpnpException]. */
  private suspend fun action(
    gateway: UpnpGateway,
    name: String,
    arguments: List<Pair<String, String>>,
  ): String {
    val body = buildString {
      append("<?xml version=\"1.0\"?>")
      append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
      append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
      append("<u:$name xmlns:u=\"${gateway.serviceType}\">")
      for ((key, value) in arguments) append("<$key>${escape(value)}</$key>")
      append("</u:$name></s:Body></s:Envelope>")
    }.encodeToByteArray()
    val (status, response) = request(gateway.host, gateway.port, "POST", gateway.controlPath,
      mapOf(
        "Content-Type" to "text/xml; charset=\"utf-8\"",
        "SOAPAction" to "\"${gateway.serviceType}#$name\"",
      ), body)
    val text = response.decodeToString()
    if (status != 200) {
      val code = tag(text, "errorCode")?.toIntOrNull() ?: status
      throw UpnpException(code, "UPnP $name failed: ${tag(text, "errorDescription") ?: status}")
    }
    return text
  }

  /** One HTTP/1.1 exchange, the connection closed after it; bodies up to 256 KiB. */
  private suspend fun request(
    host: String,
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String>,
    body: ByteArray?,
  ): Pair<Int, ByteArray> = withTimeout(requestMs) {
    val connection = network.connect(PeerEndpoint(host, port))
    try {
      val head = buildString {
        append("$method $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n")
        for ((key, value) in headers) append("$key: $value\r\n")
        if (body != null) append("Content-Length: ${body.size}\r\n")
        append("\r\n")
      }
      connection.write(head.encodeToByteArray() + (body ?: ByteArray(0)))
      val status = line(connection).split(' ').getOrNull(1)?.toIntOrNull()
        ?: throw IOException("Invalid HTTP status line")
      var length: Int? = null
      var chunked = false
      while (true) {
        val line = line(connection)
        if (line.isEmpty()) break
        val (key, value) = line.split(':', limit = 2).let {
          it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim()
        }
        if (key == "content-length") length = value.toIntOrNull()
        if (key == "transfer-encoding" && value.contains("chunked", ignoreCase = true)) {
          chunked = true
        }
      }
      val content = Buffer()
      when {
        chunked -> while (true) {
          val size = line(connection).substringBefore(';').trim().toInt(16)
          if (size == 0) break
          require(content.size + size <= MAX_BODY) { "UPnP response too large" }
          content.write(connection.readExactly(size))
          line(connection)
        }
        length != null -> {
          require(length in 0..MAX_BODY) { "UPnP response too large" }
          content.write(connection.readExactly(length))
        }
        // Neither: the body runs until the connection closes.
        else -> try {
          while (content.size < MAX_BODY) content.write(connection.readExactly(1))
        } catch (_: IOException) {
          // The end of the body.
        }
      }
      status to content.readByteArray()
    } finally {
      connection.close()
    }
  }

  /** One CRLF-terminated line, at most 8 KiB. */
  private suspend fun line(connection: TorrentConnection): String {
    val out = Buffer()
    while (out.size < 8192) {
      val byte = connection.readExactly(1)[0]
      if (byte == '\n'.code.toByte()) return out.readUtf8().trimEnd('\r')
      out.writeByte(byte.toInt())
    }
    throw IOException("HTTP line too long")
  }

  private data class Url(val host: String, val port: Int, val path: String)

  /** An `http://host[:port]/path` URL with a numeric IPv4 host; null otherwise. */
  private fun parseUrl(value: String): Url? {
    val match = URL.matchEntire(value.trim()) ?: return null
    val port = match.groupValues[2].ifEmpty { "80" }.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return Url(match.groupValues[1], port, match.groupValues[3].ifEmpty { "/" })
  }

  /** The WAN connection service of a device [description] read from [location]. */
  private fun service(description: String, location: Url): UpnpGateway? {
    val base = tag(description, "URLBase")?.let(::parseUrl)
    for (block in SERVICE.findAll(description).map { it.value }) {
      val type = tag(block, "serviceType")?.trim() ?: continue
      if (SERVICE_TYPES.none { type.startsWith(it) }) continue
      val control = tag(block, "controlURL")?.trim() ?: continue
      val absolute = parseUrl(control)
      // A control URL on another host would send our requests elsewhere.
      if (absolute != null && absolute.host != location.host) continue
      val path = absolute?.path ?: if (control.startsWith("/")) control else {
        (base?.path ?: location.path).substringBeforeLast('/') + "/" + control
      }
      return UpnpGateway(location.host, absolute?.port ?: base?.port ?: location.port, path, type)
    }
    return null
  }

  private fun escape(value: String) =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  companion object {
    private const val MAX_BODY = 256 * 1024
    private val SEARCH_TARGETS = listOf(
      "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
      "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
      "urn:schemas-upnp-org:service:WANIPConnection:1",
    )
    private val SERVICE_TYPES = listOf(
      "urn:schemas-upnp-org:service:WANIPConnection:",
      "urn:schemas-upnp-org:service:WANPPPConnection:",
    )
    private val URL = Regex("""http://(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?(/[^\s]*)?""",
      RegexOption.IGNORE_CASE)
    // [\s\S] spans lines on every target; DOT_MATCHES_ALL is not common Kotlin.
    private val SERVICE = Regex("<service>[\\s\\S]*?</service>", RegexOption.IGNORE_CASE)

    /** The text of the first element named [name] in [xml], namespace prefixes ignored. */
    fun tag(xml: String, name: String): String? =
      Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?$name>",
        RegexOption.IGNORE_CASE)
        .find(xml)?.groupValues?.get(1)

    /** An HTTP header's value in [message], case-insensitively. */
    fun header(message: String, name: String): String? = message.split("\r\n", "\n")
      .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()
  }
}
