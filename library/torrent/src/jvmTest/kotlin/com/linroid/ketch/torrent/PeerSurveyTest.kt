package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Measures why public swarms do or do not connect; set `KETCH_TORRENT_SURVEY` to magnets or
 * `.torrent` URLs separated by whitespace (`KETCH_TORRENT_SURVEY_MINUTES` per torrent, default
 * 3; `KETCH_TORRENT_SURVEY_LOG` a level such as `debug` logs to stdout). Each runs as a real download, then the peers it could not use are probed over uTP (a SYN,
 * answered by peers that speak uTP) and, for those that closed our plaintext handshake, with an
 * MSE/PE opening (96 bytes, answered by peers that speak protocol encryption). The report goes to
 * stdout and `build/reports/torrent-survey.md`. Only v1 peers are recorded.
 */
class PeerSurveyTest {
  @Test
  fun survey() {
    val targets = System.getenv("KETCH_TORRENT_SURVEY").orEmpty().split(Regex("\\s+"))
      .filter { it.isNotBlank() }
    if (targets.isEmpty()) return
    val minutes = System.getenv("KETCH_TORRENT_SURVEY_MINUTES")?.toIntOrNull() ?: 3
    runTest(timeout = (minutes * targets.size + 5).minutes) {
      // Real peers and deadlines need wall-clock time, not runTest's virtual clock.
      withContext(Dispatchers.IO) {
        val report = StringBuilder("# Torrent peer survey\n")
        for (target in targets) report.append(survey(target, minutes))
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/torrent-survey.md").writeText(report.toString())
      }
    }
  }

  private suspend fun survey(target: String, minutes: Int): String {
    val reports = ConcurrentLinkedQueue<PeerOutcomeReport>()
    val directory = Files.createTempDirectory("ketch-peer-survey-").toFile()
    val source = TorrentDownloadSource(TorrentConfig())
    source.engineFactory = { KotlinTorrentEngine(it, peerOutcomes = { report -> reports += report }) }
    val level = System.getenv("KETCH_TORRENT_SURVEY_LOG")?.takeIf { it.isNotBlank() }
    val ketch = Ketch(
      httpEngine = KtorHttpEngine(),
      config = DownloadConfig(progressIntervalMs = 1000),
      logger = level?.let { Logger.console(LogLevel.valueOf(it.uppercase())) } ?: Logger.None,
      additionalSources = listOf(source),
    )
    val ended: DownloadState
    try {
      val task = ketch.download(
        DownloadRequest(url = target, destination = Destination(directory.path + File.separator)),
      )
      ended = withTimeoutOrNull(minutes.minutes) { task.state.first { it.isTerminal } }
        ?: task.state.value
      task.cancel()
    } finally {
      ketch.close()
      directory.deleteRecursively()
    }

    val dialed = LinkedHashMap<PeerEndpoint, PeerOutcomeReport>()
    val incoming = mutableMapOf<PeerOutcome, Int>()
    for (report in reports) {
      if (report.incoming) {
        incoming.merge(report.outcome, 1, Int::plus)
        continue
      }
      val previous = dialed[report.endpoint]
      if (previous == null || report.outcome > previous.outcome) dialed[report.endpoint] = report
    }
    val failed = dialed.values.filter { it.outcome.failed }
    val utp = probeUtp(failed.map { it.endpoint }.take(MAX_PROBES))
    val closed = failed.filter { it.outcome == PeerOutcome.HANDSHAKE_CLOSED }
      .map { it.endpoint }.take(MAX_PROBES)
    val mse = probeMse(closed)

    fun count(reports: Collection<PeerOutcomeReport>, flag: Int) =
      reports.count { it.flags and flag != 0 }
    return buildString {
      append("\n## ${target.take(120)}\n\n")
      append("- Ended as ${ended::class.simpleName}")
      progressOf(ended)?.let { append(", ${it.first} of ${it.second} bytes") }
      append(" after at most $minutes min\n")
      append("- Dialed ${dialed.size} endpoints, best outcome each:\n")
      for (outcome in PeerOutcome.entries) {
        val endpoints = dialed.values.filter { it.outcome == outcome }
        if (endpoints.isEmpty()) continue
        val answeredUtp = endpoints.count { it.endpoint in utp }
        append("  - ${outcome.label}: ${endpoints.size}")
        if (outcome.failed) append(" (uTP answered $answeredUtp)")
        append("\n")
      }
      append("- Incoming: ${incoming.entries.joinToString { "${it.key.label}=${it.value}" }
        .ifEmpty { "none" }}\n")
      append("- PEX flags: uTP ${count(dialed.values, PEX_FLAG_UTP)} " +
        "(failed ${count(failed, PEX_FLAG_UTP)}), encryption " +
        "${count(dialed.values, PEX_FLAG_ENCRYPTION)} " +
        "(failed ${count(failed, PEX_FLAG_ENCRYPTION)})\n")
      append("- uTP probe: ${utp.size} of ${minOf(failed.size, MAX_PROBES)} failed dials answered\n")
      append("- MSE probe: ${mse.size} of ${closed.size} closed handshakes answered\n")
    }
  }

