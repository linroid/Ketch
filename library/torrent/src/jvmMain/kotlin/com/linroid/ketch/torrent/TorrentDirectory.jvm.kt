package com.linroid.ketch.torrent

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import okio.IOException
import okio.Path
import java.nio.file.Paths

/** Linux mkdirat complements NIO SecureDirectoryStream, which has no directory-creation method. */
internal actual fun createSecureTorrentDirectory(path: Path, mustCreate: Boolean) {
  val library = NativeLibrary.getInstance("c")
  fun call(name: String, vararg values: Any?): Int = library.getFunction(name, when (name) {
    "open" -> 2 shl 7
    "openat" -> 3 shl 7
    else -> 0
  }).invokeInt(values)
  fun checked(value: Int): Int {
    if (value < 0) {
      throw IOException("Torrent directory operation failed (errno=${Native.getLastError()})")
    }
    return value
  }
  val absolute = Paths.get(path.toString()).toAbsolutePath().normalize()
  val arm = System.getProperty("os.arch").let { it.startsWith("arm") || it == "aarch64" }
  val flags = if (arm) 16384 or 32768 else 65536 or 131072 // O_DIRECTORY | O_NOFOLLOW.
  var fd = checked(call("open", "/", flags))
  try {
    for (component in absolute.parent) {
      val next = checked(call("openat", fd, component.toString(), flags))
      call("close", fd)
      fd = next
    }
    if (call("mkdirat", fd, absolute.fileName.toString(), 511) != 0) {
      if (mustCreate || Native.getLastError() != 17) checked(-1)
      val existing = checked(call("openat", fd, absolute.fileName.toString(), flags))
      call("close", existing)
    }
  } finally { call("close", fd) }
}
