package com.linroid.ketch.updater

/** The GitHub repository Ketch is released from. */
const val KETCH_REPOSITORY: String = "linroid/Ketch"

/**
 * A published release of Ketch.
 *
 * @property version the version its tag names.
 * @property pageUrl web page of the release, with its notes.
 * @property assets the files it ships.
 */
data class Release(
  val version: ReleaseVersion,
  val pageUrl: String,
  val assets: List<ReleaseAsset>,
) {
  /** The file of [product] for [platform], or `null` when this release has none. */
  fun asset(product: ReleaseProduct, platform: ReleasePlatform): ReleaseAsset? =
    product.fileNames(version, platform).firstNotNullOfOrNull { name ->
      assets.firstOrNull { it.name == name }
    }
}

/**
 * A file of a [Release].
 *
 * @property url where it downloads from.
 * @property size its size in bytes.
 * @property sha256 its SHA-256 digest in lowercase hex, which GitHub computes on upload; `null`
 *   for files uploaded before GitHub did.
 */
data class ReleaseAsset(
  val name: String,
  val url: String,
  val size: Long,
  val sha256: String?,
)

/** What a release ships, by the names the release workflow gives its files. */
enum class ReleaseProduct {
  /** The native `ketch` command: `ketch-cli-<version>-<os>-<arch>.tar.gz`, `.zip` on Windows. */
  Cli,

  /** The installed desktop app: `ketch-desktop-<version>-<os>-<arch>.dmg`, `.msi` or `.deb`. */
  Desktop,

  /**
   * The portable desktop app, Windows only: `ketch-desktop-<version>-windows-<arch>-portable.zip`,
   * the app's folder to unpack and run anywhere.
   */
  PortableDesktop,
  ;

  /** Names of this product's file for [platform] in [version], the preferred one first. */
  internal fun fileNames(version: ReleaseVersion, platform: ReleasePlatform): List<String> {
    val os = platform.os.id
    return when (this) {
      Cli -> {
        val extension = if (platform.os == ReleaseOs.Windows) "zip" else "tar.gz"
        // Releases up to v0.0.1-rc12 named ARM64 archives "aarch64".
        val arches = listOf(platform.arch.id) +
          listOf(LEGACY_ARM64).filter { platform.arch == ReleaseArch.Arm64 }
        arches.map { arch -> "ketch-cli-$version-$os-$arch.$extension" }
      }
      Desktop -> {
        val extension = when (platform.os) {
          ReleaseOs.MacOs -> "dmg"
          ReleaseOs.Windows -> "msi"
          ReleaseOs.Linux -> "deb"
        }
        listOf("ketch-desktop-$version-$os-${platform.arch.id}.$extension")
      }
      PortableDesktop -> if (platform.os == ReleaseOs.Windows) {
        listOf("ketch-desktop-$version-$os-${platform.arch.id}-portable.zip")
      } else {
        emptyList()
      }
    }
  }

  private companion object {
    const val LEGACY_ARM64 = "aarch64"
  }
}

/** Operating systems Ketch is released for. */
enum class ReleaseOs(val id: String) {
  MacOs("macos"),
  Windows("windows"),
  Linux("linux"),
}

/** Processor architectures Ketch is released for. */
enum class ReleaseArch(val id: String) {
  X64("x64"),
  Arm64("arm64"),
}

/** An operating system and architecture, which pick a release's file. */
data class ReleasePlatform(val os: ReleaseOs, val arch: ReleaseArch) {
  companion object {
    /** The platform this JVM or native image runs on; `null` when Ketch is not released for it. */
    fun current(): ReleasePlatform? =
      of(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())

    /** The platform of the `os.name` and `os.arch` system properties [osName] and [osArch]. */
    fun of(osName: String, osArch: String): ReleasePlatform? {
      val name = osName.lowercase()
      val os = when {
        name.startsWith("mac") -> ReleaseOs.MacOs
        name.startsWith("windows") -> ReleaseOs.Windows
        name.startsWith("linux") -> ReleaseOs.Linux
        else -> return null
      }
      val arch = when (osArch.lowercase()) {
        "amd64", "x86_64" -> ReleaseArch.X64
        "aarch64", "arm64" -> ReleaseArch.Arm64
        else -> return null
      }
      return ReleasePlatform(os, arch)
    }
  }
}

/** A release could not be found, downloaded or checked; [message] says what went wrong. */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
