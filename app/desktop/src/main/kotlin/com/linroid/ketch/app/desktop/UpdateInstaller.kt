package com.linroid.ketch.app.desktop

import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Installs a downloaded release of the desktop app over the running one. The packaged app gets
 * one for its system from [forThisApp]; an app run from Gradle or an IDE has none.
 */
internal interface UpdateInstaller {
  /**
   * Whether [install] replaces the app and opens it again once the app quits; otherwise it opens
   * the installer for the user to finish.
   */
  val restarts: Boolean

  /**
   * Readies [download], the release's installer, for [install], such as by unpacking it; returns
   * what [install] takes. Blocks.
   *
   * @throws IOException when [download] cannot be used.
   */
  fun prepare(download: File): File = download

  /**
   * Starts installing [prepared]. An installer that [restarts] waits in a process of its own for
   * this one, [pid], to exit, so the caller quits next. Blocks.
   *
   * @throws IOException when the installation cannot start.
   */
  fun install(prepared: File, pid: Long)

  /**
   * Replaces the `.app` [bundle] with the one in the release's disk image, then opens it. The
   * new bundle is unpacked beside the downloads ahead of time and copied next to [bundle] once
   * the app quits, so the swap is two renames on the same volume; if one fails the old bundle
   * stays and opens again.
   */
  class MacBundle(
    private val bundle: File,
    private val workDir: File,
    private val log: File,
  ) : UpdateInstaller {
    override val restarts: Boolean get() = true

    override fun prepare(download: File): File {
      val mount = File(workDir, "mount").apply { deleteRecursively(); mkdirs() }
      val staged = File(workDir, bundle.name).apply { deleteRecursively() }
      run(
        "/usr/bin/hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen",
        "-mountpoint", mount.path, download.path,
      )
      try {
        val app = mount.listFiles()?.firstOrNull { it.name.endsWith(".app") }
          ?: throw IOException("${download.name} holds no app")
        run("/usr/bin/ditto", app.path, staged.path)
      } finally {
        run("/usr/bin/hdiutil", "detach", "-force", mount.path)
        mount.delete()
      }
      if (!File(staged, "Contents/MacOS").isDirectory) {
        throw IOException("The app in ${download.name} has no launcher")
      }
      download.delete()
      return staged
    }

    override fun install(prepared: File, pid: Long) {
      val script = writeScript(workDir, "install-update-mac.sh")
      launch(listOf("/bin/sh", script.path, pid.toString(), prepared.path, bundle.path), log)
    }
  }

  /**
   * Runs the release's Windows Installer package, which upgrades the installed app in place
   * (Windows asks the user to allow it), then opens [launcher] again. Cancelled or failed, the
   * old app stays and opens again.
   */
  class WindowsMsi(
    private val launcher: File,
    private val workDir: File,
    private val log: File,
  ) : UpdateInstaller {
    override val restarts: Boolean get() = true

    override fun install(prepared: File, pid: Long) {
      val script = writeScript(workDir, "install-update-windows.ps1")
      val command = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden", "-File", script.path,
        "-ProcessId", pid.toString(), "-Installer", prepared.path, "-App", launcher.path,
      )
      launch(command, log)
    }
  }

  /**
   * Installs the release's Debian package with `pkexec dpkg -i`, which asks for the user's
   * password, then opens [launcher] again, updated or not.
   */
  class DebPackage(
    private val launcher: File,
    private val workDir: File,
    private val log: File,
  ) : UpdateInstaller {
    override val restarts: Boolean get() = true

    override fun install(prepared: File, pid: Long) {
      val script = writeScript(workDir, "install-update-linux.sh")
      // A session of its own, so it outlives the app.
      val command = listOf("/usr/bin/setsid", "/bin/sh", script.path) +
        listOf(pid.toString(), prepared.path, launcher.path)
      launch(command, log)
    }
  }

  /**
   * Opens the installer with the system, for the user to finish, where the app cannot replace
   * itself: a macOS bundle in a folder the user cannot write or run from the disk image, or a
   * Linux system without `pkexec`.
   */
  class OpenInstaller(private val open: (File) -> Unit = { Desktop.getDesktop().open(it) }) :
    UpdateInstaller {
    override val restarts: Boolean get() = false

    override fun install(prepared: File, pid: Long) {
      open(prepared)
    }
  }

  companion object {
    /**
     * The installer of the running app, which keeps its files in [workDir] and writes what it
     * does to [log]; `null` when the app is not installed from a release package.
     *
     * @param appPath the launcher jpackage names in `jpackage.app-path`.
     */
    fun forThisApp(
      workDir: File,
      log: File,
      appPath: String? = System.getProperty("jpackage.app-path"),
      os: DesktopOs = DesktopOs.current,
    ): UpdateInstaller? {
      val launcher = appPath?.let(::File) ?: return null
      return when (os) {
        DesktopOs.MAC -> {
          // Ketch.app/Contents/MacOS/Ketch
          val bundle = launcher.parentFile?.parentFile?.parentFile
            ?.takeIf { it.name.endsWith(".app") } ?: return null
          if (canReplace(bundle)) MacBundle(bundle, workDir, log) else OpenInstaller()
        }
        DesktopOs.WINDOWS -> WindowsMsi(launcher, workDir, log)
        DesktopOs.LINUX -> if (File(PKEXEC).canExecute() && File(DPKG).canExecute()) {
          DebPackage(launcher, workDir, log)
        } else {
          OpenInstaller()
        }
      }
    }

    /**
     * Whether the app may swap [bundle] for a new one: its folder is writable, and it does not
     * run from the read-only disk image or from the random place macOS moves apps opened
     * straight from a download to (App Translocation).
     */
    internal fun canReplace(bundle: File): Boolean {
      val path = bundle.absolutePath
      val parent = bundle.absoluteFile.parentFile ?: return false
      return "/AppTranslocation/" !in path && !path.startsWith("/Volumes/") &&
        parent.canWrite() && bundle.canWrite()
    }

    private const val PKEXEC = "/usr/bin/pkexec"
    private const val DPKG = "/usr/bin/dpkg"
  }
}

/** Runs [command] and waits for it; throws when it fails, with its output. */
private fun run(vararg command: String) {
  val process = ProcessBuilder(*command).redirectErrorStream(true).start()
  val output = process.inputStream.bufferedReader().readText()
  if (!process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
    process.destroyForcibly()
    throw IOException("${command.first()} did not finish")
  }
  if (process.exitValue() != 0) {
    throw IOException("${command.first()} failed (${process.exitValue()}): ${output.trim()}")
  }
}

/** Copies the script [name] from the app's `update` resources into [dir]. */
internal fun writeScript(dir: File, name: String): File {
  val text = UpdateInstaller::class.java.getResource("/update/$name")?.readText()
    ?: throw IOException("The app has no $name")
  dir.mkdirs()
  return File(dir, name).apply { writeText(text) }
}

/** Starts [command] on its own, appending what it prints to [log]. */
private fun launch(command: List<String>, log: File) {
  log.parentFile?.mkdirs()
  val nullDevice = File(if (DesktopOs.current == DesktopOs.WINDOWS) "NUL" else "/dev/null")
  ProcessBuilder(command)
    .redirectInput(ProcessBuilder.Redirect.from(nullDevice))
    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
    .redirectErrorStream(true)
    .start()
}

private const val COMMAND_TIMEOUT_MINUTES = 5L
