package com.linroid.ketch.torrent

import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Opt-in, isolated downloader processes with streamed fixtures and a pinned, shared TCP seeder. */
class TorrentBenchmarkTest {
  @Test
  fun compareKotlinAndNativeDownloaders() = runTest(timeout = 180.minutes) {
    check(System.getenv("KETCH_TORRENT_BENCHMARK") == "1")
    val reportFile = File(System.getenv("KETCH_BENCHMARK_REPORT")?.takeIf { it.isNotBlank() } ?:
      "build/reports/torrent-benchmark.json").absoluteFile
    reportFile.parentFile.mkdirs()
    reportFile.delete() // Invalid input must not leave an earlier complete report available.
    val sizes = (System.getenv("KETCH_BENCHMARK_BYTES")?.takeIf { it.isNotBlank() } ?: "8388645")
      .split(',').map(String::toLong)
    val runs = (System.getenv("KETCH_BENCHMARK_RUNS")?.takeIf { it.isNotBlank() } ?: "1").toInt()
    require(sizes.isNotEmpty() && sizes.size <= 8 && sizes.distinct().size == sizes.size)
    require(sizes.all { it in 1..10L * 1024 * 1024 * 1024 } && runs in 1..10)
    val revision = checkNotNull(System.getenv("KETCH_BENCHMARK_REVISION"))
    require(revision.matches(Regex("[0-9a-f]{40}")))
    withContext(Dispatchers.IO) {
      val results = mutableListOf<BenchmarkRun>()
      val report = BenchmarkReport(revision, System.getProperty("os.name"),
        System.getProperty("os.version"), System.getProperty("os.arch"),
        System.getProperty("java.runtime.version"), Runtime.getRuntime().availableProcessors(),
        "transmission", ConformanceClients.version("transmission"), sizes, runs)
      var failureDetails: String? = null
      fun save(complete: Boolean) {
        reportFile.writeText(Json.encodeToString(report.copy(
          complete = complete, results = results, failure = failureDetails,
        )))
      }
      save(false)
      for (size in sizes) {
        val root = Files.createTempDirectory("ketch-benchmark").toFile()
        val seeder = TorrentBenchmarkSeeder(root)
        val tracker = TorrentBenchmarkTracker(seeder.peerPort, seeder.dataHost)
        try {
          require(root.usableSpace >= size * 2 + 2L * 1024 * 1024 * 1024) {
            "Benchmark needs space for one seed, one download, and 2 GiB headroom"
          }
          val seed = root.resolve("seed").apply { mkdirs() }
          val generation = TimeSource.Monotonic.markNow()
          val fixture = TorrentBenchmarkFixture.create(seed.resolve("payload"), size)
          val generationMs = generation.elapsedNow().inWholeMilliseconds
          val fixtureHash = fixture.sha256.joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
          }
          val metadata = TorrentMetadata.fromBencode(fixture.metainfo)
          root.resolve("fixture.torrent").writeBytes(
            metainfoFromInfo(metadata.infoBytes, listOf(listOf(tracker.url))))
          val seeding = TimeSource.Monotonic.markNow()
          seeder.start(fixture.metainfo, seed)
          val seedMs = seeding.elapsedNow().inWholeMilliseconds
          val urls = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
            .map { File(it.toURI()).absolutePath }.toList()
          val classpath = (urls + System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct().joinToString(File.pathSeparator)
          for (run in 1..runs) {
            val modes = if (run % 2 == 1) listOf("kotlin", "native") else listOf("native", "kotlin")
            for (mode in modes) {
              val output = root.resolve("download").apply { mkdirs() }
              val log = root.resolve("$mode-$run.log")
              val java = File(System.getProperty("java.home"), "bin/java").absolutePath
              val process = ProcessBuilder(java, "-Xmx256m", "-cp", classpath,
                "com.linroid.ketch.torrent.TorrentBenchmarkProcess", mode,
                root.resolve("fixture.torrent").absolutePath, output.absolutePath,
                seeder.dataHost, size.toString())
                .redirectErrorStream(true).redirectOutput(log).start()
              var idleRss = -1L
              var peakRss = 0L
              var measuredProcess: ProcessHandle? = null
              try {
                withTimeout(630_000) {
                  while (process.isAlive) {
                    if (measuredProcess == null) {
                      val measuredPid = log.readLines().firstOrNull {
                        it.startsWith("BENCHMARK_READY ")
                      }?.substringAfter(' ')?.toLong()
                      if (measuredPid == null) { delay(100); continue }
                      measuredProcess = if (measuredPid == process.pid()) process.toHandle() else
                        process.toHandle().descendants().use { children ->
                          children.filter { it.pid() == measuredPid }.findFirst().orElseThrow()
                        }
                    }
                    val observed = checkNotNull(measuredProcess)
                    if (!observed.isAlive) { delay(100); continue }
                    val ps = ProcessBuilder("ps", "-o", "rss=", "-p", observed.pid().toString())
                      .start()
                    if (!ps.waitFor(2, TimeUnit.SECONDS)) {
                      ps.destroyForcibly()
                      error("RSS sampler timed out")
                    }
                    val rss = ps.inputStream.bufferedReader().readText().trim().toLongOrNull()
                    if (rss != null) {
                      peakRss = maxOf(peakRss, rss)
                      if (idleRss < 0) {
                        idleRss = rss
                        process.outputStream.write(10)
                        process.outputStream.flush()
                      }
                    }
                    delay(100)
                  }
                }
                assertEquals(0, process.exitValue(), log.readText())
                check(idleRss >= 0) { "Missing initialized idle RSS sample" }
                val metrics = log.readLines().single { it.startsWith("BENCHMARK_METRICS ") }
                  .removePrefix("BENCHMARK_METRICS ")
                val measurement = Json.decodeFromString<BenchmarkMetrics>(metrics)
                val verification = TimeSource.Monotonic.markNow()
                assertEquals(size, output.resolve("payload").length())
                assertContentEquals(fixture.sha256,
                  TorrentBenchmarkFixture.digest(output.resolve("payload")))
                results += BenchmarkRun(size, run, mode, fixtureHash, generationMs, seedMs,
                  verification.elapsedNow().inWholeMilliseconds, idleRss, peakRss, measurement)
                save(false)
                println("BENCHMARK ${Json.encodeToString(results.last())}")
              } catch (failure: Throwable) {
                failureDetails = "$mode run $run bytes $size: $failure\n" +
                  log.readText().takeLast(16_000)
                save(false)
                throw failure
              } finally {
                if (process.isAlive) {
                  process.toHandle().descendants().forEach { child ->
                    child.destroyForcibly()
                    child.onExit().get(5, TimeUnit.SECONDS)
                  }
                  process.destroyForcibly()
                  check(process.waitFor(5, TimeUnit.SECONDS)) { "Downloader did not terminate" }
                }
                check(output.deleteRecursively()) { "Cannot remove completed benchmark payload" }
              }
            }
          }
        } catch (failure: Throwable) {
          if (failureDetails == null) failureDetails = "Fixture bytes $size: $failure"
          save(false)
          throw failure
        } finally {
          seeder.close()
          tracker.close()
          root.deleteRecursively()
        }
      }
      save(true)
    }
  }
}

