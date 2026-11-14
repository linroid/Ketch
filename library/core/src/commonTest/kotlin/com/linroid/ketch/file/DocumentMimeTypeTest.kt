package com.linroid.ketch.file

import com.linroid.ketch.core.file.DEFAULT_DOCUMENT_MIME_TYPE
import com.linroid.ketch.core.file.documentMimeType
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentMimeTypeTest {
  private val known = mapOf(
    "apk" to "application/vnd.android.package-archive",
    "gz" to "application/gzip",
    "zip" to "application/zip",
  )

  private fun typeOf(fileName: String): String = documentMimeType(fileName, known::get)

  @Test
  fun knownExtension_isItsType() {
    assertEquals("application/vnd.android.package-archive", typeOf("abc.apk"))
  }

  @Test
  fun unknownExtension_isTheDefaultType() {
    assertEquals(DEFAULT_DOCUMENT_MIME_TYPE, typeOf("abc.xyz"))
  }

  @Test
  fun noExtension_isTheDefaultType() {
    assertEquals(DEFAULT_DOCUMENT_MIME_TYPE, typeOf("README"))
    assertEquals(DEFAULT_DOCUMENT_MIME_TYPE, typeOf("abc."))
  }

  @Test
  fun severalDots_onlyTheLastExtensionCounts() {
    assertEquals("application/gzip", typeOf("backup.tar.gz"))
    assertEquals(DEFAULT_DOCUMENT_MIME_TYPE, typeOf("app.apk.part"))
    assertEquals("application/zip", typeOf("v1.2.zip"))
  }

  @Test
  fun leadingDot_isReadAsAnExtension() {
    assertEquals("application/zip", typeOf(".zip"))
    assertEquals(DEFAULT_DOCUMENT_MIME_TYPE, typeOf(".profile"))
  }

  @Test
  fun extension_isLookedUpAsWritten() {
    // The lookup lowers the case itself, by the same rules as the provider it asks for.
    val asked = mutableListOf<String>()
    documentMimeType("Setup.APK") { extension ->
      asked += extension
      null
    }
    assertEquals(listOf("APK"), asked)
  }
}
