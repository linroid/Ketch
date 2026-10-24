package com.linroid.ketch.config

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun writeOwnerOnly(path: String, content: String) {
  val file = path.toPath()
  val temp = "$path.tmp".toPath()
  platformFileSystem.delete(temp, mustExist = false)
  // Created with its permissions, so no one else can open it before they are set.
  val descriptor = open(temp.toString(), O_CREAT or O_EXCL or O_WRONLY, OWNER_ONLY)
  if (descriptor < 0) throw IOException("Couldn't create $temp (errno=$errno)")
  close(descriptor)
  platformFileSystem.write(temp) { writeUtf8(content) }
  platformFileSystem.atomicMove(temp, file)
  // A restored backup on another device must not take this one's identity.
  val excluded = NSURL.fileURLWithPath(path).setResourceValue(
    NSNumber(bool = true),
    forKey = NSURLIsExcludedFromBackupKey,
    error = null,
  )
  if (!excluded) KetchLogger("DeviceStore").w { "Couldn't leave $path out of backups" }
}

private const val OWNER_ONLY = 384 // 0600
