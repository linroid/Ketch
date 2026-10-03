package com.linroid.ketch.file

import com.linroid.ketch.core.file.MAX_FILE_NAME_BYTES
import com.linroid.ketch.core.file.fitFileName
import com.linroid.ketch.core.file.isInsideDirectory
import com.linroid.ketch.core.file.sanitizeFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNamesTest {
  @Test
  fun sanitizeFileName_slashTraversal_keepsLastSegment() {
    assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
  }

  @Test
  fun sanitizeFileName_backslashTraversal_keepsLastSegment() {
    assertEquals("evil.bat", sanitizeFileName("..\\..\\Startup\\evil.bat"))
  }

  @Test
  fun sanitizeFileName_absolutePaths_keepLastSegment() {
    assertEquals("x.desktop", sanitizeFileName("/home/me/.config/autostart/x.desktop"))
    assertEquals("evil.exe", sanitizeFileName("C:\\Windows\\evil.exe"))
    assertEquals("share.txt", sanitizeFileName("\\\\server\\share.txt"))
  }

  @Test
  fun sanitizeFileName_nothingUsable_returnsNull() {
    for (name in listOf("", "   ", ".", "..", "...", " . ", "../", "..\\", "a/..", "\u0000")) {
      assertNull(sanitizeFileName(name), "\"$name\"")
    }
  }

  @Test
  fun sanitizeFileName_controlAndBidiCharacters_removed() {
    assertEquals("abc.txt", sanitizeFileName("a\u0000b\tc\u007F\u0085.txt"))
    assertEquals("invoicegpj.exe", sanitizeFileName("invoice\u202Egpj.exe"))
  }

  @Test
  fun sanitizeFileName_windowsReservedCharacters_replaced() {
    assertEquals("a_b_c_d_e_f_g_.txt", sanitizeFileName("a<b>c:d\"e|f?g*.txt"))
    // A volume letter would make okio resolve the name on its own, outside the folder.
    assertEquals("C_evil.txt", sanitizeFileName("C:evil.txt"))
  }

  @Test
  fun sanitizeFileName_windowsDeviceNames_prefixed() {
    assertEquals("_CON", sanitizeFileName("CON"))
    assertEquals("_nul.txt", sanitizeFileName("nul.txt"))
    assertEquals("_com1.tar.gz", sanitizeFileName("com1.tar.gz"))
    assertEquals("_LPT9", sanitizeFileName("LPT9"))
    assertEquals("CONSOLE.txt", sanitizeFileName("CONSOLE.txt"))
    assertEquals("COM10", sanitizeFileName("COM10"))
  }

  @Test
  fun sanitizeFileName_surroundingWhitespaceAndTrailingDots_trimmed() {
    assertEquals("file.txt", sanitizeFileName("  file.txt. . "))
  }

  @Test
  fun sanitizeFileName_leadingDots_kept() {
    assertEquals(".bashrc", sanitizeFileName(".bashrc"))
    assertEquals("..data", sanitizeFileName("..data"))
  }

  @Test
  fun sanitizeFileName_longName_cutKeepingExtension() {
    val name = sanitizeFileName("a".repeat(300) + ".zip")!!

    assertEquals(MAX_FILE_NAME_BYTES, name.encodeToByteArray().size)
    assertEquals("a".repeat(251) + ".zip", name)
  }

  @Test
  fun sanitizeFileName_longMultibyteName_cutAtCharacterBoundary() {
    val chinese = sanitizeFileName("中".repeat(100) + ".txt")!!
    assertEquals("中".repeat(83) + ".txt", chinese)

    val emoji = sanitizeFileName("😀".repeat(70))!!
    assertEquals("😀".repeat(63), emoji)
  }

  @Test
  fun sanitizeFileName_extensionTooLongToKeep_cutWithStem() {
    val name = sanitizeFileName("a." + "b".repeat(300))!!

    assertEquals("a." + "b".repeat(253), name)
  }

  @Test
  fun fitFileName_suffix_insertedBeforeExtensionWithinLimit() {
    assertEquals("file (1).zip", fitFileName("file.zip", " (1)"))
    assertEquals("a".repeat(247) + " (1).zip", fitFileName("a".repeat(251) + ".zip", " (1)"))
  }

  @Test
  fun isInsideDirectory_childOfFolder_isInside() {
    assertTrue(isInsideDirectory("/dl", "/dl/file.zip"))
    assertTrue(isInsideDirectory("/dl", "/dl/sub/file.zip"))
    assertTrue(isInsideDirectory("dl", "dl/file.zip"))
    assertTrue(isInsideDirectory("", "file.zip"))
    assertTrue(isInsideDirectory("..", "../file.zip"))
  }

  @Test
  fun isInsideDirectory_escapingPaths_areNotInside() {
    assertFalse(isInsideDirectory("/dl", "/dl/../file.zip"))
    assertFalse(isInsideDirectory("/dl", "/dl/..\\file.zip"))
    assertFalse(isInsideDirectory("/dl", "/etc/file.zip"))
    assertFalse(isInsideDirectory("/dl", "/dlx/file.zip"))
    assertFalse(isInsideDirectory("/dl", "/dl"))
    assertFalse(isInsideDirectory("/dl", "C:file.zip"))
    assertFalse(isInsideDirectory("", "../file.zip"))
    assertFalse(isInsideDirectory("..", "../../file.zip"))
  }
}
