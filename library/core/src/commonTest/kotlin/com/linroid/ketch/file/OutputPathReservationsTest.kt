package com.linroid.ketch.file

import com.linroid.ketch.core.file.OutputPathReservations
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.file.renamedIfExists
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
  fun reserveUnique_existingPartialFile_getsNextFreeName() = withFolder { folder ->
    // A paused download writing file.zip.ketch keeps file.zip taken.
    platformFileSystem.write(folder / "file.zip.ketch") {}

    val path = OutputPathReservations.reserveUnique("$folder/file.zip")

    assertEquals((folder / "file (1).zip").toString(), path)
    OutputPathReservations.release(path)
  }

  @Test
  fun reserveUnique_nameOfReservedPartialFile_getsNextFreeName() = withFolder { folder ->
    val first = OutputPathReservations.reserveUnique("$folder/file.zip")
    val second = OutputPathReservations.reserveUnique("$folder/file.zip.ketch")

    assertEquals((folder / "file.zip (1).ketch").toString(), second)
    OutputPathReservations.release(first)
    OutputPathReservations.release(second)
  }

  @Test
  fun reserveUnique_existingFile_getsNextFreeName() = withFolder { folder ->
    platformFileSystem.write(folder / "file.zip") {}

    val path = OutputPathReservations.reserveUnique("$folder/file.zip")

    assertEquals((folder / "file (1).zip").toString(), path)
    OutputPathReservations.release(path)
  }

  @Test
  fun reserveReplaceable_existingFile_reservesItsOwnName() = withFolder { folder ->
    platformFileSystem.write(folder / "file.zip") {}

    val path = OutputPathReservations.reserveReplaceable("$folder/file.zip")
    val second = OutputPathReservations.reserveReplaceable("$folder/file.zip")

    assertEquals((folder / "file.zip").toString(), path)
    // Held by the first download now.
    assertNull(second)
    OutputPathReservations.release(checkNotNull(path))
  }

  @Test
  fun reserveReplaceable_existingPartialFile_isRefused() = withFolder { folder ->
    platformFileSystem.write(folder / "file.zip") {}
    platformFileSystem.write(folder / "file.zip.ketch") {}

    assertNull(OutputPathReservations.reserveReplaceable("$folder/file.zip"))
  }

  @Test
  fun reserveReplaceable_nameOfAPartialFile_isRefused() = withFolder { folder ->
    // It may be what a paused download of file.zip writes.
    platformFileSystem.write(folder / "file.zip.ketch") {}

    assertNull(OutputPathReservations.reserveReplaceable("$folder/file.zip.ketch"))
  }

  @Test
  fun reserveReplaceable_folderOrNameTooLongForPartialFile_isRefused() = withFolder { folder ->
    platformFileSystem.createDirectories(folder / "folder.zip")
    val long = "a".repeat(248) + ".zip"

    assertNull(OutputPathReservations.reserveReplaceable("$folder/folder.zip"))
    assertNull(OutputPathReservations.reserveReplaceable("$folder/$long"))
  }

  @Test
  fun renamedIfExists_existingFile_namesTheFreeNameBesideIt() = withFolder { folder ->
    platformFileSystem.write(folder / "file.zip") {}
    platformFileSystem.write(folder / "file (1).zip") {}

    assertEquals("file (2).zip", renamedIfExists(folder.toString(), "file.zip"))
    assertEquals("file (2).zip", renamedIfExists("$folder/", "sub/file.zip"))
  }

  @Test
  fun renamedIfExists_freeNameOrFolderUri_isNull() = withFolder { folder ->
    platformFileSystem.write(folder / "file.zip") {}

    assertNull(renamedIfExists(folder.toString(), "other.zip"))
    assertNull(renamedIfExists("content://com.android.providers/tree/1", "file.zip"))
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
