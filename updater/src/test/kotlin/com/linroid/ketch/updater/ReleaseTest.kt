package com.linroid.ketch.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseTest {
  private val version = ReleaseVersion(0, 0, 2, "rc1")

  @Test
  fun asset_desktop_usesTheInstallerOfEachSystem() {
    val release = release(
      "ketch-desktop-0.0.2-rc1-macos-arm64.dmg",
      "ketch-desktop-0.0.2-rc1-windows-x64.msi",
      "ketch-desktop-0.0.2-rc1-linux-arm64.deb",
    )
    assertEquals(
      "ketch-desktop-0.0.2-rc1-macos-arm64.dmg",
      release.asset(ReleaseProduct.Desktop, platform(ReleaseOs.MacOs, ReleaseArch.Arm64))?.name,
    )
    assertEquals(
      "ketch-desktop-0.0.2-rc1-windows-x64.msi",
      release.asset(ReleaseProduct.Desktop, platform(ReleaseOs.Windows, ReleaseArch.X64))?.name,
    )
    assertEquals(
      "ketch-desktop-0.0.2-rc1-linux-arm64.deb",
      release.asset(ReleaseProduct.Desktop, platform(ReleaseOs.Linux, ReleaseArch.Arm64))?.name,
    )
  }

  @Test
  fun asset_portableDesktop_usesTheZipOnWindowsOnly() {
    val release = release(
      "ketch-desktop-0.0.2-rc1-windows-arm64.msi",
      "ketch-desktop-0.0.2-rc1-windows-arm64-portable.zip",
      "ketch-desktop-0.0.2-rc1-macos-arm64.dmg",
    )
    assertEquals(
      "ketch-desktop-0.0.2-rc1-windows-arm64-portable.zip",
      release.asset(
        ReleaseProduct.PortableDesktop,
        platform(ReleaseOs.Windows, ReleaseArch.Arm64),
      )?.name,
    )
    assertEquals(
      "ketch-desktop-0.0.2-rc1-windows-arm64.msi",
      release.asset(ReleaseProduct.Desktop, platform(ReleaseOs.Windows, ReleaseArch.Arm64))?.name,
    )
    val mac = platform(ReleaseOs.MacOs, ReleaseArch.Arm64)
    assertNull(release.asset(ReleaseProduct.PortableDesktop, mac))
  }

  @Test
  fun asset_cli_usesZipOnWindowsOnly() {
    val release = release(
      "ketch-cli-0.0.2-rc1-windows-x64.zip",
      "ketch-cli-0.0.2-rc1-linux-x64.tar.gz",
    )
    assertEquals(
      "ketch-cli-0.0.2-rc1-windows-x64.zip",
      release.asset(ReleaseProduct.Cli, platform(ReleaseOs.Windows, ReleaseArch.X64))?.name,
    )
    assertEquals(
      "ketch-cli-0.0.2-rc1-linux-x64.tar.gz",
      release.asset(ReleaseProduct.Cli, platform(ReleaseOs.Linux, ReleaseArch.X64))?.name,
    )
  }

  @Test
  fun asset_cliOnArm64_fallsBackToTheLegacyArchName() {
    val release = release("ketch-cli-0.0.2-rc1-macos-aarch64.tar.gz")
    assertEquals(
      "ketch-cli-0.0.2-rc1-macos-aarch64.tar.gz",
      release.asset(ReleaseProduct.Cli, platform(ReleaseOs.MacOs, ReleaseArch.Arm64))?.name,
    )
    assertNull(release.asset(ReleaseProduct.Cli, platform(ReleaseOs.MacOs, ReleaseArch.X64)))
  }

  @Test
  fun asset_missingPlatform_returnsNull() {
    val release = release("ketch-desktop-0.0.2-rc1-windows-x64.msi")
    val windowsArm = platform(ReleaseOs.Windows, ReleaseArch.Arm64)
    assertNull(release.asset(ReleaseProduct.Desktop, windowsArm))
    assertNull(release.asset(ReleaseProduct.Cli, platform(ReleaseOs.Windows, ReleaseArch.X64)))
  }

  @Test
  fun platformOf_mapsJvmProperties() {
    val mac = ReleasePlatform.of("Mac OS X", "aarch64")
    assertEquals(platform(ReleaseOs.MacOs, ReleaseArch.Arm64), mac)
    val windows = ReleasePlatform.of("Windows 11", "amd64")
    assertEquals(platform(ReleaseOs.Windows, ReleaseArch.X64), windows)
    assertEquals(platform(ReleaseOs.Linux, ReleaseArch.X64), ReleasePlatform.of("Linux", "x86_64"))
  }

  @Test
  fun platformOf_unreleasedPlatform_returnsNull() {
    assertNull(ReleasePlatform.of("FreeBSD", "amd64"))
    assertNull(ReleasePlatform.of("Linux", "ppc64le"))
  }

  private fun release(vararg names: String) = Release(
    version = version,
    pageUrl = "https://github.com/linroid/Ketch/releases/tag/v$version",
    assets = names.map { ReleaseAsset(it, "https://example.com/$it", 1, "00") },
  )

  private fun platform(os: ReleaseOs, arch: ReleaseArch) = ReleasePlatform(os, arch)
}
