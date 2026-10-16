package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.engine.TorrentTaskSpec
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Opt-in (`KETCH_TORRENT_THROUGHPUT=1`): how fast one engine downloads a v1 torrent from
 * [LoopbackSeeder]s, and the CPU it spends doing so, per piece length and emulated round trip.
 * The engine keeps its default buffer limits; uploads, DHT, uTP, encryption and port mapping are
 * off so only the download path is measured. The seeders' own threads are subtracted.
 *
 * | Variable | Default |
 * | --- | --- |
 * | `KETCH_THROUGHPUT_PIECES` | `16384,262144,4194304,16777216` |
 * | `KETCH_THROUGHPUT_RTT_MS` | `0,50` |
 * | `KETCH_THROUGHPUT_SEEDERS` | `4` |
 * | `KETCH_THROUGHPUT_BYTES` | 256 MiB, without a round trip |
 * | `KETCH_THROUGHPUT_DELAYED_BYTES` | 64 MiB, with one |
 * | `KETCH_THROUGHPUT_RUNS` | `3` |
 * | `KETCH_THROUGHPUT_LABEL` | `run`, the report's first column |
 * | `KETCH_THROUGHPUT_REPORT` | `build/reports/torrent-throughput.md`, appended to |
 */
class V1ThroughputTest {
  private val os =
    ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

  private class Sample(
    val seconds: Double,
    val cpuSeconds: Double,
    val allocatedBytes: Long,
    val gcMs: Long,
    val complete: Boolean,
    val bytes: Long,
  )

  @Test
  fun measure() = runTest(timeout = 240.minutes) {
    check(System.getenv("KETCH_TORRENT_THROUGHPUT") == "1")
    fun env(name: String, default: String) =
      System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    val pieceLengths = env("KETCH_THROUGHPUT_PIECES", "16384,262144,4194304,16777216")
      .split(',').map(String::toInt)
    val rtts = env("KETCH_THROUGHPUT_RTT_MS", "0,50").split(',').map(String::toLong)
    val seeders = env("KETCH_THROUGHPUT_SEEDERS", "4").toInt()
    val bytes = env("KETCH_THROUGHPUT_BYTES", "${256 * 1024 * 1024}").toInt()
    val delayedBytes = env("KETCH_THROUGHPUT_DELAYED_BYTES", "${64 * 1024 * 1024}").toInt()
    val runs = env("KETCH_THROUGHPUT_RUNS", "3").toInt()
    val label = env("KETCH_THROUGHPUT_LABEL", "run")
    val report = File(env("KETCH_THROUGHPUT_REPORT", "build/reports/torrent-throughput.md"))
      .absoluteFile
    val payload = Random(162).nextBytes(maxOf(bytes, delayedBytes))
    withContext(Dispatchers.IO) {
      // Warms the JIT up on the same paths; not reported.
      download(payload, minOf(payload.size, 64 * 1024 * 1024), 262_144, 0, seeders)
      report.parentFile.mkdirs()
      if (!report.exists()) {
        report.appendText("| Label | Piece | RTT | Seeders | Payload | MB/s (median, min–max) " +
          "| CPU s/GiB | Allocated GiB/GiB | GC ms | Complete |\n" +
          "| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- |\n")
      }
      for (rtt in rtts) {
        for (pieceLength in pieceLengths) {
          val size = if (rtt == 0L) bytes else delayedBytes
          val samples = (1..runs).map { download(payload, size, pieceLength, rtt, seeders) }
          val rates = samples.map { it.bytes / it.seconds / 1_000_000 }.sorted()
          fun median(values: List<Double>) = values.sorted()[values.size / 2]
          val gib = 1024.0 * 1024 * 1024
          val row = "| $label | ${pieceLength / 1024} KiB | $rtt ms | $seeders | " +
            "${size / (1024 * 1024)} MiB | " +
            "%.1f (%.1f–%.1f) | %.2f | %.2f | %d | %s |".format(median(rates), rates.first(),
              rates.last(), median(samples.map { it.cpuSeconds / (it.bytes / gib) }),
              median(samples.map { it.allocatedBytes / gib / (it.bytes / gib) }),
              samples.map { it.gcMs }.sorted()[samples.size / 2],
              if (samples.all { it.complete }) "yes" else
                "${samples.count { it.complete }}/${samples.size}")
          println(row)
          report.appendText(row + "\n")
        }
      }
    }
  }

