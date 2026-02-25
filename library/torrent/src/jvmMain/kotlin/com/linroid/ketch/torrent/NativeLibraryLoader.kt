package com.linroid.ketch.torrent

import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * Extracts the libtorrent4j native library from the classpath JAR
 * to a temp directory and sets the `libtorrent4j.jni.path` system
 * property so the SWIG static initializer uses `System.load()`.
 *
 * This is only needed on Desktop JVM — Android extracts native
 * libraries from the APK automatically.
 */
internal object NativeLibraryLoader {

  @Volatile
  private var loaded = false

  /**
   * Ensures the native library is extracted and the system property
   * is set. Safe to call multiple times — subsequent calls are no-ops.
   */
  @Synchronized
  fun ensureLoaded() {
    if (loaded) return
    // Skip if already configured externally
    val existing = System.getProperty("libtorrent4j.jni.path", "")
    if (existing.isNotEmpty()) {
      loaded = true
      return
    }
    val resourcePath = resolveResourcePath()
    val libFile = extractToTempDir(resourcePath)
    System.setProperty("libtorrent4j.jni.path", libFile.absolutePath)
    loaded = true
  }

  private fun resolveResourcePath(): String {
    val os = System.getProperty("os.name", "")
      .lowercase()
    val arch = System.getProperty("os.arch", "")
      .lowercase()

    val (libDir, libName) = when {
      "mac" in os || "darwin" in os -> {
        val dir = when (arch) {
          "aarch64", "arm64" -> "arm64"
          else -> "x86_64"
        }
        dir to "libtorrent4j.dylib"
      }
      "linux" in os -> {
        val dir = when (arch) {
          "aarch64", "arm64" -> "arm"
          else -> "x86_64"
        }
        dir to "libtorrent4j.so"
      }
      "windows" in os || "win" in os -> {
        "x86_64" to "libtorrent4j.dll"
      }
      else -> throw UnsupportedOperationException(
        "Unsupported OS for libtorrent4j: $os"
      )
    }

    return "lib/$libDir/$libName"
  }

  private fun extractToTempDir(resourcePath: String): File {
    val stream: InputStream = Thread.currentThread()
      .contextClassLoader
      .getResourceAsStream(resourcePath)
      ?: NativeLibraryLoader::class.java.classLoader
        ?.getResourceAsStream(resourcePath)
      ?: throw UnsatisfiedLinkError(
        "Native library not found on classpath: $resourcePath"
      )

    val tempDir = Files.createTempDirectory("ketch-torrent-native")
      .toFile()
    tempDir.deleteOnExit()

    val fileName = resourcePath.substringAfterLast('/')
    val tempFile = File(tempDir, fileName)
    tempFile.deleteOnExit()

    stream.use { input ->
      tempFile.outputStream().use { output ->
        input.copyTo(output)
      }
    }

    if (!tempFile.canExecute()) {
      tempFile.setExecutable(true)
    }
    return tempFile
  }
}
