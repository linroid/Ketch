package com.linroid.ketch.cli

import com.linroid.ketch.torrent.CreatedTorrent
import com.linroid.ketch.torrent.TorrentCreateOptions
import com.linroid.ketch.torrent.TorrentFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CreateTorrentTest {
  private class Printed(val status: Int, val out: String, val err: String)

  private fun run(vararg args: String): Printed {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val status = runCreate(args.toList(), PrintStream(out), PrintStream(err), progress = false)
    return Printed(status, out.toString().trim(), err.toString().trim())
  }

  private fun inFolder(block: (File) -> Unit) {
    val folder = Files.createTempDirectory("ketch-create-cli").toFile()
    try {
      block(folder)
    } finally {
      folder.deleteRecursively()
    }
  }

  @Test
  fun `options map to how the torrent is created`() {
    val run = assertIs<CreateArgs.Run>(
      parseCreateArgs(
        listOf(
          "--format", "hybrid", "-t", "http://a.example/announce", "--tracker",
          "udp://b.example:1337", "--web-seed", "https://m.example/pack", "--private",
          "--piece-length", "256k", "--name", "pack", "--comment", "hi", "--source", "TAG",
          "-o", "out.torrent", "--overwrite", "--json", "folder",
        )
      )
    )
    assertEquals("folder", run.path)
    assertEquals("out.torrent", run.output)
    assertTrue(run.overwrite && run.json)
    assertEquals(
      TorrentCreateOptions(
        format = TorrentFormat.HYBRID,
        pieceLength = 256 * 1024,
        name = "pack",
        trackers = listOf(listOf("http://a.example/announce"), listOf("udp://b.example:1337")),
        webSeeds = listOf("https://m.example/pack"),
        privateTorrent = true,
        comment = "hi",
        source = "TAG",
      ),
      run.options,
    )
  }

  @Test
  fun `arguments that cannot be used say why`() {
    assertEquals(CreateArgs.Invalid("missing <path>"), parseCreateArgs(emptyList()))
    assertEquals(CreateArgs.Help, parseCreateArgs(listOf("a", "--help")))
    assertEquals(
      CreateArgs.Invalid("unexpected argument 'b'"),
      parseCreateArgs(listOf("a", "b")),
    )
    assertIs<CreateArgs.Invalid>(parseCreateArgs(listOf("--format", "v3", "a")))
    assertIs<CreateArgs.Invalid>(parseCreateArgs(listOf("--piece-length", "100k", "a")))
    assertIs<CreateArgs.Invalid>(parseCreateArgs(listOf("--tracker")))
    assertEquals(CreateExit.USAGE, run("--bogus", "a").status)
  }

  @Test
  fun `piece lengths take binary units and only powers of two in range`() {
    assertEquals(16_384, parsePieceLength("16384"))
    assertEquals(256 * 1024, parsePieceLength("256K"))
    assertEquals(4 * 1024 * 1024, parsePieceLength("4m"))
    for (invalid in listOf("8k", "32m", "100k", "big", "")) {
      assertEquals(null, parsePieceLength(invalid))
    }
  }

  @Test
  fun `create saves the torrent file and prints its path and magnet link`() = inFolder { folder ->
    File(folder, "pack").mkdirs()
    File(folder, "pack/a.bin").writeBytes(ByteArray(50_000) { it.toByte() })
    val target = File(folder, "out/pack.torrent")

    val printed = run("-o", target.path, "-t", "http://a.example/announce",
      File(folder, "pack").path)

    assertEquals(CreateExit.OK, printed.status)
    val lines = printed.out.lines()
    assertEquals(target.absolutePath, lines[0])
    assertTrue(lines[1].startsWith("magnet:?xt=urn:btih:"))
    assertTrue(target.readBytes().decodeToString(0, 12).startsWith("d8:announce"))

    // An existing file is kept unless asked to replace it.
    val again = run("-o", target.path, File(folder, "pack").path)
    assertEquals(CreateExit.FAILED, again.status)
    assertTrue("already exists" in again.err)
    assertEquals(CreateExit.OK, run("-o", target.path, "--overwrite", File(folder, "pack").path).status)
  }

  @Test
  fun `create prints the details as JSON and names the file after the torrent`() =
    inFolder { folder ->
      File(folder, "a.bin").writeBytes(ByteArray(100) { it.toByte() })

      val printed = run("--json", "--format", "v2", "-o", folder.path, File(folder, "a.bin").path)

      val details = Json.parseToJsonElement(printed.out).jsonObject
      assertEquals(File(folder, "a.bin.torrent").absolutePath,
        details.getValue("file").jsonPrimitive.content)
      assertEquals(64, details.getValue("infoHashV2").jsonPrimitive.content.length)
      assertEquals("100", details.getValue("size").jsonPrimitive.content)
    }

  @Test
  fun `create fails for content that is missing or cannot become a torrent`() = inFolder { folder ->
    val missing = run(File(folder, "none").path)
    assertEquals(CreateExit.FAILED, missing.status)
    assertTrue("does not exist" in missing.err)

    File(folder, "empty").mkdirs()
    val empty = run(File(folder, "empty").path)
    assertEquals(CreateExit.FAILED, empty.status)
    assertTrue("nothing to share" in empty.err)
  }
}
