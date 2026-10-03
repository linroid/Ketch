package com.linroid.ketch.cli

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The native `ketch` binary this process runs from, which `ketch update` replaces, and the
 * license notices beside it.
 *
 * @property executable the binary, with symbolic links resolved.
 * @property windows whether it is a Windows program, which cannot be replaced while it runs.
 */
internal class CliInstallation(
  val executable: File,
  private val windows: Boolean = File.separatorChar == '\\',
) {
  private val directory: File = executable.absoluteFile.parentFile

  /** The binary this one renames itself to on Windows, which the next run removes. */
  private val replaced: File get() = File(directory, "${executable.name}.old")

  /** Whether this process may replace the binary. */
  fun isWritable(): Boolean = Files.isWritable(directory.toPath()) && executable.canWrite()

  /**
   * Installs the binary and the license notices from [extracted], an extracted release archive.
   *
   * On macOS and Linux the new binary is renamed over the old one; the running process keeps
   * the old file open, so it goes on working. Windows cannot replace a running program, so the
   * old one is renamed out of the way and the next run removes it ([removeReplaced]).
   *
   * @throws IOException when the archive lacks the binary or a file cannot be written; the
   *   binary is then left as it was.
   */
  fun install(extracted: File) {
    val binary = File(extracted, executableName())
    if (!binary.isFile) throw IOException("The release archive has no ${binary.name}")
    val staged = File(directory, ".${executable.name}.new")
    try {
      Files.copy(binary.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
      copyPermissions(staged)
      if (windows) swapRunning(staged) else move(staged, executable, atomic = true)
    } finally {
      staged.delete()
    }
    installLicenses(File(extracted, LICENSES))
  }

  /** Deletes the binary an update on Windows set aside, once it no longer runs. */
  fun removeReplaced() {
    replaced.delete()
  }

  private fun executableName(): String = if (windows) "ketch.exe" else "ketch"

  private fun copyPermissions(staged: File) {
    if (windows) return
    val permissions = Files.getPosixFilePermissions(executable.toPath())
    Files.setPosixFilePermissions(staged.toPath(), permissions)
  }

  /** Renames the running binary away, then moves [staged] into its place. */
  private fun swapRunning(staged: File) {
    replaced.delete()
    move(executable, replaced, atomic = false)
    try {
      move(staged, executable, atomic = false)
    } catch (e: IOException) {
      move(replaced, executable, atomic = false)
      throw e
    }
  }

  /**
   * Copies the notices into the folder the installer made, `ketch-licenses`, or the `licenses`
   * folder of an unpacked archive, keeping files no release ships any more.
   */
  private fun installLicenses(licenses: File) {
    if (!licenses.isDirectory) return
    val target = listOf(File(directory, INSTALLED_LICENSES), File(directory, LICENSES))
      .firstOrNull { it.isDirectory } ?: File(directory, INSTALLED_LICENSES)
    licenses.copyRecursively(target, overwrite = true)
  }

  private fun move(from: File, to: File, atomic: Boolean) {
    val options = if (atomic) {
      arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } else {
      arrayOf(StandardCopyOption.REPLACE_EXISTING)
    }
    Files.move(from.toPath(), to.toPath(), *options)
  }

  companion object {
    /** Folder install.sh puts the license notices in, beside the binary. */
    private const val INSTALLED_LICENSES = "ketch-licenses"
    private const val LICENSES = "licenses"

    /**
     * The native binary this process runs from; `null` on a JVM, as with `./gradlew :cli:run`,
     * which has no binary to replace.
     */
    fun current(): CliInstallation? {
      if (System.getProperty("org.graalvm.nativeimage.imagecode") != "runtime") return null
      val command = ProcessHandle.current().info().command().orElse(null) ?: return null
      return CliInstallation(File(command).canonicalFile)
    }
  }
}
