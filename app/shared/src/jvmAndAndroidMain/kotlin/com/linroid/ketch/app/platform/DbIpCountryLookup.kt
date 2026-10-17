package com.linroid.ketch.app.platform

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * [CountryLookup] over DB-IP's monthly IP to Country Lite database, downloaded on request to
 * [path] (the `.csv.gz` as published, about 5 MB) and read into memory by [load].
 */
class DbIpCountryLookup(
  private val fileSystem: FileSystem,
  private val path: Path,
  dispatcher: CoroutineDispatcher,
) : CountryLookup {
  private val log = KetchLogger("CountryLookup")
  // One job at a time, so a download queued behind the first read is never skipped.
  private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
  private val _state = MutableStateFlow<CountryLookupState>(CountryLookupState.Missing)
  override val state: StateFlow<CountryLookupState> = _state.asStateFlow()

  @Volatile
  private var table: CountryTable? = null
  private val loadRequested = AtomicBoolean()
  private val downloading = AtomicBoolean()

  override fun load() {
    if (!loadRequested.compareAndSet(false, true)) return
    scope.launch {
      if (table != null || downloading.get()) return@launch
      if (!fileSystem.exists(path)) {
        _state.value = CountryLookupState.Missing
        return@launch
      }
      _state.value = CountryLookupState.Loading
      try {
        use(read(path))
      } catch (e: Exception) {
        log.w { "Cannot read $path: ${e.describeCauses()}" }
        _state.value = CountryLookupState.Failed(e.message ?: e.toString())
      }
    }
  }

  override fun download() {
    if (!downloading.compareAndSet(false, true)) return
    _state.value = CountryLookupState.Loading
    scope.launch {
      val tmp = "$path.tmp".toPath()
      try {
        path.parent?.let { fileSystem.createDirectories(it) }
        fetch(tmp)
        val loaded = read(tmp)
        fileSystem.atomicMove(tmp, path)
        use(loaded)
      } catch (e: Exception) {
        log.w { "Cannot download the country database: ${e.describeCauses()}" }
        fileSystem.delete(tmp, mustExist = false)
        _state.value = CountryLookupState.Failed(e.message ?: e.toString())
      } finally {
        downloading.set(false)
      }
    }
  }

  // Host names resolve on a few threads of their own, each once (until the cache fills).
  private val resolver = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(4))
  private val names = ConcurrentHashMap<String, Optional<String>>()
  private val _resolved = MutableStateFlow(0)
  override val resolved: StateFlow<Int> = _resolved.asStateFlow()

  /**
   * The country of [host]'s first address that has one. A name the system resolves only to
   * addresses without one, such as the 198.18.0.0/15 a proxy's fake-IP mode answers with, is
   * asked of DNS over HTTPS.
   */
  private fun countryOfName(table: CountryTable, host: String): String? {
    val system = try {
      InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
    } catch (e: Exception) {
      log.d { "Cannot resolve a host: ${e.describeCauses()}" }
      emptyList()
    }
    system.firstNotNullOfOrNull { table.countryOf(it) }?.let { return it }
    return try {
      resolveOverHttps(host).firstNotNullOfOrNull { table.countryOf(it) }
    } catch (e: Exception) {
      log.d { "Cannot resolve a host over HTTPS: ${e.describeCauses()}" }
      null
    }
  }

  /** [host]'s IPv4 addresses from Cloudflare's DNS over HTTPS JSON API. */
  private fun resolveOverHttps(host: String): List<String> {
    val name = URLEncoder.encode(host, Charsets.UTF_8.name())
    val connection = URI("$DOH_URL?name=$name&type=A").toURL().openConnection()
      as HttpURLConnection
    try {
      connection.connectTimeout = DOH_TIMEOUT_MS
      connection.readTimeout = DOH_TIMEOUT_MS
      connection.setRequestProperty("Accept", "application/dns-json")
      if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
      val body = connection.inputStream.use { it.readBytes().decodeToString() }
      val answers = Json.parseToJsonElement(body).jsonObject["Answer"] as? JsonArray
        ?: return emptyList()
      return answers.mapNotNull { answer ->
        val record = answer.jsonObject
        record["data"]?.jsonPrimitive?.content
          .takeIf { record["type"]?.jsonPrimitive?.content == "1" }
      }
    } finally {
      connection.disconnect()
    }
  }

  override fun countryOf(host: String): String? {
    val table = table ?: return null
    if (parseIp(host) != null) return table.countryOf(host)
    names[host]?.let { return it.orElse(null) }
    if (names.size >= MAX_NAMES) names.clear()
    if (names.putIfAbsent(host, Optional.empty()) == null) {
      resolver.launch {
        val code = countryOfName(table, host) ?: return@launch
        names[host] = Optional.of(code)
        _resolved.update { it + 1 }
      }
    }
    return null
  }

  private fun use(loaded: CountryTable) {
    log.i { "Loaded ${loaded.size} country ranges" }
    table = loaded
    _state.value = CountryLookupState.Ready
  }

  private fun read(file: Path): CountryTable =
    GZIPInputStream(fileSystem.source(file).buffer().inputStream()).bufferedReader().use {
      CountryTable.parse(it.lineSequence())
    }

  /** Saves this month's database to [target], or last month's before this one's is out. */
  private fun fetch(target: Path) {
    val month = YearMonth.now(ZoneOffset.UTC)
    try {
      fetch(urlOf(month), target)
    } catch (_: FileNotFoundException) {
      fetch(urlOf(month.minusMonths(1)), target)
    }
  }

  private fun fetch(url: String, target: Path) {
    log.d { "Downloading $url" }
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      connection.setRequestProperty("User-Agent", "Ketch/${KetchApi.VERSION}")
      val code = connection.responseCode
      if (code == HttpURLConnection.HTTP_NOT_FOUND) throw FileNotFoundException(url)
      if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
      connection.inputStream.use { input ->
        fileSystem.write(target) { input.copyTo(outputStream()) }
      }
    } finally {
      connection.disconnect()
    }
  }

  private companion object {
    const val TIMEOUT_MS = 30_000
    const val MAX_NAMES = 1024
    const val DOH_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_TIMEOUT_MS = 10_000

    fun urlOf(month: YearMonth) =
      "https://download.db-ip.com/free/dbip-country-lite-$month.csv.gz"
  }
}
