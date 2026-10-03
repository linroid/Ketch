package com.linroid.ketch.app.state

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * Keeps the Discover history in the JSON file at [path], as [DiscoverHistoryCodec] writes it.
 *
 * The file is read once, on the first [load]; after that the store answers from memory, so every
 * controller of the process sees the latest [save] even before it is written. A controller
 * loads the history as it is built, on the main thread, so hosts call [load] at startup on a
 * thread of their own. A single coroutine
 * on [dispatcher] writes the newest list, skipping lists a newer save replaced, to `<path>.tmp`
 * and then moves it over [path]. A `.tmp` left by an interrupted write is used when [path] is
 * missing and deleted otherwise; a file that cannot be read is moved to `<path>.bak`, replacing
 * an older one, and the history starts empty.
 */
class FileDiscoverHistoryStore(
  private val fileSystem: FileSystem,
  private val path: Path,
  dispatcher: CoroutineDispatcher,
) : DiscoverHistoryStore {
  private val log = KetchLogger("DiscoverHistory")
  private val tmp = "$path.tmp".toPath()
  private val lock = Any()
  private var sessions: List<DiscoverSession>? = null
  private var saves = 0L
  private val written = MutableStateFlow(0L)
  private val writes = Channel<Pair<Long, List<DiscoverSession>>>(Channel.CONFLATED)
  private val writer = CoroutineScope(dispatcher).launch {
    try {
      for ((count, list) in writes) {
        write(list)
        written.value = count
      }
    } finally {
      // Nothing is written after this, so nothing waits for it.
      written.value = Long.MAX_VALUE
    }
  }

  override fun load(): List<DiscoverSession> = synchronized(lock) {
    sessions ?: read().also { sessions = it }
  }

  override fun save(sessions: List<DiscoverSession>) {
    synchronized(lock) {
      this.sessions = sessions
      writes.trySend(++saves to sessions)
    }
  }

  override suspend fun flush() {
    val count = synchronized(lock) { saves }
    written.first { it >= count }
  }

  override suspend fun close() {
    writes.close()
    writer.join()
  }

  private fun read(): List<DiscoverSession> {
    try {
      if (!fileSystem.exists(path) && fileSystem.exists(tmp)) {
        // A write stopped before it replaced the file.
        fileSystem.atomicMove(tmp, path)
      } else if (fileSystem.exists(tmp)) {
        fileSystem.delete(tmp)
      }
      if (!fileSystem.exists(path)) return emptyList()
      val text = fileSystem.read(path) { readUtf8() }
      DiscoverHistoryCodec.decode(text)?.let { return it }
      fileSystem.atomicMove(path, "$path.bak".toPath())
      log.w { "Moved the unreadable Discover history aside; it starts empty" }
    } catch (e: IOException) {
      log.w { "Couldn't read the Discover history: ${e.describeCauses()}" }
    }
    return emptyList()
  }

  private fun write(sessions: List<DiscoverSession>) {
    // Any failure, not only I/O: the writer must outlive it, or every later save is lost.
    try {
      path.parent?.let(fileSystem::createDirectories)
      fileSystem.write(tmp) { writeUtf8(DiscoverHistoryCodec.encode(sessions)) }
      fileSystem.atomicMove(tmp, path)
    } catch (e: Exception) {
      log.w { "Couldn't save the Discover history: ${e.describeCauses()}" }
    }
  }
}
