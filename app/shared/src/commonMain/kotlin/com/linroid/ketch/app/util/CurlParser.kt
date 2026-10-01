package com.linroid.ketch.app.util

/**
 * A download described by a `curl` command, such as one copied with a browser's "Copy as cURL".
 *
 * @property urls the links the command fetches, in order.
 * @property headers request headers to send with them: the `-H` headers plus the `-b` cookies,
 *   the `-e` referrer and the `-A` user agent. Headers that Ketch sets itself or that would make
 *   the server answer differently, such as `Range`, `Accept-Encoding` and `If-None-Match`, are
 *   left out.
 * @property globOff whether `-g` turned off curl's `[01-12]` and `{a,b}` ranges.
 */
data class CurlCommand(
  val urls: List<String>,
  val headers: Map<String, String> = emptyMap(),
  val globOff: Boolean = false,
)

/**
 * Reads `curl` commands as browsers copy them, in POSIX shell quoting (bash, including `$'...'`)
 * or Windows `cmd` quoting (`^` escapes). Only the options that describe the request are read;
 * the values of other options are skipped, so they are never taken for links.
 */
object CurlParser {
  /** Whether [text] starts with a `curl` command, optionally after a `$` shell prompt. */
  fun isCurl(text: String): Boolean = CURL_START.containsMatchIn(text)

  /**
   * Reads [command], or returns `null` when it is not a complete `curl` command with at least
   * one link: a quote left open, an option without its value, or no link at all. Links without
   * a scheme get `https://`.
   */
  fun parse(command: String): CurlCommand? {
    if (!isCurl(command)) return null
    val tokens = tokenize(command) ?: return null
    var i = if (tokens.firstOrNull() == "$") 1 else 0
    val program = tokens.getOrNull(i)?.lowercase() ?: return null
    if (program != "curl" && program != "curl.exe") return null
    val urls = mutableListOf<String>()
    val headers = HeaderBuilder()
    var globOff = false
    var optionsEnded = false

    // The value of the option at i, which is the next word.
    fun nextValue(): String? = tokens.getOrNull(++i)

    fun addUrl(value: String) {
      LinkParser.linkFromToken(value, pathOptional = true)?.let(urls::add)
    }

    i++
    while (i < tokens.size) {
      val token = tokens[i]
      when {
        optionsEnded || !token.startsWith('-') || token == "-" -> addUrl(token)
        token == "--" -> optionsEnded = true
        token.startsWith("--") -> {
          val name = token.substring(2).lowercase()
          val option = LONG_OPTIONS[name]
          when {
            name == "globoff" -> globOff = true
            name == "url" -> addUrl(nextValue() ?: return null)
            option != null -> headers.apply(option, nextValue() ?: return null)
            name in LONG_OPTIONS_WITH_VALUE -> if (nextValue() == null) return null
          }
        }
        else -> {
          // A cluster such as -sSLo: an option that takes a value ends it, and the rest of the
          // word, or else the next word, is that value.
          for (j in 1 until token.length) {
            val option = token[j]
            if (option == 'g') globOff = true
            if (option in SHORT_OPTIONS_WITH_VALUE) {
              headers.apply(option, token.substring(j + 1).ifEmpty { nextValue() ?: return null })
              break
            }
          }
        }
      }
      i++
    }
    if (urls.isEmpty()) return null
    return CurlCommand(urls, headers.build(), globOff)
  }

  /** Whether [command] goes on past its end: a quote is still open or the last line continues. */
  internal fun isIncomplete(command: String): Boolean = tokenize(command) == null
}

private val CURL_START = Regex("^\\s*(?:\\$\\s+)?curl(?:\\.exe)?(?:\\s|$)", RegexOption.IGNORE_CASE)

// Request headers that Ketch sets itself, that only make sense on one connection, or that make
// the server answer with something other than the file.
private val DROPPED_HEADERS = setOf(
  "accept-encoding", "connection", "content-length", "expect", "host", "if-match",
  "if-modified-since", "if-none-match", "if-range", "if-unmodified-since", "keep-alive",
  "proxy-connection", "range", "te", "transfer-encoding", "upgrade",
)

private val LONG_OPTIONS = mapOf(
  "header" to 'H',
  "cookie" to 'b',
  "referer" to 'e',
  "user-agent" to 'A',
)

private const val SHORT_OPTIONS_WITH_VALUE = "AbcCdDeEFHKmoPQrtTuUwxXyYz"

