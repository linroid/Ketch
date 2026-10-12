package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpResumeState
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.decodeResumeState
import com.linroid.ketch.core.engine.encodeResumeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceResumeStateTest {
  private val source = HttpDownloadSource(FakeHttpEngine())

  @Test
  fun encodeResumeState_namesTheSourceType() {
    val saved = source.encodeResumeState(HttpResumeState("\"abc\"", null, 4096))

    assertEquals(HttpDownloadSource.TYPE, saved.sourceType)
  }

  @Test
  fun decodeResumeState_readsStateSavedBeforeTypedStates() {
    // The JSON earlier builds saved for HTTP tasks, before sources declared their state type.
    val saved = SourceResumeState(
      sourceType = "http",
      data = """{"etag":"\"abc\"","lastModified":"Thu, 01 Jan 2024 00:00:00 GMT",""" +
        """"totalBytes":4096}""",
    )

    val state = source.decodeResumeState(saved)

    assertEquals(HttpResumeState("\"abc\"", "Thu, 01 Jan 2024 00:00:00 GMT", 4096), state)
  }

  @Test
  fun decodeResumeState_ignoresUnknownFields() {
    val saved = SourceResumeState(
      sourceType = "http",
      data = """{"etag":null,"lastModified":null,"totalBytes":1,"addedLater":true}""",
    )

    assertEquals(HttpResumeState(null, null, 1), source.decodeResumeState(saved))
  }

  @Test
  fun decodeResumeState_malformed_throwsCorruptResumeState() {
    assertFailsWith<KetchError.CorruptResumeState> {
      source.decodeResumeState(SourceResumeState("http", "not-json"))
    }
    assertFailsWith<KetchError.CorruptResumeState> {
      source.decodeResumeState(SourceResumeState("http", "{}"))
    }
  }
}
