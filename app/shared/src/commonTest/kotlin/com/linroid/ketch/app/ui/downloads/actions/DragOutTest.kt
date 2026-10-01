package com.linroid.ketch.app.ui.downloads.actions

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.TaskKey
import kotlin.test.Test
import kotlin.test.assertEquals

class DragOutTest {
  private val done = ListFixtures.row(
    id = "a",
    state = DownloadState.Completed("/downloads/a.iso", totalBytes = 10),
    request = DownloadRequest("https://example.com/a.iso"),
  )
  private val running = ListFixtures.row(
    id = "b",
    state = ListFixtures.downloading(5),
    request = DownloadRequest("https://example.com/b.iso"),
  )

  @Test
  fun dragPayloadOf_localFinishedFile_carriesItsPathAndEveryLink() {
    val payload = DragPayload.of(listOf(done, running))

    assertEquals(listOf("/downloads/a.iso"), payload.files)
    assertEquals(listOf("https://example.com/a.iso", "https://example.com/b.iso"), payload.links)
    assertEquals("/downloads/a.iso", payload.text)
    assertEquals("https://example.com/a.iso\r\nhttps://example.com/b.iso\r\n", payload.uriList)
  }

  @Test
  fun dragPayloadOf_remoteFinishedFile_carriesOnlyItsLink() {
    val remote = ListFixtures.row(
      id = "a",
      state = DownloadState.Completed("/srv/a.iso", totalBytes = 10),
      deviceId = "nas.local:8642",
      device = RemoteDevice,
    )

    val payload = DragPayload.of(listOf(remote))

    assertEquals(emptyList(), payload.files)
    assertEquals(payload.links.single(), payload.text)
  }

  @Test
  fun keysOf_keysText_roundTrips() {
    val payload = DragPayload.of(listOf(done, running))

    assertEquals(listOf(done.key, running.key), DragPayload.keysOf(payload.keysText))
    assertEquals(listOf(TaskKey("local", "a")), DragPayload.keysOf("hello\nketch-task://local/a"))
  }
}
