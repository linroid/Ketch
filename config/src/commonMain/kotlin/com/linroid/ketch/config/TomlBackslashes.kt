package com.linroid.ketch.config

import com.akuleshov7.ktoml.tree.nodes.TomlKeyValueArray
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValuePrimitive
import com.akuleshov7.ktoml.tree.nodes.TomlNode
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlArray
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlBasicString
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlValue

// ktoml 0.7.1 writes a backslash followed by b, t, n, f, r, u, U or " as it is, taking it for an
// escape, so `F:\Downloads\backup` came back as `F:\Downloads<backspace>ackup` and `C:\Users`
// could not be read at all. Its escaper leaves `\u` alone, so every backslash of a basic string
// is written as the `\u005C` escape instead, which any TOML reader decodes as a backslash.

/** Writes each backslash of the basic strings under this node as `\u005C`. */
internal fun TomlNode.escapeBackslashes() {
  when (this) {
    is TomlKeyValuePrimitive -> value.escapeBackslashes()
    is TomlKeyValueArray -> value.escapeBackslashes()
    // Inline tables keep their pairs internal; KetchConfig writes none.
    else -> Unit
  }
  children.forEach { it.escapeBackslashes() }
}

private fun TomlValue.escapeBackslashes() {
  when (this) {
    is TomlBasicString -> content = (content as String).replace("\\", "\\u005C")
    is TomlArray -> (content as List<*>).forEach { (it as? TomlValue)?.escapeBackslashes() }
    else -> Unit
  }
}

/**
 * Restores the backslashes that versions writing through ktoml's escaper lost in folder paths,
 * where `\b`, `\t`, `\n`, `\f` and `\r` were read back as control characters. Only Windows paths
 * are repaired, as Windows refuses those characters in names while other systems allow them: a
 * path from a drive (`C:`), one starting with `\` or holding one, and category folders, which are
 * relative, under such a default directory.
 */
internal fun KetchConfig.repairLostBackslashes(): KetchConfig {
  val windowsDownloads = download.defaultDirectory?.let(::isWindowsPath) == true
  val download = download.copy(
    defaultDirectory = download.defaultDirectory?.let(::repairPath),
    categories = download.categories.map {
      it.copy(folder = if (windowsDownloads) repairAnyPath(it.folder) else repairPath(it.folder))
    },
  )
  val server = server.copy(allowedDirectories = server.allowedDirectories.map(::repairPath))
  val hooks = hooks.map { hook ->
    hook.copy(
      command = hook.command.mapIndexed { i, arg -> if (i == 0) repairPath(arg) else arg },
      workingDirectory = hook.workingDirectory?.let(::repairPath),
    )
  }
  val ui = ui.copy(
    intake = ui.intake.mapValues { (_, intake) ->
      intake.copy(
        folder = intake.folder?.let(::repairPath),
        favoriteFolders = intake.favoriteFolders.map(::repairPath),
      )
    },
  )
  val repaired = copy(download = download, server = server, hooks = hooks, ui = ui)
  return if (repaired == this) this else repaired
}

private fun isWindowsPath(path: String): Boolean =
  '\\' in path || (path.length >= 2 && path[0].isLetter() && path[1] == ':')

private fun repairPath(path: String): String = if (isWindowsPath(path)) repairAnyPath(path) else path

private fun repairAnyPath(path: String): String {
  if (path.none { it in LOST_ESCAPES }) return path
  return buildString {
    for (char in path) {
      val letter = LOST_ESCAPES[char]
      if (letter == null) append(char) else append('\\').append(letter)
    }
  }
}

private val LOST_ESCAPES = mapOf(
  '\b' to 'b',
  '\t' to 't',
  '\n' to 'n',
  '\u000C' to 'f',
  '\r' to 'r',
)