@Serializable
internal data class BenchmarkReport(
  val revision: String, val os: String, val osVersion: String, val arch: String,
  val java: String, val processors: Int, val referenceEngine: String, val transmission: String,
  val sizes: List<Long>, val runs: Int, val complete: Boolean = false,
  val results: List<BenchmarkRun> = emptyList(), val failure: String? = null,
)

@Serializable
internal data class BenchmarkRun(
  val bytes: Long, val run: Int, val mode: String, val fixtureSha256: String,
  val fixtureMs: Long, val seedReadyMs: Long,
  val verifyMs: Long, val idleRssKiB: Long, val peakRssKiB: Long, val metrics: BenchmarkMetrics,
)

@Serializable
internal data class BenchmarkMetrics(
  val initializationMs: Long, val transferMs: Long, val firstVerifiedMs: Long,
  val firstVerifiedBytes: Long, val cpuMs: Long, val idleHeapBytes: Long,
  val peakHeapBytes: Long, val idleFds: Long, val peakFds: Long, val finalFds: Long,
)

internal object TorrentBenchmarkProcess {
  @JvmStatic
  fun main(args: Array<String>): Unit = runBlocking {
    withTimeout(600_000) {
      val initialization = TimeSource.Monotonic.markNow()
      val data = File(args[1]).readBytes()
      val output = File(args[2])
      val payloadBytes = args[4].toLong()

      val os = ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean
      val memory = ManagementFactory.getMemoryMXBean()
      val native = if (args[0] == "native") {
        TorrentBenchmarkSeeder(output.resolve("control").apply { mkdirs() }, args[3]).also {
          it.start(null, output)
        }
      } else null
      val kotlin = if (native == null) KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
        connectionsPerTorrent = 1, maxBufferedBytes = 64 * 1024 * 1024,
        maxExchangeBytes = 256 * 1024 * 1024, maxSessionStateBytes = 64 * 1024 * 1024),
        allowLocalDiscovery = true) else null
      try {
        kotlin?.start()
        val initializationMs = initialization.elapsedNow().inWholeMilliseconds
        val idleHeap = if (native == null) memory.heapMemoryUsage.used else -1
        val idleFds = if (native == null) os?.openFileDescriptorCount ?: -1 else -1
        println("BENCHMARK_READY ${native?.pid ?: ProcessHandle.current().pid()}")
        System.out.flush()
        check(System.`in`.read() == 10) { "Missing measurement start signal" }
        var peakHeap = idleHeap
        var peakFds = idleFds
        val monitor = launch(Dispatchers.Default) {
          while (true) {
            if (native == null) {
              peakHeap = maxOf(peakHeap, memory.heapMemoryUsage.used)
              peakFds = maxOf(peakFds, os?.openFileDescriptorCount ?: -1)
            }
            delay(20)
          }
        }
        val cpuStart = native?.cpuNanos ?: os?.processCpuTime ?: -1
        val transfer = TimeSource.Monotonic.markNow()
        var firstVerifiedMs = -1L
        var firstVerifiedBytes = 0L
        fun verified(bytes: Long) {
          if (bytes > 0 && firstVerifiedMs < 0) {
            firstVerifiedMs = transfer.elapsedNow().inWholeMilliseconds
            firstVerifiedBytes = bytes
          }
        }
        try {
          if (native != null) {
            native.addTorrent(data, output)
            while (true) {
              val bytes = native.verifiedBytes()
              verified(bytes)
              if (bytes == payloadBytes) break
              delay(100)
            }
          } else {
            val metadata = TorrentMetadata.fromBencode(data)
            val session = checkNotNull(kotlin).addTask(TorrentTaskSpec("benchmark", metadata,
              output.resolve("payload").absolutePath, emptySet()))
            val progress = launch { session.downloadedBytes.collect { verified(it) } }
            try {
              session.resume()
              val state = session.state.first {
                it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
              }
              verified(session.downloadedBytes.value)
              check(state == TorrentSessionState.FINISHED) { session.failure.value.toString() }
            } finally { progress.cancel(); progress.join() }
          }
        } finally { monitor.cancel(); monitor.join() }
        val elapsed = transfer.elapsedNow().inWholeMilliseconds
        val cpuEnd = native?.cpuNanos ?: os?.processCpuTime ?: -1
        val cpuMs = if (cpuStart < 0 || cpuEnd < 0) -1 else (cpuEnd - cpuStart) / 1_000_000
        native?.close()
        kotlin?.stop()
        val metrics = BenchmarkMetrics(initializationMs, elapsed, firstVerifiedMs,
          firstVerifiedBytes, cpuMs, idleHeap,
          peakHeap, idleFds, peakFds, if (native == null) os?.openFileDescriptorCount ?: -1 else -1)
        println("BENCHMARK_METRICS ${Json.encodeToString(metrics)}")
      } finally {
        native?.close()
        kotlin?.stop()
      }
    }
  }
}
