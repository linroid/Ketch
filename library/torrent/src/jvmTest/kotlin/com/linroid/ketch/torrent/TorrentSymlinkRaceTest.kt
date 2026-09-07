package com.linroid.ketch.torrent

import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals

class TorrentSymlinkRaceTest {
  @Test
  fun concurrentParentReplacement_cannotRedirectPayloadWrites() {
    if (System.getProperty("os.name").startsWith("Windows")) return // Needs symlink privilege.
    val root = Files.createTempDirectory("ketch-symlink-race").toRealPath()
    val slot = Files.createDirectory(root.resolve("slot"))
    val parked = root.resolve("parked")
    val outside = Files.createDirectory(root.resolve("outside"))
    val original = byteArrayOf(71, 72, 73, 74)
    Files.write(outside.resolve("payload"), original)
    Files.write(slot.resolve("payload"), ByteArray(4))
    val running = AtomicBoolean(true)
    val attacker = thread {
      while (running.get()) {
        Files.move(slot, parked)
        Files.createSymbolicLink(slot, outside)
        Thread.yield()
        Files.delete(slot)
        Files.move(parked, slot)
      }
    }
    try {
      repeat(250) {
        try {
          torrentFileSystem.openReadWrite(slot.resolve("payload").toString().toPath(),
            mustExist = true).use { handle ->
            handle.write(0, byteArrayOf(1, 2, 3, 4), 0, 4)
          }
        } catch (_: java.io.IOException) {
          // Reject a symlink or a temporarily absent parent.
        }
      }
    } finally {
      running.set(false)
      attacker.join()
    }
    try {
      assertContentEquals(original, Files.readAllBytes(outside.resolve("payload")))
    } finally { root.toFile().deleteRecursively() }
  }
}
