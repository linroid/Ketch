package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * How HTTP(S) downloads, HLS and DASH included, reach servers. FTP and BitTorrent ignore it, and
 * loopback hosts always go directly.
 *
 * JSON and TOML carry it as one flat object, `{"mode": "manual", "url": "socks5://host:1080",
 * "username": ..., "password": ..., "bypass": [...]}`, where `mode` is `system`, `direct` or
 * `manual` and only `manual` reads the other fields.
 */
@Serializable(with = ProxySerializer::class)
sealed interface Proxy {
  /**
   * The system's settings: on the JVM the `*_proxy` environment variables, then the default
   * `ProxySelector` (no PAC scripts); the active network's on Android; the system's on iOS.
   */
  data object System : Proxy

  /** No proxy, whatever the system says. */
  data object Direct : Proxy

  /**
   * Through the proxy at [address], except for the hosts in [bypass]. [toString] masks the
   * password.
   *
   * @property bypass hosts reached directly, matched against the URL's host unresolved: a domain
   *   with its subdomains (a leading `.` or `*.` is optional), an IP, a CIDR range, `<local>` for
   *   names without a dot, or `*`; a `:port` is ignored
   */
  data class Manual(
    val address: ProxyAddress,
    val bypass: List<String> = emptyList(),
  ) : Proxy {
    init {
      require(bypass.all { ProxyBypass.isValid(it) }) {
        "Bypass entries must be host names, IP addresses or CIDR ranges"
      }
    }

    /**
     * The proxy at [url], `http://host:port` (80 by default; also the default scheme) or
     * `socks5://host:port` (1080), with its credentials if it carries any
     * (`socks5://user:secret@host`).
     *
     * @throws IllegalArgumentException if [url] or a [bypass] entry is invalid
     */
    constructor(url: String, bypass: List<String> = emptyList()) : this(
      requireNotNull(ProxyAddress.parse(url)) { "Invalid proxy URL" },
      bypass,
    )

    /** Whether [host] is reached directly: loopback or matching [bypass]. */
    fun bypasses(host: String): Boolean = ProxyBypass.bypasses(bypass, host)

    companion object {
      /** Whether [entry] is a valid [bypass] entry. */
      fun isValidBypass(entry: String): Boolean = ProxyBypass.isValid(entry)
    }
  }
}

// The flat object older versions wrote for every mode, so both read each other's. Encoded field
// by field: ktoml nests a serializer that delegates to another class's.
internal object ProxySerializer : KSerializer<Proxy> {
  private const val SYSTEM = "system"
  private const val DIRECT = "direct"
  private const val MANUAL = "manual"
  private val bypassSerializer = ListSerializer(String.serializer())

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
    "com.linroid.ketch.api.Proxy",
  ) {
    element<String>("mode")
    element<String?>("url", isOptional = true)
    element<String?>("username", isOptional = true)
    element<String?>("password", isOptional = true)
    element("bypass", bypassSerializer.descriptor, isOptional = true)
  }

  override fun serialize(encoder: Encoder, value: Proxy) {
    encoder.encodeStructure(descriptor) {
      when (value) {
        Proxy.System -> encodeStringElement(descriptor, 0, SYSTEM)
        Proxy.Direct -> encodeStringElement(descriptor, 0, DIRECT)
        is Proxy.Manual -> {
          val address = value.address
          encodeStringElement(descriptor, 0, MANUAL)
          encodeStringElement(descriptor, 1, address.url)
          address.username?.let { encodeStringElement(descriptor, 2, it) }
          address.password?.let { encodeStringElement(descriptor, 3, it) }
          encodeSerializableElement(descriptor, 4, bypassSerializer, value.bypass)
        }
      }
    }
  }

  override fun deserialize(decoder: Decoder): Proxy = decoder.decodeStructure(descriptor) {
    var mode = SYSTEM
    var url: String? = null
    var username: String? = null
    var password: String? = null
    var bypass = emptyList<String>()
    while (true) {
      when (val index = decodeElementIndex(descriptor)) {
        CompositeDecoder.DECODE_DONE -> break
        0 -> mode = decodeStringElement(descriptor, 0)
        1 -> url = decodeNullableSerializableElement(descriptor, 1, String.serializer())
        2 -> username = decodeNullableSerializableElement(descriptor, 2, String.serializer())
        3 -> password = decodeNullableSerializableElement(descriptor, 3, String.serializer())
        4 -> bypass = decodeSerializableElement(descriptor, 4, bypassSerializer)
        else -> throw SerializationException("Unexpected proxy field $index")
      }
    }
    when (mode) {
      SYSTEM -> Proxy.System
      DIRECT -> Proxy.Direct
      MANUAL -> try {
        val parsed = ProxyAddress.parse(requireNotNull(url) { "A manual proxy needs a URL" })
        val address = requireNotNull(parsed) { "Invalid proxy URL" }
        require(address.username == null) { "Proxy credentials go in username and password" }
        Proxy.Manual(address.copy(username = username, password = password), bypass)
      } catch (e: IllegalArgumentException) {
        throw SerializationException(e.message, e)
      }
      else -> throw SerializationException("Unknown proxy mode '$mode'")
    }
  }
}

