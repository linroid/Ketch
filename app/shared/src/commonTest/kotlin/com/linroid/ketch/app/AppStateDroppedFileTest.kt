package com.linroid.ketch.app

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.ResolveState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStateDroppedFileTest {

  private val resolved = ResolvedSource(
    url = "torrent:abc",
    sourceType = "torrent",
    totalBytes = 3,
    supportsResume = true,
    suggestedFileName = "pack",
    maxSegments = 1,
  )

  private fun TestScope.appState(api: FakeKetchApi): AppState {
    val manager = InstanceManager(InstanceFactory(embeddedFactory = { api }))
    return AppState(manager, backgroundScope)
  }

  @Test
  fun addDroppedFiles_torrentAmongFiles_resolvesItsContent() = runTest {
    val api = FakeKetchApi().apply { resolveContentResult = resolved }
    val state = appState(api)
    val notes = DroppedFile("notes.txt") { error("Only the torrent should be read") }
    val torrent = DroppedFile("Pack.TORRENT") { byteArrayOf(1, 2, 3) }

    state.addDroppedFiles(listOf(notes, torrent))
    runCurrent()

    assertTrue(state.showAddDialog)
    assertEquals("Pack.TORRENT", state.droppedFile?.name)
    assertContentEquals(byteArrayOf(1, 2, 3), api.lastResolvedContent)
    assertEquals("Pack.TORRENT", api.lastResolvedFileName)
    assertEquals(resolved, assertIs<ResolveState.Resolved>(state.resolveState).result)
  }

  @Test
  fun addDroppedFiles_noTorrent_reportsErrorWithoutOpeningDialog() = runTest {
    val state = appState(FakeKetchApi())

    state.addDroppedFiles(listOf(DroppedFile("movie.mkv") { error("Must not be read") }))
    runCurrent()

    assertFalse(state.showAddDialog)
    assertNull(state.droppedFile)
    assertNotNull(state.errorMessage)
  }

  @Test
  fun resolveDroppedFile_readFailure_reportsError() = runTest {
    val api = FakeKetchApi().apply { resolveContentResult = resolved }
    val state = appState(api)

    state.addDroppedFiles(
      listOf(DroppedFile("big.torrent") { throw IllegalArgumentException("too large") }),
    )
    runCurrent()

    assertEquals("too large", assertIs<ResolveState.Error>(state.resolveState).message)
    assertNull(api.lastResolvedContent)
  }

  @Test
  fun resolveDroppedFile_malformedTorrent_explainsTheFileIsInvalid() = runTest {
    val api = object : KetchApi by FakeKetchApi() {
      override suspend fun resolveContent(content: ByteArray, fileName: String?) =
        throw KetchError.SourceError("torrent")
    }
    val manager = InstanceManager(InstanceFactory(embeddedFactory = { api }))
    val state = AppState(manager, backgroundScope)

    state.addDroppedFiles(listOf(DroppedFile("broken.torrent") { byteArrayOf(1) }))
    runCurrent()

    assertEquals(
      "broken.torrent is not a valid torrent file",
      assertIs<ResolveState.Error>(state.resolveState).message,
    )
  }

  @Test
  fun resetResolveState_whileReading_ignoresLateResult() = runTest {
    val api = FakeKetchApi().apply { resolveContentResult = resolved }
    val state = appState(api)
    val content = CompletableDeferred<ByteArray>()

    state.addDroppedFiles(listOf(DroppedFile("a.torrent") { content.await() }))
    runCurrent()
    state.resetResolveState()
    content.complete(byteArrayOf(1))
    runCurrent()

    assertNull(state.droppedFile)
    assertIs<ResolveState.Idle>(state.resolveState)
  }
}
