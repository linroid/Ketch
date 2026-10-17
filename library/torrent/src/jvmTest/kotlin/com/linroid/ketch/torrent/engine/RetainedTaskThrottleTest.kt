package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.MultiFileTorrent
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.InfoHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A session that outlives the run that opened it, such as a seeder, keeps nothing of that run
 * once its task limits are replaced: the run's throttle reaches its download's execution.
 */
class RetainedTaskThrottleTest {
  @Test
  fun v1Session_withNewTaskLimits_keepsNoneOfTheOpeningRun() = withEngine { engine, root ->
    val torrent = MultiFileTorrent()
    val (session, opening) = open { throttle ->
      engine.addTask(TorrentTaskSpec("task", torrent.metadata, (root / "out").toString(),
        setOf(0, 1, 2), throttle = throttle, privacy = TorrentDiscoveryPrivacy.PUBLIC))
    }
    session.setTaskThrottle {}
    assertTrue(collected(opening), "the session keeps the opening run's throttle")
  }

  @Test
  fun v2Owner_withNewTaskLimits_keepsNoneOfTheOpeningRun() = withEngine { engine, root ->
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 20_000))
    val (session, opening) = open { throttle ->
      engine.addV2Task(TorrentV2TaskSpec("task", fixture.document, (root / "out").toString(),
        trackerTiers = listOf(listOf("http://tracker.invalid/announce")),
        privacy = TorrentDiscoveryPrivacy.PUBLIC, throttle = throttle))
    }
    session.setTaskThrottle {}
    assertTrue(collected(opening), "the owner keeps the opening run's throttle")
  }

  private fun withEngine(
    block: suspend (KotlinTorrentEngine, Path) -> Unit,
  ) = runBlocking(Dispatchers.Default) {
    withTimeout(30.seconds) {
      val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "ketch-retained-throttle-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
      FileSystem.SYSTEM.createDirectories(root)
      val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
        listenHost = "127.0.0.1")
      try {
        engine.start()
        block(engine, root)
      } finally {
        engine.stop()
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
      }
    }
  }

  /**
   * Opens a session with a throttle of its own, as a run's, and returns it with a weak reference
   * to that throttle; nothing here keeps it once this returns.
   */
  private suspend fun open(
    add: suspend (suspend (Int) -> Unit) -> TorrentSession,
  ): Pair<TorrentSession, WeakReference<Any>> {
    val run = Any()
    val throttle: suspend (Int) -> Unit = { run.hashCode() }
    return add(throttle) to WeakReference(throttle)
  }

  private fun collected(reference: WeakReference<*>): Boolean {
    repeat(20) {
      System.gc()
      if (reference.get() == null) return true
      Thread.sleep(50)
    }
    return false
  }
}
