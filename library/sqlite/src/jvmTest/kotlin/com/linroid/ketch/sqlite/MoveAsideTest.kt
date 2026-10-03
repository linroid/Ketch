package com.linroid.ketch.sqlite

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

class MoveAsideTest {
  private val dir = Files.createTempDirectory("ketch-sqlite").toFile()

  @AfterTest
  fun deleteDir() {
    dir.deleteRecursively()
  }

  @Test
  fun moveDatabaseAside_journal_movesWithTheDatabase() {
    val db = File(dir, "ketch.db").apply { writeText("db") }
    val journal = File(dir, "ketch.db-journal").apply { writeText("journal") }

    val movedTo = moveDatabaseAside(FileSystem.SYSTEM, db.path)

    assertFalse(db.exists())
    assertFalse(journal.exists())
    assertEquals("db", File(movedTo).readText())
    assertEquals("journal", File("$movedTo-journal").readText())
  }

  @Test
  fun moveDatabaseAside_journalCannotMove_putsTheDatabaseBack() {
    val db = File(dir, "ketch.db").apply { writeText("db") }
    val journal = File(dir, "ketch.db-journal").apply { writeText("journal") }
    // Such as a journal another process holds open on Windows.
    val lockedJournal = object : ForwardingFileSystem(FileSystem.SYSTEM) {
      override fun atomicMove(source: Path, target: Path) {
        if (source.name.endsWith("-journal")) throw IOException("locked")
        super.atomicMove(source, target)
      }
    }

    assertFailsWith<IOException> { moveDatabaseAside(lockedJournal, db.path) }

    assertEquals(setOf("ketch.db", "ketch.db-journal"), dir.list()?.toSet())
    assertEquals("db", db.readText())
    assertEquals("journal", journal.readText())
  }

  @Test
  fun brokenPath_nameTaken_isNumbered() {
    val now = Instant.parse("2026-10-03T14:25:30.500Z")
    val taken = setOf("ketch.db.broken-20261003T142530Z", "ketch.db.broken-20261003T142530Z-2")

    assertEquals(
      "ketch.db.broken-20261003T142530Z-3",
      brokenPath("ketch.db", exists = taken::contains, now = now),
    )
  }
}