private val LONG_OPTIONS_WITH_VALUE = setOf(
  "abstract-unix-socket", "alt-svc", "aws-sigv4", "cacert", "capath", "cert", "cert-type",
  "ciphers", "config", "connect-timeout", "connect-to", "continue-at", "cookie-jar",
  "create-file-mode", "crlfile", "curves", "data", "data-ascii", "data-binary", "data-raw",
  "data-urlencode", "delegation", "dns-interface", "dns-ipv4-addr", "dns-ipv6-addr",
  "dns-servers", "doh-url", "dump-header", "ech", "egd-file", "engine", "etag-compare",
  "etag-save", "expect100-timeout", "form", "form-string", "ftp-account",
  "ftp-alternative-to-user", "ftp-method", "ftp-port", "ftp-ssl-ccc-mode",
  "happy-eyeballs-timeout-ms", "haproxy-clientip", "hostpubmd5", "hostpubsha256", "hsts",
  "interface", "ip-tos", "ipfs-gateway", "json", "keepalive-cnt", "keepalive-time", "key",
  "key-type", "krb", "libcurl", "limit-rate", "local-port", "login-options", "mail-auth",
  "mail-from", "mail-rcpt", "max-filesize", "max-redirs", "max-time", "netrc-file", "noproxy",
  "oauth2-bearer", "output", "output-dir", "parallel-max", "pass", "pinnedpubkey", "preproxy",
  "proto", "proto-default", "proto-redir", "proxy", "proxy-cacert", "proxy-capath",
  "proxy-cert", "proxy-cert-type", "proxy-ciphers", "proxy-crlfile", "proxy-header",
  "proxy-key", "proxy-key-type", "proxy-pass", "proxy-pinnedpubkey", "proxy-service-name",
  "proxy-tls13-ciphers", "proxy-tlsauthtype", "proxy-tlspassword", "proxy-tlsuser",
  "proxy-user", "proxy1.0", "pubkey", "quote", "random-file", "range", "rate", "request",
  "request-target", "resolve", "retry", "retry-delay", "retry-max-time", "sasl-authzid",
  "service-name", "socks4", "socks4a", "socks5", "socks5-gssapi-service", "socks5-hostname",
  "speed-limit", "speed-time", "stderr", "telnet-option", "tftp-blksize", "time-cond",
  "tls-max", "tls13-ciphers", "tlsauthtype", "tlspassword", "tlsuser", "trace", "trace-ascii",
  "trace-config", "unix-socket", "upload-file", "url-query", "user", "variable",
  "vlan-priority", "write-out",
)

/** Collects headers, replacing repeats regardless of case and joining cookies. */
private class HeaderBuilder {
  private val headers = LinkedHashMap<String, String>()

  fun apply(option: Char, value: String) {
    when (option) {
      'H' -> header(value)
      // Without "=" the value names a cookie file.
      'b' -> if ('=' in value) cookie("Cookie", value.trim())
      'e' -> setIfNotBlank("Referer", value.removeSuffix(";auto"))
      'A' -> setIfNotBlank("User-Agent", value)
    }
  }

  fun build(): Map<String, String> = headers.toMap()

  private fun header(line: String) {
    // "@file" reads headers from a file; "Name;" sends an empty header and "Name:" removes one.
    if (line.startsWith('@')) return
    val colon = line.indexOf(':')
    if (colon <= 0) return
    val name = line.substring(0, colon).trim()
    val value = line.substring(colon + 1).trim()
    if (value.isEmpty() || name.lowercase() in DROPPED_HEADERS || name.startsWith(':')) return
    if (name.equals("Cookie", ignoreCase = true)) cookie(name, value) else set(name, value)
  }

  private fun cookie(name: String, value: String) {
    val existing = keyOf(name)
    if (existing == null) {
      headers[name] = value
    } else {
      headers[existing] = headers.getValue(existing) + "; " + value
    }
  }

  private fun set(name: String, value: String) {
    keyOf(name)?.let(headers::remove)
    headers[name] = value
  }

  private fun setIfNotBlank(name: String, value: String) {
    if (value.isNotBlank()) set(name, value.trim())
  }

  private fun keyOf(name: String): String? =
    headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
}

private fun tokenize(command: String): List<String>? =
  if (isCmd(command)) tokenizeCmd(command) else tokenizePosix(command)

// Browsers copy for cmd with ^-escaped quotes and ^ at the end of each continued line.
private fun isCmd(command: String): Boolean =
  "^\"" in command || CMD_CONTINUATION.containsMatchIn(command)

private val CMD_CONTINUATION = Regex("\\^[ \\t]*(?:\\r?\\n|$)")

/**
 * Splits a POSIX shell command into words: `'...'`, `"..."` with its backslash escapes,
 * `$'...'` with C escapes, and backslash-newline continuations. Returns `null` when a quote is
 * left open or the command ends with a continuation.
 */
private fun tokenizePosix(command: String): List<String>? {
  val words = WordBuilder()
  var i = 0
  while (i < command.length) {
    val char = command[i]
    when {
      char == '\\' -> {
        val next = command.getOrNull(i + 1) ?: return null
        if (next == '\r' && command.getOrNull(i + 2) == '\n') {
          i += 3
          continue
        }
        if (next != '\n') words.append(next)
        i += 2
        continue
      }
      char == '\'' -> {
        val end = command.indexOf('\'', i + 1)
        if (end < 0) return null
        words.append(command.substring(i + 1, end))
        i = end + 1
        continue
      }
      char == '$' && command.getOrNull(i + 1) == '\'' -> {
        i = readAnsiC(command, i + 2, words) ?: return null
        continue
      }
      char == '"' -> {
        i = readDoubleQuoted(command, i + 1, words) ?: return null
        continue
      }
      char.isWhitespace() -> words.end()
      else -> words.append(char)
    }
    i++
  }
  return words.finish()
}

