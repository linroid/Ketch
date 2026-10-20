package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class TomlBackslashesTest {
  private val values = listOf(
    "F:\\Downloads\\backup",
    "D:\\Téléchargements\\new",
    "C:\\new\\tab\\file",
    "C:\\Users\\u\\x",
    "C:\\fonts\\route\\Ubuntu",
    "\\\\nas\\share\\",
    "say \"hi\"\\",
    "a\\\"b",
    "\\u005C",
  )

  @Test
  fun encode_stringsWithBackslashes_decodeUnchanged() {
    for (value in values) {
      val config = KetchConfig(
        name = value,
        download = DownloadConfig(
          defaultDirectory = value,
          categories = listOf(DownloadCategory(folder = "Games\\new", extensions = listOf("iso"))),
        ),
        server = ServerConfig(allowedDirectories = listOf(value, "D:\\backup")),
        hooks = listOf(
          HookConfig(
            events = listOf("completed"),
            command = listOf("C:\\tools\\run.exe", value),
            workingDirectory = value,
          ),
        ),
      )

      assertEquals(config, ConfigStore.decode(ConfigStore.encode(config)), value)
    }
  }

  @Test
  fun encode_controlCharacters_decodeUnchanged() {
    val config = KetchConfig(name = "a\tb\nc\bd")

    assertEquals(config, ConfigStore.decode(ConfigStore.encode(config)))
  }

  @Test
  fun decode_folderPathsOlderVersionsWrote_restoresBackslashes() {
    // As ktoml wrote `F:\Downloads\backup` and friends: escapes in place of backslashes.
    val text = """
      [download]
      defaultDirectory = "F:\\Downloads\backup"
      [[download.categories]]
      folder = "Games\new"
      extensions = ["iso"]
      [server]
      allowedDirectories = ["C:\temp", "D:\\files\fonts\run"]
      [[hooks]]
      events = ["completed"]
      command = ["C:\\tools\\notify.exe", "line\none"]
      workingDirectory = "C:\\scripts\new"
      [ui.intake.local]
      folder = "D:\backup"
      favoriteFolders = ["E:\\films\new"]
    """.trimIndent()

    val config = ConfigStore.decode(text)

    assertEquals("F:\\Downloads\\backup", config.download.defaultDirectory)
    assertEquals("Games\\new", config.download.categories.single().folder)
    assertEquals(listOf("C:\\temp", "D:\\files\\fonts\\run"), config.server.allowedDirectories)
    val hook = config.hooks.single()
    // Only the program is a path; an argument may hold a newline.
    assertEquals(listOf("C:\\tools\\notify.exe", "line\none"), hook.command)
    assertEquals("C:\\scripts\\new", hook.workingDirectory)
    val intake = config.ui.intake.getValue("local")
    assertEquals("D:\\backup", intake.folder)
    assertEquals(listOf("E:\\films\\new"), intake.favoriteFolders)
  }

  @Test
  fun decode_posixFoldersWithControlCharacters_keepsThem() {
    // Other systems allow tabs and newlines in names, so only Windows paths are repaired.
    val config = KetchConfig(
      download = DownloadConfig(
        defaultDirectory = "/data/a\tb",
        categories = listOf(DownloadCategory(folder = "x\ny", extensions = listOf("iso"))),
      ),
      server = ServerConfig(allowedDirectories = listOf("/srv/new\nline")),
    )

    assertEquals(config, ConfigStore.decode(ConfigStore.encode(config)))
  }

  @Test
  fun decode_namesOutsideFolderPaths_keepControlCharacters() {
    val config = ConfigStore.decode("name = \"two\\nlines\"")

    assertEquals("two\nlines", config.name)
  }
}
