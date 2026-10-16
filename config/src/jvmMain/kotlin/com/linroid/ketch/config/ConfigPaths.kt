package com.linroid.ketch.config

import java.io.File

/** Environment variable that, when set, replaces the platform's config directory. */
const val CONFIG_DIR_ENV = "KETCH_CONFIG_DIR"

/**
 * Returns the config directory for Ketch: [CONFIG_DIR_ENV] when it is set, such as `/config` in
 * the Docker image, otherwise the platform's:
 *
 * - macOS: `~/Library/Application Support/ketch`
 * - Windows: `%APPDATA%/ketch`
 * - Linux: `$XDG_CONFIG_HOME/ketch` (falls back to `~/.config/ketch`)
 */
fun defaultConfigDir(): String = configDir(System::getenv)

/** [defaultConfigDir] with the environment variables [getenv] returns. */
internal fun configDir(getenv: (String) -> String?): String {
  getenv(CONFIG_DIR_ENV)?.takeIf { it.isNotBlank() }?.let { return it }
  val os = System.getProperty("os.name", "").lowercase()
  val home = System.getProperty("user.home")
  return when {
    os.contains("mac") ->
      "$home${File.separator}Library${File.separator}" +
        "Application Support${File.separator}ketch"

    os.contains("win") -> {
      val appData = getenv("APPDATA")
        ?: "$home${File.separator}AppData${File.separator}Roaming"
      "$appData${File.separator}ketch"
    }

    else -> {
      val xdg = getenv("XDG_CONFIG_HOME")
        ?: "$home${File.separator}.config"
      "$xdg${File.separator}ketch"
    }
  }
}

/** Returns the default config file path: `<configDir>/config.toml`. */
fun defaultConfigPath(): String {
  return File(defaultConfigDir(), "config.toml").absolutePath
}

/** Returns the default database path: `<configDir>/ketch.db`. */
fun defaultDbPath(): String {
  val dir = File(defaultConfigDir())
  dir.mkdirs()
  return File(dir, "ketch.db").absolutePath
}
