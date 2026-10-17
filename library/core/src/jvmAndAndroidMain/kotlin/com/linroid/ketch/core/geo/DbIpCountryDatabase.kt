package com.linroid.ketch.core.geo

import com.linroid.ketch.api.CountryDatabaseStatus
import com.linroid.ketch.api.CountryDatabaseStatus.State
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
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
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/**
 * [CountryLookup] over DB-IP's monthly IP to Country Lite database (CC BY 4.0), downloaded on
 * request to [path] (the `.csv.gz` as published, about 5 MB) and read into memory, about 10 MB,
 * as the instance starts.
 *
 * Host names resolve through the system, then through DNS over HTTPS (Cloudflare) when the
 * system only answers with addresses without a country, such as the 198.18.0.0/15 a proxy's
 * fake-IP mode answers with.
 */
class DbIpCountryDatabase(
  private val fileSystem: FileSystem,
  private val path: Path,
  dispatcher: CoroutineDispatcher,
) : CountryLookup {
  /** Keeps the database in [file], downloading and reading it on [Dispatchers.IO]. */
  constructor(file: File) : this(FileSystem.SYSTEM, file.toOkioPath(), Dispatchers.IO)

  private val log = KetchLogger("CountryDatabase")

  // One job at a time, so a download queued behind the first read waits for it.
  private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))

  // Host names resolve on a few threads of their own, each once (until the cache fills).
  private val resolver = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(4))
  private val names = ConcurrentHashMap<String, Optional<String>>()

  @Volatile
  private var table: CountryTable? = null

  @Volatile
  private var status = CountryDatabaseStatus(State.MISSING)
  private val downloading = AtomicBoolean()

  init {
    scope.launch {
      if (!fileSystem.exists(path)) return@launch
      status = CountryDatabaseStatus(State.DOWNLOADING)
      try {
        use(read(path))
      } catch (e: Exception) {
        log.w { "Cannot read the country database: ${e.describeCauses()}" }
        status = CountryDatabaseStatus(State.FAILED, error = e.message ?: e.toString())
      }
    }
  }

  override suspend fun status(): CountryDatabaseStatus = status

  override suspend fun download(): CountryDatabaseStatus {
    if (!downloading.compareAndSet(false, true)) return status
    status = CountryDatabaseStatus(State.DOWNLOADING)
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
        status = CountryDatabaseStatus(
          State.FAILED,
          updatedAt = status.updatedAt,
          error = e.message ?: e.toString(),
        )
      } finally {
        downloading.set(false)
      }
    }
    return status
  }

  override fun countryOf(host: String): String? {
    val table = table ?: return null
    if (parseIp(host) != null) return table.countryOf(host)
    names[host]?.let { return it.orElse(null) }
    if (names.size >= MAX_NAMES) names.clear()
    if (names.putIfAbsent(host, Optional.empty()) == null) {
      resolver.launch {
        countryOfName(table, host)?.let { names[host] = Optional.of(it) }
      }
    }
    return null
  }

  private fun use(loaded: CountryTable) {
    log.i { "Loaded ${loaded.size} country ranges" }
    table = loaded
    names.clear()
    val modified = fileSystem.metadataOrNull(path)?.lastModifiedAtMillis
    status = CountryDatabaseStatus(
      State.READY,
      updatedAt = modified?.let { Instant.fromEpochMilliseconds(it) },
    )
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

  /** The country of [host]'s first address that has one, over HTTPS when the system has none. */
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

  private companion object {
    const val TIMEOUT_MS = 30_000
    const val MAX_NAMES = 1024
    const val DOH_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_TIMEOUT_MS = 10_000

    fun urlOf(month: YearMonth) =
      "https://download.db-ip.com/free/dbip-country-lite-$month.csv.gz"
  }
}
