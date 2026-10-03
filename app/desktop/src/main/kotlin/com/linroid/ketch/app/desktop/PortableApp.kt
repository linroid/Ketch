package com.linroid.ketch.app.desktop

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * A portable copy of the app on Windows: the app's folder, unpacked from the release's portable
 * `.zip` anywhere, such as on a USB drive, with a [DATA_DIR] folder beside the launcher. That
 * folder holds what an installed app keeps in the user's profile (settings, downloads list,
 * logs, updates), so the copy carries them along when it moves.
 *
 * @property dir the app's folder, which holds [launcher].
 * @property launcher the `Ketch.exe` that started the app.
 */
internal class PortableApp(val dir: File, val launcher: File) {
  /** Where the copy keeps its data. */
  val dataDir: File = File(dir, DATA_DIR)

  /**
   * [dataDir] when the app may write to it; `null` otherwise, such as on a read-only drive, where
   * the data stays in the default folder.
   */
  fun writableDataDir(): File? = dataDir.takeIf { Files.isWritable(it.toPath()) }

  /**
   * Whether an update may replace the app's files in [dir]. It unpacks the new files in
   * [dataDir] and swaps them in by renaming, which only works on one drive, so both folders must
   * be writable and on the same drive, which a [dataDir] linked to another drive is not.
   */
  fun canReplaceFiles(): Boolean =
    Files.isWritable(dir.toPath()) && writableDataDir() != null && onOneDrive()

  private fun onOneDrive(): Boolean = try {
    Files.getFileStore(dir.toPath()) == Files.getFileStore(dataDir.toPath())
  } catch (_: IOException) {
    false
  }

  companion object {
    /** The folder beside the launcher that marks a portable copy and holds its data. */
    const val DATA_DIR = "data"

    /**
     * The running copy when it is portable: on Windows, a [DATA_DIR] folder lies beside the
     * launcher; `null` otherwise, and for an app run from Gradle or an IDE.
     *
     * @param appPath the launcher jpackage names in `jpackage.app-path`.
     */
    fun detect(
      appPath: String? = System.getProperty("jpackage.app-path"),
      os: DesktopOs = DesktopOs.current,
    ): PortableApp? {
      if (os != DesktopOs.WINDOWS || appPath == null) return null
      val launcher = File(appPath).absoluteFile
      val dir = launcher.parentFile ?: return null
      return PortableApp(dir, launcher).takeIf { it.dataDir.isDirectory }
    }
  }
}