  private fun progressOf(state: DownloadState): Pair<Long, Long>? = when (state) {
    is DownloadState.Downloading -> state.progress.downloadedBytes to state.progress.totalBytes
    is DownloadState.Paused -> state.progress.downloadedBytes to state.progress.totalBytes
    else -> null
  }

  /** Endpoints that answered a uTP SYN with a STATE packet for its connection. */
  private suspend fun probeUtp(endpoints: List<PeerEndpoint>): Set<PeerEndpoint> =
    withContext(Dispatchers.IO) {
      val answered = mutableSetOf<PeerEndpoint>()
      if (endpoints.isEmpty()) return@withContext answered
      DatagramChannel.open().use { channel ->
        channel.bind(InetSocketAddress(0))
        channel.configureBlocking(false)
        val probes = endpoints.mapNotNull { endpoint ->
          runCatching {
            Triple(endpoint, InetSocketAddress(InetAddress.getByName(endpoint.host),
              endpoint.port), Random.nextInt(65536))
          }.getOrNull()
        }
        val byKey = probes.associateBy { (_, address, id) -> address to id }
        // Twice, in case a SYN was lost.
        repeat(2) {
          for ((endpoint, address, id) in probes) {
            if (endpoint in answered) continue
            runCatching { channel.send(utpPacket(UTP_SYN, id), address) }
          }
          val until = System.nanoTime() + 2_000_000_000L
          val buffer = ByteBuffer.allocate(2048)
          while (System.nanoTime() < until) {
            buffer.clear()
            val from = channel.receive(buffer) as InetSocketAddress?
            if (from == null) {
              Thread.sleep(5)
              continue
            }
            buffer.flip()
            if (buffer.remaining() < 20 || buffer.get(0) != UTP_STATE) continue
            val id = buffer.getShort(2).toInt() and 0xffff
            val probe = byKey[InetSocketAddress(from.address, from.port) to id] ?: continue
            if (answered.add(probe.first)) {
              runCatching { channel.send(utpPacket(UTP_RESET, (id + 1) and 0xffff), from) }
            }
          }
        }
      }
      answered
    }

  private fun utpPacket(type: Byte, connectionId: Int): ByteBuffer =
    ByteBuffer.allocate(20).apply {
      put(type)
      put(0)
      putShort(connectionId.toShort())
      putInt((System.nanoTime() / 1000).toInt())
      putInt(0)
      putInt(1 shl 20)
      putShort(1)
      putShort(0)
      flip()
    }

  /**
   * Endpoints that answer 96 bytes standing for a Diffie-Hellman key, as an MSE/PE opening
   * starts, with at least as many of their own; peers without encryption close instead.
   */
  private suspend fun probeMse(endpoints: List<PeerEndpoint>): Set<PeerEndpoint> = coroutineScope {
    val permits = Semaphore(32)
    endpoints.map { endpoint ->
      async(Dispatchers.IO) {
        permits.withPermit {
          runCatching {
            Socket().use { socket ->
              socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 5000)
              socket.soTimeout = 5000
              // Below the 768-bit MSE prime, which starts with 0xFF.
              val key = Random.nextBytes(96).also { it[0] = 0x7f }
              socket.getOutputStream().apply { write(key); flush() }
              val input = socket.getInputStream()
              var read = 0
              val buffer = ByteArray(96)
              while (read < 96) {
                val n = input.read(buffer, read, 96 - read)
                if (n < 0) break
                read += n
              }
              endpoint.takeIf { read == 96 }
            }
          }.getOrNull()
        }
      }
    }.awaitAll().filterNotNull().toSet()
  }

  private companion object {
    const val MAX_PROBES = 400
    const val UTP_SYN: Byte = 0x41
    const val UTP_STATE: Byte = 0x21
    const val UTP_RESET: Byte = 0x31
  }
}
