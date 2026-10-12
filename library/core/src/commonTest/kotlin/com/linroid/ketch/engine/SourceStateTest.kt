package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpSourceState
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.decodeState
import com.linroid.ketch.core.engine.encodeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceStateTest {
  private val source = HttpDownloadSource(FakeHttpEngine())

  @Test
  fun encodeResumeState_namesTheSourceType() {
    val saved = source.encodeState(HttpSourceState("\"abc\"", null, 4096))

    assertEquals(HttpDownloadSource.TYPE, saved.sourceType)
  }

  @Test
  fun decodeResumeState_readsStateSavedBeforeTypedStates() {
    // The JSON earlier builds saved for HTTP tasks, before sources declared their state type.
    val saved = SourceState(
      sourceType = "http",
      data = """{"etag":"\"abc\"","lastModified":"Thu, 01 Jan 2024 00:00:00 GMT",""" +
        """"totalBytes":4096}""",
    )

    val state = source.decodeState(saved)

    assertEquals(HttpSourceState("\"abc\"", "Thu, 01 Jan 2024 00:00:00 GMT", 4096), state)
  }

  @Test
  fun decodeResumeState_ignoresUnknownFields() {
    val saved = SourceState(
      sourceType = "http",
      data = """{"etag":null,"lastModified":null,"totalBytes":1,"addedLater":true}""",
    )

    assertEquals(HttpSourceState(null, null, 1), source.decodeState(saved))
  }

  @Test
  fun decodeResumeState_anotherSourcesState_throwsCorruptResumeState() {
    // An FTP state would decode as HTTP's, skipping its ETag and Last-Modified checks.
    val saved = SourceState("ftp", """{"etag":null,"lastModified":null,"totalBytes":1}""")

    assertFailsWith<KetchError.CorruptResumeState> { source.decodeState(saved) }
  }

  @Test
  fun decodeResumeState_malformed_throwsCorruptResumeState() {
    assertFailsWith<KetchError.CorruptResumeState> {
      source.decodeState(SourceState("http", "not-json"))
    }
    assertFailsWith<KetchError.CorruptResumeState> {
      source.decodeState(SourceState("http", "{}"))
    }
  }
}