/**
 * A proxy server parsed from a URL, such as `socks5://user:secret@127.0.0.1:1080`.
 *
 * @property host a host name or IP (IPv6 without brackets)
 */
data class ProxyAddress(
  val type: Type,
  val host: String,
  val port: Int,
  val username: String? = null,
  val password: String? = null,
) {
  init {
    require(host.isNotEmpty()) { "A proxy needs a host" }
    require(port in 1..65535) { "Proxy port must be in 1..65535" }
    require(password == null || username != null) { "A proxy password needs a username" }
  }

  /** The proxy's URL without its credentials, such as `socks5://127.0.0.1:1080`. */
  val url: String get() = "${scheme()}://${printedHost()}:$port"

  /** A proxy's protocol. */
  enum class Type {
    /** Tunnels HTTPS with `CONNECT`. */
    HTTP,

    /** Also resolves host names. */
    SOCKS5,
  }

  /** The proxy's URL, its password masked. */
  override fun toString(): String {
    val user = username?.let { "${it.encodeUserInfo()}${if (password != null) ":***" else ""}@" }
    return "${scheme()}://${user.orEmpty()}${printedHost()}:$port"
  }

  private fun scheme(): String = if (type == Type.HTTP) "http" else "socks5"

  private fun printedHost(): String = if (host.contains(':')) "[$host]" else host

  companion object {
    /**
     * Parses `http://`, `socks5://` or `socks5h://` URLs (HTTP without a scheme) with optional
     * port and percent-encoded credentials; `null` for anything else, such as a path or HTTPS.
     */
    fun parse(url: String): ProxyAddress? {
      val trimmed = url.trim()
      if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
      val schemeEnd = trimmed.indexOf("://")
      val scheme = if (schemeEnd < 0) "http" else trimmed.substring(0, schemeEnd).lowercase()
      val type = when (scheme) {
        "http" -> Type.HTTP
        "socks5", "socks5h" -> Type.SOCKS5
        else -> return null
      }
      var rest = if (schemeEnd < 0) trimmed else trimmed.substring(schemeEnd + 3)
      rest = rest.removeSuffix("/")
      if (rest.any { it == '/' || it == '?' || it == '#' }) return null
      val at = rest.lastIndexOf('@')
      var username: String? = null
      var password: String? = null
      if (at >= 0) {
        val userInfo = rest.substring(0, at)
        rest = rest.substring(at + 1)
        val colon = userInfo.indexOf(':')
        username = (if (colon < 0) userInfo else userInfo.substring(0, colon))
          .decodeUserInfo() ?: return null
        password = if (colon < 0) null else userInfo.substring(colon + 1).decodeUserInfo()
          ?: return null
        if (username.isEmpty()) return null
      }
      val host: String
      val portText: String?
      if (rest.startsWith("[")) {
        val close = rest.indexOf(']')
        if (close < 0) return null
        host = rest.substring(1, close)
        if (IpAddress.parse(host)?.size != 16) return null
        val after = rest.substring(close + 1)
        portText = when {
          after.isEmpty() -> null
          after.startsWith(":") -> after.substring(1)
          else -> return null
        }
      } else {
        val colon = rest.lastIndexOf(':')
        if (colon >= 0 && rest.indexOf(':') != colon) return null
        host = if (colon < 0) rest else rest.substring(0, colon)
        portText = if (colon < 0) null else rest.substring(colon + 1)
        if (!isHostName(host) && IpAddress.parse(host) == null) return null
      }
      val port = if (portText == null) {
        if (type == Type.HTTP) 80 else 1080
      } else {
        portText.toIntOrNull()?.takeIf { it in 1..65535 && portText.all(Char::isDigit) }
          ?: return null
      }
      return ProxyAddress(type, host.lowercase(), port, username, password)
    }

    private fun isHostName(host: String): Boolean =
      host.isNotEmpty() && host.length <= 253 && host.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
          label.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
          !label.startsWith('-') && !label.endsWith('-')
      }

    private fun String.decodeUserInfo(): String? {
      val source = encodeToByteArray()
      val decoded = ByteArray(source.size)
      var size = 0
      var i = 0
      while (i < source.size) {
        if (source[i] == '%'.code.toByte()) {
          if (i + 2 >= source.size) return null
          val high = source[i + 1].toInt().toChar().digitToIntOrNull(16) ?: return null
          val low = source[i + 2].toInt().toChar().digitToIntOrNull(16) ?: return null
          decoded[size++] = (high * 16 + low).toByte()
          i += 3
        } else {
          decoded[size++] = source[i++]
        }
      }
      return decoded.decodeToString(0, size)
    }

    private fun String.encodeUserInfo(): String = buildString {
      for (byte in this@encodeUserInfo.encodeToByteArray()) {
        val c = (byte.toInt() and 0xff).toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") {
          append(c)
        } else {
          append('%')
          append((byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
        }
      }
    }
  }
}
