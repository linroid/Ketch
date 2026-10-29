package com.linroid.ketch.config

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class DeviceStoreTest {
  private val dir = createTempDirectory("ketch-device").toFile()
  private val file = File(dir, DEVICE_FILE_NAME)

  @AfterTest
  fun deleteDir() {
    dir.deleteRecursively()
  }

  @Test
  fun instanceId_noFile_createsOwnerOnlyFileWithRandomUuid() {
    val id = DeviceStore(file.path).instanceId()

    assertEquals(4, UUID.fromString(id).version())
    assertEquals("{\"instanceId\":\"$id\"}", file.readText())
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
      val permissions = Files.getPosixFilePermissions(file.toPath())
      assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
    }
    assertFalse(File(dir, "$DEVICE_FILE_NAME.tmp").exists())
  }

  @Test
  fun instanceId_missingDirectory_createsIt() {
    val nested = File(dir, "config/ketch/$DEVICE_FILE_NAME")

    val id = DeviceStore(nested.path).instanceId()

    assertEquals(id, DeviceStore(nested.path).instanceId())
  }

  @Test
  fun instanceId_savedFile_returnsTheSameIdEveryTime() {
    val first = DeviceStore(file.path).instanceId()
    val written = file.lastModified()

    assertEquals(first, DeviceStore(file.path).instanceId())
    assertEquals(first, DeviceStore(file.path).instanceId())
    assertEquals(written, file.lastModified())
  }

  @Test
  fun instanceId_fileOfLaterVersion_keepsItsIdAndFields() {
    val id = "0b9f3a52-6c1e-4d3e-9a51-2f4c7d8e9b10"
    val content = """{"instanceId":"$id","pairings":[{"id":"x"}]}"""
    file.writeText(content)

    assertEquals(id, DeviceStore(file.path).instanceId())
    assertEquals(content, file.readText())
  }

  @Test
  fun instanceId_unreadableFile_replacesItWithANewId() {
    val unreadable = listOf(
      "",
      "not json",
      "{}",
      """{"instanceId":42}""",
      """{"instanceId":"not-a-uuid"}""",
      """{"instanceId":"0b9f3a52-6c1e-4d3e-9a51"}""",
    )
    for (content in unreadable) {
      file.writeText(content)

      val id = DeviceStore(file.path).instanceId()

      assertNotNull(Uuid.parseOrNull(id), content)
      assertEquals("{\"instanceId\":\"$id\"}", file.readText(), content)
      assertEquals(id, DeviceStore(file.path).instanceId(), content)
    }
  }

  @Test
  fun instanceId_cannotBeSaved_returnsANewIdEachTime() {
    // Its folder is a regular file, so nothing can be written there.
    val blocked = File(dir, "blocked").apply { writeText("") }
    val path = File(blocked, DEVICE_FILE_NAME).path

    val first = DeviceStore(path).instanceId()

    assertNotNull(Uuid.parseOrNull(first))
    assertNotEquals(first, DeviceStore(path).instanceId())
  }
}
