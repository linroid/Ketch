package com.linroid.ketch.file

import com.linroid.ketch.core.file.OutputPathReservations
import com.linroid.ketch.core.file.platformFileSystem
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputPathReservationsTest {
  @Test
  fun reserveUnique_reservedPath_getsNextFreeName() = withFolder { folder ->
    val first = OutputPathReservations.reserveUnique("$folder/file.zip")
    val second = OutputPathReservations.reserveUnique("$folder/file.zip")
    OutputPathReservations.release(first)
    val third = OutputPathReservations.reserveUnique("$folder/file.zip")

    assertEquals((folder / "file.zip").toString(), first)
    assertEquals((folder / "file (1).zip").toString(), second)
    assertEquals(first, third)
    OutputPathReservations.release(second)
    OutputPathReservations.release(third)
  }

  @Test
  fun reserveUnique_existingFile_getsNextFreeName() = withFolder { folder ->
    platformFileSystem.write(folder / "file.zip") {}

    val path = OutputPathReservations.reserveUnique("$folder/file.zip")

    assertEquals((folder / "file (1).zip").toString(), path)
    OutputPathReservations.release(path)
  }

  @Test
  fun reserve_samePathTwice_staysReservedUntilBothRelease() = withFolder { folder ->
    // A file destination is used as it is, so two downloads may name the same file.
    val path = "$folder/sub/../file.zip"
    OutputPathReservations.reserve(path)
    OutputPathReservations.reserve(path)
    OutputPathReservations.release(path)
    val whileHeld = OutputPathReservations.reserveUnique("$folder/file.zip")
    OutputPathReservations.release(path)
    val afterRelease = OutputPathReservations.reserveUnique("$folder/file.zip")

    assertEquals((folder / "file (1).zip").toString(), whileHeld)
    assertEquals((folder / "file.zip").toString(), afterRelease)
    OutputPathReservations.release(whileHeld)
    OutputPathReservations.release(afterRelease)
  }

  private fun withFolder(block: (Path) -> Unit) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-reserve-${Random.nextLong()}"
    platformFileSystem.createDirectories(folder)
    try {
      block(folder)
    } finally {
      platformFileSystem.deleteRecursively(folder)
    }
  }
}
