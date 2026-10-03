package com.linroid.ketch.cli

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.updater.GitHubReleases
import com.linroid.ketch.updater.Release
import com.linroid.ketch.updater.ReleaseAsset
import com.linroid.ketch.updater.ReleaseDownloader
import com.linroid.ketch.updater.ReleasePlatform
import com.linroid.ketch.updater.ReleaseProduct
import com.linroid.ketch.updater.ReleaseVersion
import com.linroid.ketch.updater.UpdateException
import com.linroid.ketch.updater.extractArchive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Arguments of `ketch update`. */
internal sealed interface UpdateArgs {
  /** `--help` or `-h` was given. */
  data object Help : UpdateArgs

  /** The arguments cannot be used; [message] says why. */
  data class Invalid(val message: String) : UpdateArgs

  /**
   * @property checkOnly report whether a newer release exists without installing it.
   * @property version the release to install, also an older one; `null` for the latest.
   */
  data class Update(val checkOnly: Boolean = false, val version: ReleaseVersion? = null) :
    UpdateArgs
}

/** Parses the arguments of `ketch update`, which no longer contain the global flags. */
internal fun parseUpdateArgs(args: List<String>): UpdateArgs {
  var checkOnly = false
  var version: ReleaseVersion? = null
  var i = 0
  while (i < args.size) {
    when (val arg = args[i]) {
      "--help", "-h" -> return UpdateArgs.Help
      "--check" -> checkOnly = true
      "--version" -> {
        val value = args.getOrNull(++i) ?: return UpdateArgs.Invalid("--version requires a value")
        version = ReleaseVersion.parse(value)
          ?: return UpdateArgs.Invalid("'$value' is not a version, such as 0.0.1-rc15")
      }
      else -> return UpdateArgs.Invalid("unknown option '$arg'")
    }
    i++
  }
  return UpdateArgs.Update(checkOnly, version)
}

/**
 * Runs `ketch update`: looks up the latest release on GitHub, or the one asked for, and replaces
 * this binary with it after checking the download against the SHA-256 digest GitHub published.
 *
 * @param logger logger of the Ketch engine that downloads the release.
 * @return the exit code.
 */
internal fun runUpdate(args: List<String>, logger: Logger): Int {
  val parsed = when (val parsed = parseUpdateArgs(args)) {
    UpdateArgs.Help -> {
      printUpdateUsage()
      return 0
    }
    is UpdateArgs.Invalid -> {
      System.err.println("Error: ${parsed.message}")
      System.err.println("Run `ketch update --help` for usage.")
      return 1
    }
    is UpdateArgs.Update -> parsed
  }
  val current = ReleaseVersion.parse(KetchApi.VERSION)
  val platform = ReleasePlatform.current() ?: return fail(
    "Ketch is not released for ${System.getProperty("os.name")} " +
      "(${System.getProperty("os.arch")})",
  )
  val installation = CliInstallation.current()
  if (!parsed.checkOnly) {
    if (installation == null) {
      return fail(
        "`ketch update` replaces the native ketch binary, and this one runs on a JVM.\n" +
          "Install the native binary with:\n  curl -fsSL $INSTALL_SCRIPT | bash",
      )
    }
    if (!installation.isWritable()) {
      return fail(
        "Can't write to ${installation.executable}. " + if (File.separatorChar == '\\') {
          "Run `ketch update` in a terminal opened as administrator."
        } else {
          "Run `sudo ketch update`."
        },
      )
    }
  }

  return try {
    runBlocking {
      println("Checking for updates...")
      val release = fetchRelease(parsed.version)
      if (parsed.version == null && current != null && release.version <= current) {
        println("Ketch is up to date: $current is the latest release.")
        return@runBlocking 0
      }
      val asset = release.asset(ReleaseProduct.Cli, platform)
      if (parsed.checkOnly) {
        println("Ketch ${release.version} is available; this is ${KetchApi.VERSION}.")
        println("Release notes: ${release.pageUrl}")
        if (asset != null) {
          println("Run `ketch update` to install it.")
        } else {
          println(noBuildMessage(release, platform))
        }
        return@runBlocking 0
      }
      if (asset == null) throw UpdateException(noBuildMessage(release, platform))
      install(release, asset, checkNotNull(installation), logger)
      0
    }
  } catch (e: UpdateException) {
    fail(e.message.orEmpty())
  } catch (e: IOException) {
    fail("Couldn't install the update: ${e.message}")
  }
}

private suspend fun fetchRelease(version: ReleaseVersion?): Release {
  val feed = GitHubReleases({ KtorHttpEngine() })
  return if (version != null) feed.release(version) else feed.latest()
}

/** Says that [release] has no archive for [platform], as while its files are uploaded. */
private fun noBuildMessage(release: Release, platform: ReleasePlatform): String =
  "Ketch ${release.version} has no command-line build for ${platform.os.id}-${platform.arch.id} " +
    "yet; its files may still be uploading, so try again later."

private suspend fun install(
  release: Release,
  asset: ReleaseAsset,
  installation: CliInstallation,
  logger: Logger,
) {
  val work = Files.createTempDirectory("ketch-update").toFile()
  try {
    val archive = File(work, asset.name)
    println("Downloading ${asset.name} (${formatBytes(asset.size)})")
    ReleaseDownloader({ KtorHttpEngine() }, logger).download(asset, archive) { progress ->
      val pct = (progress.percent * 100).toInt()
      print("\r  $pct%  ${formatBytes(progress.downloadedBytes)} / ${formatBytes(asset.size)}    ")
    }
    println()
    println("Verified the SHA-256 checksum")
    val extracted = File(work, "extracted")
    extractArchive(archive, extracted)
    installation.install(extracted)
    println("Updated ${installation.executable} to ${release.version}")
    println("Release notes: ${release.pageUrl}")
  } finally {
    work.deleteRecursively()
  }
}

private fun fail(message: String): Int {
  System.err.println("Error: $message")
  return 1
}

internal fun printUpdateUsage() {
  println("Usage: ketch update [options]")
  println()
  println("Replace this binary with the latest release from GitHub,")
  println("after checking it against the SHA-256 checksum GitHub")
  println("published for it.")
  println()
  println("Options:")
  println("  --check              Only report whether a newer release")
  println("                       exists")
  println("  --version <version>  Install this release instead, also")
  println("                       an older one (e.g. 0.0.1-rc15)")
  println("  --help, -h           Show this help message")
  println()
  println("Examples:")
  println("  ketch update")
  println("  ketch update --check")
  println("  sudo ketch update    (when installed in /usr/local/bin)")
}

private const val INSTALL_SCRIPT = "https://raw.githubusercontent.com/linroid/Ketch/main/install.sh"
