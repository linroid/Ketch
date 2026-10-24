package com.linroid.ketch.config

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal actual fun writeOwnerOnly(path: String, content: String) {
  val file = File(path)
  val temp = File(file.parentFile, "${file.name}.tmp").toPath()
  Files.deleteIfExists(temp)
  if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
    // Created with its permissions, so no one else can open it before they are set.
    Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
  } else {
    // On Windows the profile folder holding the config directory is already private.
    Files.createFile(temp)
  }
  Files.write(temp, content.toByteArray())
  Files.move(
    temp, file.toPath(),
    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
  )
}

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
