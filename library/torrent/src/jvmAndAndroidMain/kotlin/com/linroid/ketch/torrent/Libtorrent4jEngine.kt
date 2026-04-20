package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import kotlin.coroutines.resume

/**
 * [TorrentEngine] implementation using libtorrent4j.
 *
 * Wraps [SessionManager] to manage the libtorrent session lifecycle,
 * add/remove torrents, and fetch metadata from magnet links.
 */
internal class Libtorrent4jEngine(
  private val config: TorrentConfig,
) : TorrentEngine {

  private val log = KetchLogger("TorrentEngine")
  private var session: SessionManager? = null
  private val sessions = mutableMapOf<String, Libtorrent4jSession>()

  override val isRunning: Boolean
    get() = session?.isRunning == true

  override suspend fun start() = withContext(Dispatchers.IO) {
    if (isRunning) return@withContext
    log.i { "Starting torrent engine" }

    val settings = SettingsPack()
    settings.setString(
      settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
      DHT_BOOTSTRAP_NODES,
    )
    settings.setBoolean(
      settings_pack.bool_types.enable_dht.swigValue(),
      config.dhtEnabled,
    )
    settings.setInteger(
      settings_pack.int_types.active_downloads.swigValue(),
      config.maxActiveTorrents,
    )
    settings.setInteger(
      settings_pack.int_types.active_seeds.swigValue(),
      if (config.enableUpload) config.maxActiveTorrents else 0,
    )
    if (config.listenPort > 0) {
      settings.setString(
        settings_pack.string_types.listen_interfaces.swigValue(),
        "0.0.0.0:${config.listenPort}",
      )
    }

    val params = SessionParams(settings)
    val mgr = SessionManager(config.enableUpload)
    mgr.start(params)
    session = mgr
    log.i { "Torrent engine started" }
  }

  override suspend fun stop() = withContext(Dispatchers.IO) {
    log.i { "Stopping torrent engine" }
    sessions.clear()
    session?.stop()
    session = null
    log.i { "Torrent engine stopped" }
  }

  override suspend fun fetchMetadata(
    magnetUri: String,
  ): TorrentMetadata? = withContext(Dispatchers.IO) {
    val mgr = requireSession()
    log.d { "Fetching metadata for magnet URI" }

    val torrentInfo = try {
      withTimeout(config.metadataTimeout) {
        suspendCancellableCoroutine<TorrentInfo?> { cont ->
          val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(
              AlertType.METADATA_RECEIVED.swig(),
            )

            override fun alert(alert: Alert<*>) {
              if (alert is MetadataReceivedAlert) {
                mgr.removeListener(this)
                val ti = alert.handle().torrentFile()
                cont.resume(ti)
              }
            }
          }
          mgr.addListener(listener)
          cont.invokeOnCancellation {
            mgr.removeListener(listener)
          }
          val tempDir = File(
            System.getProperty("java.io.tmpdir"),
            "ketch-torrent",
          )
          if (!tempDir.exists()) tempDir.mkdirs()
          mgr.fetchMagnet(
            magnetUri, config.metadataTimeoutSeconds, tempDir,
          )
        }
      }
    } catch (e: Exception) {
      log.w(e) { "Metadata fetch failed or timed out" }
      return@withContext null
    }

    if (torrentInfo == null) return@withContext null

    mapTorrentInfo(torrentInfo)
  }

  override suspend fun addTorrent(
    infoHash: String,
    savePath: String,
    magnetUri: String?,
    torrentData: ByteArray?,
    selectedFileIndices: Set<Int>,
    resumeData: ByteArray?,
  ): TorrentSession = withContext(Dispatchers.IO) {
    val mgr = requireSession()
    log.i {
      "Adding torrent: infoHash=$infoHash, savePath=$savePath"
    }

    val saveDir = File(savePath)
    if (!saveDir.exists()) saveDir.mkdirs()

    val handle = when {
      torrentData != null -> {
        val ti = TorrentInfo(torrentData)
        mgr.download(ti, saveDir)
        mgr.find(ti.infoHash())
          ?: throw IllegalStateException(
            "Torrent handle not found after add"
          )
      }
      magnetUri != null -> {
        val tempDir = File(
          System.getProperty("java.io.tmpdir"),
          "ketch-torrent",
        )
        if (!tempDir.exists()) tempDir.mkdirs()
        mgr.fetchMagnet(
          magnetUri, config.metadataTimeoutSeconds, tempDir,
        )
        // Wait for the handle to become available
        val sha1 =
          org.libtorrent4j.Sha1Hash.parseHex(infoHash)
        var found = mgr.find(sha1)
        var attempts = 0
        while (found == null && attempts < 50) {
          Thread.sleep(100)
          found = mgr.find(sha1)
          attempts++
        }
        found ?: throw IllegalStateException(
          "Could not find torrent handle for $infoHash"
        )
      }
      else -> throw IllegalArgumentException(
        "Either magnetUri or torrentData must be provided"
      )
    }

    // Apply file selection
    if (selectedFileIndices.isNotEmpty() &&
      handle.torrentFile() != null
    ) {
      val numFiles = handle.torrentFile().numFiles()
      val priorities = Array(numFiles) { idx ->
        if (idx in selectedFileIndices) {
          Priority.DEFAULT
        } else {
          Priority.IGNORE
        }
      }
      handle.prioritizeFiles(priorities)
    }

    // Resume if we have previous session data
    if (resumeData != null) {
      handle.resume()
    }

    val totalBytes = if (handle.torrentFile() != null) {
      if (selectedFileIndices.isEmpty()) {
        handle.torrentFile().totalSize()
      } else {
        selectedFileIndices.sumOf { idx ->
          if (idx < handle.torrentFile().numFiles()) {
            handle.torrentFile().files().fileSize(idx)
          } else {
            0L
          }
        }
      }
    } else {
      0L
    }

    val torrentSession = Libtorrent4jSession(
      handle, infoHash, totalBytes,
    )
    sessions[infoHash] = torrentSession
    torrentSession
  }

  override suspend fun removeTorrent(
    infoHash: String,
    deleteFiles: Boolean,
  ) = withContext(Dispatchers.IO) {
    val mgr = requireSession()
    val sha1 = org.libtorrent4j.Sha1Hash.parseHex(infoHash)
    val handle = mgr.find(sha1)
    if (handle != null) {
      if (deleteFiles) {
        mgr.remove(handle, SessionHandle.DELETE_FILES)
      } else {
        mgr.remove(handle)
      }
    }
    sessions.remove(infoHash)
    log.i { "Removed torrent: infoHash=$infoHash" }
  }

  override fun setDownloadRateLimit(bytesPerSecond: Long) {
    val mgr = session ?: return
    mgr.downloadRateLimit(bytesPerSecond.toInt())
    log.d {
      "Global download rate limit set to $bytesPerSecond B/s"
    }
  }

  override fun setUploadRateLimit(bytesPerSecond: Long) {
    val mgr = session ?: return
    mgr.uploadRateLimit(bytesPerSecond.toInt())
    log.d {
      "Global upload rate limit set to $bytesPerSecond B/s"
    }
  }

  private fun requireSession(): SessionManager {
    return session ?: throw IllegalStateException(
      "Torrent engine not started"
    )
  }

  private fun mapTorrentInfo(ti: TorrentInfo): TorrentMetadata {
    val fileStorage = ti.files()
    val files = (0 until fileStorage.numFiles()).map { i ->
      TorrentMetadata.TorrentFile(
        index = i,
        path = fileStorage.filePath(i),
        size = fileStorage.fileSize(i),
      )
    }

    return TorrentMetadata(
      infoHash = InfoHash.fromHex(ti.infoHash().toHex()),
      name = ti.name(),
      pieceLength = ti.pieceLength().toLong(),
      totalBytes = ti.totalSize(),
      files = files,
    )
  }

  companion object {
    private const val DHT_BOOTSTRAP_NODES =
      "router.bittorrent.com:6881," +
        "router.utorrent.com:6881," +
        "dht.transmissionbt.com:6881," +
        "dht.aelitis.com:6881"
  }
}
