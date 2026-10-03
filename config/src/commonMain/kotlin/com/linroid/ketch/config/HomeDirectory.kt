package com.linroid.ketch.config

/**
 * Expands a leading `~` in [path] to [home], as a shell does for `~` and `~/...`.
 * Other paths, including the `~user` form, are returned unchanged, as is every path
 * when [home] is `null`.
 */
internal fun expandHome(path: String, home: String?): String {
  if (home.isNullOrEmpty()) return path
  return when {
    path == "~" -> home
    path.startsWith("~/") || path.startsWith("~\\") ->
      home.trimEnd('/', '\\') + path.substring(1)
    else -> path
  }
}

/** Expands a leading `~` in the paths users write in a config file. */
internal fun KetchConfig.expandHome(home: String?): KetchConfig {
  val directory = download.defaultDirectory?.let { expandHome(it, home) }
  val allowed = server.allowedDirectories.map { expandHome(it, home) }
  if (directory == download.defaultDirectory && allowed == server.allowedDirectories) return this
  return copy(
    download = download.copy(defaultDirectory = directory),
    server = server.copy(allowedDirectories = allowed),
  )
}