  private suspend fun download(
    payload: ByteArray,
    length: Int,
    pieceLength: Int,
    rttMs: Long,
    seederCount: Int,
  ): Sample {
    val hashes = java.io.ByteArrayOutputStream()
    val sha1 = MessageDigest.getInstance("SHA-1")
    var offset = 0
    while (offset < length) {
      val end = minOf(length, offset + pieceLength)
      sha1.update(payload, offset, end - offset)
      hashes.write(sha1.digest())
      offset = end
    }
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
      "announce" to "http://tracker.invalid/announce",
      "info" to mapOf("name" to "payload", "length" to length.toLong(),
        "piece length" to pieceLength.toLong(), "pieces" to hashes.toByteArray()),
    )))
    val seeds = (1..seederCount).map {
      LoopbackSeeder(payload, length, pieceLength, metadata.infoHash.toBytes(), rttMs)
    }
    val tracker = object : HttpEngine {
      override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
        error("unused")

      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        onData(Bencode.encode(mapOf("interval" to 1800L, "peers" to seeds.map {
          mapOf("ip" to "127.0.0.1", "port" to it.port.toLong())
        })))
      }

      override fun close() = Unit
    }
    val root = Files.createTempDirectory("ketch-throughput").toFile()
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, portMapping = false,
      encryption = TorrentEncryption.DISABLED, utpEnabled = false, localDiscovery = false),
      createTorrentNetwork(), TorrentHttp(tracker), allowLocalPeers = true,
      listenHost = "127.0.0.1")
    try {
      engine.start()
      val session = engine.addTask(TorrentTaskSpec("throughput", metadata,
        File(root, "payload").path, emptySet()))
      val gcBefore = gcMs()
      // The seeders' work is taken over the same interval as the process's, threads that end
      // before it does included.
      val seedersBefore = seeds.map { it.usage() }
      val cpuBefore = os.processCpuTime
      val allocatedBefore = threads.totalThreadAllocatedBytes
      val started = TimeSource.Monotonic.markNow()
      session.resume()
      val finished = withTimeoutOrNull(5.minutes) {
        session.state.first {
          it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
        }
      }
      val seconds = started.elapsedNow().inWholeNanoseconds / 1e9
      val cpuAfter = os.processCpuTime
      val allocatedAfter = threads.totalThreadAllocatedBytes
      val seedersAfter = seeds.map { it.usage() }
      val seederCpu = seedersAfter.sumOf { it.cpuNanos } - seedersBefore.sumOf { it.cpuNanos }
      val seederAllocated =
        seedersAfter.sumOf { it.allocatedBytes } - seedersBefore.sumOf { it.allocatedBytes }
      val cpu = cpuAfter - cpuBefore - seederCpu
      val allocated = allocatedAfter - allocatedBefore - seederAllocated
      val complete = finished == TorrentSessionState.FINISHED
      check(finished != TorrentSessionState.STOPPED) {
        "Download stopped: ${session.failure.value?.stackTraceToString()}"
      }
      if (complete) verify(File(root, "payload"), payload, length)
      return Sample(seconds, cpu / 1e9, allocated, gcMs() - gcBefore, complete,
        if (complete) length.toLong() else session.downloadedBytes.value)
    } finally {
      engine.stop()
      seeds.forEach { it.close() }
      root.deleteRecursively()
    }
  }

  private fun gcMs() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }

  private fun verify(file: File, payload: ByteArray, length: Int) {
    check(file.length() == length.toLong()) { "Output has ${file.length()} bytes, not $length" }
    val buffer = ByteArray(1024 * 1024)
    file.inputStream().use { input ->
      var offset = 0
      while (offset < length) {
        val count = input.read(buffer)
        check(count > 0)
        check(java.util.Arrays.equals(buffer, 0, count, payload, offset, offset + count)) {
          "Output differs near byte $offset"
        }
        offset += count
      }
    }
  }
}
