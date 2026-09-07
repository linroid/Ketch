package com.linroid.ketch.torrent

import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Opt-in comparison with isolated downloader processes and a shared reference seed. */
class TorrentBenchmarkTest {
  @Test
  fun compareKotlinAndNativeDownloaders() = runTest {
    if (System.getenv("KETCH_TORRENT_BENCHMARK") != "1") return@runTest
    withContext(Dispatchers.IO) {
      NativeLibraryLoader.ensureLoaded()
      val root = Files.createTempDirectory("ketch-benchmark").toFile()
      val payload = ByteArray(8 * 1024 * 1024 + 37) { (it * 31 + 17).toByte() }
      val seed = root.resolve("seed").apply { mkdirs() }
      seed.resolve("payload").writeBytes(payload)
      val hashes = payload.asList().chunked(256 * 1024).fold(ByteArray(0)) { value, chunk ->
        value + sha1Digest(chunk.toByteArray())
      }
      val data = Bencode.encode(mapOf("info" to mapOf("name" to "payload",
        "length" to payload.size.toLong(), "piece length" to 256 * 1024L, "pieces" to hashes)))
      root.resolve("fixture.torrent").writeBytes(data)
      val manager = SessionManager()
      try {
        manager.start(SessionParams(referenceSettings()))
        val info = TorrentInfo(data)
        manager.download(info, seed)
        withTimeout(20_000) {
          while (manager.find(info.infoHash())?.status()?.isSeeding() != true) delay(20)
        }
        val urls = generateSequence(javaClass.classLoader) { it.parent }
          .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
          .map { File(it.toURI()).absolutePath }.toList()
        val classpath = (urls + System.getProperty("java.class.path").split(File.pathSeparator))
          .distinct().joinToString(File.pathSeparator)
        for (mode in listOf("kotlin", "native")) {
          val output = root.resolve(mode).apply { mkdirs() }
          val log = root.resolve("$mode.log")
          val java = File(System.getProperty("java.home"), "bin/java").absolutePath
          val process = ProcessBuilder(java,
            "-Xmx256m", "-cp", classpath, "com.linroid.ketch.torrent.TorrentBenchmarkProcess",
            mode, root.resolve("fixture.torrent").absolutePath, output.absolutePath,
            manager.swig().listen_port().toString())
            .redirectErrorStream(true).redirectOutput(log).start()
          var peakRssKiB = 0L
          while (process.isAlive) {
            val ps = ProcessBuilder("ps", "-o", "rss=", "-p", process.pid().toString()).start()
            val rss = ps.inputStream.bufferedReader().readText().trim().toLongOrNull() ?: 0
            ps.waitFor(2, TimeUnit.SECONDS)
            peakRssKiB = maxOf(peakRssKiB, rss)
            delay(50)
          }
          assertEquals(0, process.exitValue(), log.readText())
          assertTrue(output.resolve("payload").readBytes().contentEquals(payload))
          println("BENCHMARK $mode rss_peak_kib=$peakRssKiB ${log.readText().trim()}")
        }
      } finally { manager.stop(); root.deleteRecursively() }
    }
  }
}

internal fun referenceSettings(): SettingsPack = SettingsPack().apply {
  setString(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0")
  for (flag in listOf(settings_pack.bool_types.enable_dht, settings_pack.bool_types.enable_lsd,
    settings_pack.bool_types.enable_upnp, settings_pack.bool_types.enable_natpmp)) {
    setBoolean(flag.swigValue(), false)
  }
}

internal object TorrentBenchmarkProcess {
  @JvmStatic
  fun main(args: Array<String>): Unit = runBlocking {
    withTimeout(90_000) {
      val data = File(args[1]).readBytes()
      val output = File(args[2])
      val port = args[3].toInt()
      val os = ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean
      val memory = ManagementFactory.getMemoryMXBean()
      var peakHeap = 0L
      var peakFds = 0L
      val monitor = launch(Dispatchers.Default) {
        while (true) {
          peakHeap = maxOf(peakHeap, memory.heapMemoryUsage.used)
          peakFds = maxOf(peakFds, os?.openFileDescriptorCount ?: 0)
          delay(20)
        }
      }
      val start = TimeSource.Monotonic.markNow()
      try {
        if (args[0] == "native") {
          NativeLibraryLoader.ensureLoaded()
          val manager = SessionManager()
          try {
            manager.start(SessionParams(referenceSettings()))
            val info = TorrentInfo(data)
            manager.download(info, output)
            while (manager.find(info.infoHash()) == null) delay(10)
            val handle = manager.find(info.infoHash())!!
            handle.swig().connect_peer(TcpEndpoint("127.0.0.1", port).swig())
            while (!handle.status().isSeeding()) delay(10)
          } finally { manager.stop() }
        } else {
          val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
          try {
            val metadata = TorrentMetadata.fromBencode(data)
            val magnet = MagnetUri(metadata.infoHash,
              explicitPeers = listOf("127.0.0.1:$port")).toUri()
            source.download(sourceContext(magnet, source.resolveMetainfo(data),
              output.resolve("payload").absolutePath))
          } finally { source.close() }
        }
      } finally { monitor.cancel(); monitor.join() }
      println("elapsed_ms=${start.elapsedNow().inWholeMilliseconds} heap_peak_bytes=$peakHeap " +
        "fd_peak=$peakFds fd_after=${os?.openFileDescriptorCount}")
    }
  }
}