/** Reads a `"..."` body starting at [start]; returns the index after the closing quote. */
private fun readDoubleQuoted(command: String, start: Int, words: WordBuilder): Int? {
  words.append("")
  var i = start
  while (i < command.length) {
    val char = command[i]
    when {
      char == '"' -> return i + 1
      char == '\\' && i + 1 < command.length && command[i + 1] in "$`\"\\\n" -> {
        if (command[i + 1] != '\n') words.append(command[i + 1])
        i += 2
        continue
      }
      else -> words.append(char)
    }
    i++
  }
  return null
}

/** Reads a `$'...'` body starting at [start]; returns the index after the closing quote. */
private fun readAnsiC(command: String, start: Int, words: WordBuilder): Int? {
  words.append("")
  var i = start
  while (i < command.length) {
    val char = command[i]
    if (char == '\'') return i + 1
    if (char != '\\' || i + 1 >= command.length) {
      words.append(char)
      i++
      continue
    }
    val escape = command[i + 1]
    i += 2
    when (escape) {
      'n' -> words.append('\n')
      't' -> words.append('\t')
      'r' -> words.append('\r')
      'a' -> words.append('\u0007')
      'b' -> words.append('\b')
      'e', 'E' -> words.append('\u001B')
      'f' -> words.append('\u000C')
      'v' -> words.append('\u000B')
      'x', 'u', 'U' -> {
        val maxDigits = when (escape) {
          'x' -> 2
          'u' -> 4
          else -> 8
        }
        var end = i
        while (end < command.length && end - i < maxDigits && command[end].isHexDigit()) end++
        if (end == i) {
          words.append("\\$escape")
        } else {
          words.appendCodePoint(command.substring(i, end).toInt(16))
          i = end
        }
      }
      in '0'..'7' -> {
        var end = i
        while (end < command.length && end - i < 2 && command[end] in '0'..'7') end++
        words.append((escape + command.substring(i, end)).toInt(8).toChar())
        i = end
      }
      '\\', '\'', '"', '?' -> words.append(escape)
      else -> words.append("\\$escape")
    }
  }
  return null
}

/**
 * Splits a Windows `cmd` command into arguments: `^` escapes the next character (and continues
 * the line before a newline) outside `cmd` quotes, then the arguments are split like the C
 * runtime does, with `"` grouping and `\"` a literal quote. Returns `null` when a quote is left
 * open or the command ends with a continuation.
 */
private fun tokenizeCmd(command: String): List<String>? {
  val unescaped = StringBuilder()
  var cmdQuoted = false
  var i = 0
  while (i < command.length) {
    val char = command[i]
    when {
      char == '"' -> {
        cmdQuoted = !cmdQuoted
        unescaped.append(char)
      }
      char == '^' && !cmdQuoted -> {
        val next = command.getOrNull(i + 1) ?: return null
        // An escaped quote reaches the argument parser without opening a cmd quote.
        when {
          next == '\n' -> i++
          next == '\r' && command.getOrNull(i + 2) == '\n' -> i += 2
          else -> {
            unescaped.append(next)
            i++
          }
        }
      }
      else -> unescaped.append(char)
    }
    i++
  }
  val text = unescaped.toString()
  val words = WordBuilder()
  var quoted = false
  i = 0
  while (i < text.length) {
    val char = text[i]
    when {
      char == '\\' -> {
        var end = i
        while (end < text.length && text[end] == '\\') end++
        val count = end - i
        if (text.getOrNull(end) == '"') {
          words.append("\\".repeat(count / 2))
          if (count % 2 == 1) {
            words.append('"')
            end++
          }
        } else {
          words.append("\\".repeat(count))
        }
        i = end
        continue
      }
      char == '"' -> {
        quoted = !quoted
        words.append("")
      }
      char.isWhitespace() && !quoted -> words.end()
      else -> words.append(char)
    }
    i++
  }
  return if (quoted) null else words.finish()
}

/** Builds words; an empty quoted string still makes a word. */
private class WordBuilder {
  private val words = mutableListOf<String>()
  private val current = StringBuilder()
  private var started = false

  fun append(text: String) {
    current.append(text)
    started = true
  }

  fun append(char: Char) {
    current.append(char)
    started = true
  }

  fun appendCodePoint(codePoint: Int) {
    if (codePoint < 0x10000) {
      append(codePoint.toChar())
    } else {
      val offset = codePoint - 0x10000
      append((0xD800 + (offset shr 10)).toChar())
      append((0xDC00 + (offset and 0x3FF)).toChar())
    }
  }

  fun end() {
    if (started) words += current.toString()
    current.clear()
    started = false
  }

  fun finish(): List<String> {
    end()
    return words
  }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
