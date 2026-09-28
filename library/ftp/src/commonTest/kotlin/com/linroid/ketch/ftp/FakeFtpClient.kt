package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.file.FileAccessor

/**
 * In-memory FTP server shared by the [FakeFtpClient]s it creates, one per
 * control connection. Serves [content] for every path.
 *
 * @param supportsRest whether the server accepts REST offsets
 * @param chunkSize bytes delivered per data callback
 */
internal class FakeFtpServer(
  val content: ByteArray,
  var supportsRest: Boolean = true,
  private val chunkSize: Int = 100,
) {
  /** Offset of every RETR, in call order. */
  val retrieveOffsets = mutableListOf<Long>()

  /** Buffer sizes requested by the source for each client. */
  val bufferSizes = mutableListOf<Int>()

  /** When non-negative, the next transfer closes its data connection after this many bytes. */
  var dropNextTransferAfter: Long = -1

  fun client(bufferSize: Int): FtpClient {
    bufferSizes += bufferSize
    return FakeFtpClient(this)
  }

  suspend fun retrieve(offset: Long, onData: suspend (ByteArray) -> Unit) {
    retrieveOffsets += offset
    if (offset > 0 && !supportsRest) {
      throw KetchError.Unsupported(IllegalStateException("REST not supported"))
    }
    val limit = dropNextTransferAfter.also { dropNextTransferAfter = -1 }
    var position = offset.toInt()
    var sent = 0L
    while (position < content.size) {
      if (limit in 0..sent) return
      val end = minOf(position + chunkSize, content.size)
      onData(content.copyOfRange(position, end))
      sent += end - position
      position = end
    }
  }
}

/** [FtpClient] backed by a [FakeFtpServer]. */
internal class FakeFtpClient(private val server: FakeFtpServer) : FtpClient {
  override var isConnected: Boolean = false
    private set

  override suspend fun connect() {
    isConnected = true
  }

  override suspend fun upgradeToTls() {}

  override suspend fun login(username: String, password: String) {}

  override suspend fun setBinaryMode() {}

  override suspend fun size(path: String): Long = server.content.size.toLong()

  override suspend fun mdtm(path: String): String = "20250101000000"

  override suspend fun supportsRest(): Boolean = server.supportsRest

  override suspend fun retrieve(
    path: String,
    offset: Long,
    onData: suspend (ByteArray) -> Unit,
  ) {
    server.retrieve(offset, onData)
  }

  override suspend fun disconnect() {
    isConnected = false
  }
}

/** [FileAccessor] that keeps the written file in memory. */
internal class MemoryFileAccessor : FileAccessor {
  var bytes = ByteArray(0)
    private set

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    val end = offset.toInt() + data.size
    if (end > bytes.size) bytes = bytes.copyOf(end)
    data.copyInto(bytes, offset.toInt())
  }

  override suspend fun flush() {}

  override fun close() {}

  override suspend fun delete() {
    bytes = ByteArray(0)
  }

  override suspend fun size(): Long = bytes.size.toLong()

  override suspend fun preallocate(size: Long) {
    bytes = bytes.copyOf(size.toInt())
  }
}
