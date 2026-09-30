package com.linroid.ketch.config

import java.io.File

/**
 * [ConfigStore] for config files on the JVM, where `download.defaultDirectory`
 * may start with `~` to name a folder in the user's home directory, as in
 * `defaultDirectory = "~/Downloads"`.
 *
 * [load] replaces a leading `~` or `~/` with [home], like a shell does. Other
 * paths, including `~user/...` and a `~` later in the path, are left as they
 * are. [save] writes the config it is given, so saving a loaded config
 * stores the expanded path.
 *
 * @param delegate store that reads and writes the file.
 * @param home directory that `~` stands for.
 */
class HomeExpandingConfigStore(
  private val delegate: ConfigStore,
  private val home: String = System.getProperty("user.home"),
) : ConfigStore by delegate {
  override fun load(): KetchConfig {
    val config = delegate.load()
    val directory = config.download.defaultDirectory ?: return config
    return config.copy(
      download = config.download.copy(defaultDirectory = expandHome(directory, home)),
    )
  }
}

private fun expandHome(path: String, home: String): String = when {
  path == "~" -> home
  path.startsWith("~/") || path.startsWith("~" + File.separator) ->
    File(home, path.substring(2)).path
  else -> path
}
