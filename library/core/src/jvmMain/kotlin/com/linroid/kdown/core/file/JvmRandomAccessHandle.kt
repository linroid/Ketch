package com.linroid.kdown.core.file

import java.io.RandomAccessFile

internal class JvmRandomAccessHandle(
  private val raf: RandomAccessFile,
) : RandomAccessHandle {

  override fun writeAt(offset: Long, data: ByteArray) {
    raf.seek(offset)
    raf.write(data)
  }

  override fun flush() {
    raf.fd.sync()
  }

  override fun close() {
    raf.close()
  }

  override fun preallocate(size: Long) {
    raf.setLength(size)
  }
}
