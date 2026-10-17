@file:OptIn(ExperimentalAtomicApi::class, ExperimentalEncodingApi::class)

package com.linroid.ketch.webdav

import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.core.digest.newDigest
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * An authentication scheme a server offers in `WWW-Authenticate` (RFC 9110), with its
 * parameters by lowercase name.
 */
internal class Challenge(val scheme: String, val params: Map<String, String>) {
  override fun toString(): String = "Challenge($scheme)"

  companion object {
    /**
     * The challenges in [values], the `WWW-Authenticate` headers of a response; one value may hold
     * several, separated by commas. Parameters a scheme takes as a single token (`token68`) are
     * kept under an empty name.
     */
    fun parse(values: List<String>): List<Challenge> = values.flatMap(::parseOne)

    private fun parseOne(value: String): List<Challenge> {
      val challenges = mutableListOf<Challenge>()
      var scheme: String? = null
      var params = mutableMapOf<String, String>()
      fun finish() {
        scheme?.let { challenges += Challenge(it, params) }
      }
      for (element in elements(value)) {
        val equals = element.indexOf('=')
        val space = element.indexOfFirst { it == ' ' || it == '\t' }
        if (space > 0 && (equals < 0 || space < equals)) {
          // A scheme, then a token68 or the challenge's first parameter.
          finish()
          scheme = element.substring(0, space)
          params = mutableMapOf()
          val rest = element.substring(space + 1).trim()
          if (TOKEN68.matches(rest)) params[""] = rest else param(rest)?.let { params += it }
        } else if (equals < 0) {
          finish()
          scheme = element
          params = mutableMapOf()
        } else if (scheme != null) {
          param(element)?.let { params += it }
        }
      }
      finish()
      return challenges
    }

    /** `name=value`, unquoted, with the name in lowercase. */
    private fun param(element: String): Pair<String, String>? {
      val equals = element.indexOf('=')
      if (equals <= 0) return null
      val name = element.substring(0, equals).trim().lowercase()
      val raw = element.substring(equals + 1).trim()
      if (!raw.startsWith('"')) return name to raw
      val out = StringBuilder()
      var i = 1
      while (i < raw.length && raw[i] != '"') {
        if (raw[i] == '\\' && i + 1 < raw.length) i++
        out.append(raw[i])
        i++
      }
      return name to out.toString()
    }

    /** [value] split at the commas outside quoted strings, trimmed, empty parts left out. */
    private fun elements(value: String): List<String> {
      val parts = mutableListOf<String>()
      val current = StringBuilder()
      var quoted = false
      var i = 0
      while (i < value.length) {
        val c = value[i]
        when {
          quoted && c == '\\' && i + 1 < value.length -> {
            current.append(c).append(value[i + 1])
            i++
          }
          c == '"' -> {
            quoted = !quoted
            current.append(c)
          }
          c == ',' && !quoted -> {
            parts += current.toString()
            current.clear()
          }
          else -> current.append(c)
        }
        i++
      }
      parts += current.toString()
      return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val TOKEN68 = Regex("[A-Za-z0-9._~+/-]+=*")
  }
}

/**
 * Answers a server's challenges with [credentials]: Digest (RFC 7616, `SHA-256` or `MD5`, with
 * or without `-sess`, `qop=auth` or none) where the server offers it, else Basic (RFC 7617).
 * Thread-safe: segments sign their requests concurrently, each Digest request with the next
 * nonce count.
 */
internal class Authenticator(private val credentials: Credentials) {
  @Volatile
  private var answer: Answer? = null

  /** Whether a challenge was answered, so requests carry credentials. */
  val answering: Boolean get() = answer != null

  /** The scheme requests answer with, such as `Digest`, or `null` before a challenge. */
  val scheme: String? get() = answer?.scheme

  /**
   * Takes the best of [challenges] this authenticator answers, returning `false` when there is
   * none, or when the server asks again with the challenge it answered, which means the
   * credentials were refused. A Digest challenge marked `stale` asks again with a new nonce,
   * which is no refusal.
   */
  fun accept(challenges: List<Challenge>): Boolean {
    val chosen = pick(challenges) ?: return false
    val current = answer
    if (current != null && current.sameAs(chosen)) return false
    answer = when (chosen.scheme.lowercase()) {
      "digest" -> Answer.Digest(chosen)
      else -> Answer.Basic
    }
    return true
  }

  /** The `Authorization` value for [method] on [uri], or `null` before a challenge. */
  fun authorization(method: String, uri: String): String? =
    when (val current = answer) {
      null -> null
      Answer.Basic -> basic()
      is Answer.Digest -> digest(current, method, uri, cnonce())
    }

  private fun basic(): String =
    "Basic " + Base64.encode("${credentials.user}:${credentials.password}".encodeToByteArray())

  /** The Digest `Authorization` for [method] on [uri] with the client nonce [cnonce]. */
  internal fun digest(answer: Answer.Digest, method: String, uri: String, cnonce: String): String {
    val params = answer.challenge.params
    val realm = params["realm"].orEmpty()
    val nonce = params["nonce"].orEmpty()
    val algorithm = params["algorithm"] ?: "MD5"
    val hash = hashOf(algorithm) ?: error("Unsupported algorithm")
    val session = algorithm.endsWith("-sess", ignoreCase = true)
    val qop = answer.qop
    val nc = answer.count.incrementAndFetch().toString(16).padStart(8, '0')
    var ha1 = hash("${credentials.user}:$realm:${credentials.password}")
    if (session) ha1 = hash("$ha1:$nonce:$cnonce")
    val ha2 = hash("$method:$uri")
    val response = if (qop != null) {
      hash("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
    } else {
      hash("$ha1:$nonce:$ha2")
    }
    val userhash = params["userhash"].equals("true", ignoreCase = true)
    val user = if (userhash) hash("${credentials.user}:$realm") else credentials.user
    return buildString {
      append("Digest username=").append(quote(user))
      append(", realm=").append(quote(realm))
      append(", nonce=").append(quote(nonce))
      append(", uri=").append(quote(uri))
      append(", response=").append(quote(response))
      params["algorithm"]?.let { append(", algorithm=").append(it) }
      params["opaque"]?.let { append(", opaque=").append(quote(it)) }
      if (qop != null) {
        append(", qop=").append(qop)
        append(", nc=").append(nc)
        append(", cnonce=").append(quote(cnonce))
      }
      if (userhash) append(", userhash=true")
    }
  }

  /** The challenge to answer: Digest with the strongest algorithm offered, else Basic. */
  private fun pick(challenges: List<Challenge>): Challenge? {
    val digests = challenges.filter { challenge ->
      challenge.scheme.equals("digest", ignoreCase = true) &&
        hashOf(challenge.params["algorithm"] ?: "MD5") != null &&
        challenge.params["nonce"] != null &&
        (challenge.params["qop"]?.let { "auth" in qops(it) } ?: true)
    }
    return digests.firstOrNull { it.params["algorithm"]?.startsWith("SHA-256", true) == true }
      ?: digests.firstOrNull()
      ?: challenges.firstOrNull { it.scheme.equals("basic", ignoreCase = true) }
  }

  /** What requests answer with. */
  internal sealed interface Answer {
    val scheme: String

    /** Whether the server repeating [challenge] refuses this answer. */
    fun sameAs(challenge: Challenge): Boolean

    data object Basic : Answer {
      override val scheme: String = "Basic"

      override fun sameAs(challenge: Challenge): Boolean =
        challenge.scheme.equals("basic", ignoreCase = true)
    }

    class Digest(val challenge: Challenge) : Answer {
      override val scheme: String = "Digest"

      /** `auth` when the server asks for a quality of protection, else `null` (RFC 2069). */
      val qop: String? = challenge.params["qop"]?.let { "auth" }

      /** Requests sent with this nonce so far. */
      val count = AtomicInt(0)

      override fun sameAs(challenge: Challenge): Boolean =
        challenge.scheme.equals("digest", ignoreCase = true) &&
          !challenge.params["stale"].equals("true", ignoreCase = true) &&
          challenge.params["nonce"] == this.challenge.params["nonce"]
    }
  }

  private companion object {
    fun qops(value: String): List<String> = value.split(',').map { it.trim().lowercase() }

    fun cnonce(): String = Random.nextBytes(16).toHex()

    /** The hash of a Digest [algorithm], as lowercase hex of a UTF-8 string, or `null`. */
    fun hashOf(algorithm: String): ((String) -> String)? {
      val digest = when (algorithm.lowercase().removeSuffix("-sess")) {
        "md5" -> ChecksumAlgorithm.MD5
        "sha-256" -> ChecksumAlgorithm.SHA_256
        else -> return null
      }
      return { text -> digest.newDigest().update(text.encodeToByteArray()).digest().toHex() }
    }

    fun quote(value: String): String =
      "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun ByteArray.toHex(): String =
      joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
  }
}
