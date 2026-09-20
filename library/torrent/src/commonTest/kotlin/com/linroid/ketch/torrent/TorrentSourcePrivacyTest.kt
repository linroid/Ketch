package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class TorrentSourcePrivacyTest {
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "announce" to "https://trusted/announce", "info" to mapOf("name" to "empty", "length" to 0L,
      "piece length" to 1L, "pieces" to ByteArray(0))
  )))
  private val magnet = "magnet:?xt=urn:btih:${metadata.infoHash.hex}" +
    "&tr=https%3A%2F%2Ftrusted%2Fannounce"

  private class Engine(private val metadata: TorrentMetadata) : TorrentEngine by FakeTorrentEngine() {
    val fetches = mutableListOf<TorrentDiscoveryPrivacy>()
    val tasks = mutableListOf<TorrentTaskSpec>()
    override suspend fun fetchMetadata(
      magnetUri: String,
      privacy: TorrentDiscoveryPrivacy,
    ): TorrentMetadata {
      fetches += privacy
      return metadata
    }
    override suspend fun addTask(spec: TorrentTaskSpec): TorrentSession {
      tasks += spec
      val session = FakeTorrentSession(spec.metadata.infoHash.hex)
      return object : TorrentSession by session {
        override suspend fun resume() { session.resume(); session.finish() }
      }
    }
  }

  private object NoIo : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Unexpected I/O")
    override suspend fun flush(): Unit = error("Unexpected I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Unexpected I/O")
    override suspend fun size(): Long = error("Unexpected I/O")
    override suspend fun preallocate(size: Long): Unit = error("Unexpected I/O")
  }

  private fun context(resolved: ResolvedSource? = null) = DownloadContext(
    taskId = "privacy", url = magnet,
    request = DownloadRequest(url = magnet, resolvedSource = resolved),
    fileAccessor = NoIo, segments = MutableStateFlow(emptyList()), onProgress = { _, _ -> },
    throttle = {}, headers = emptyMap(), preResolved = resolved, outputPath = "/tmp/empty",
  )

  private fun source(engine: TorrentEngine, privacy: TorrentDiscoveryPrivacy) =
    TorrentDownloadSource(TorrentConfig(discoveryPrivacy = privacy)).also {
      it.engineFactory = { engine }
    }

  @Test
  fun sourceDefaultAndExplicitResolutionAreCapturedBeforeDownload() = runTest {
    val engine = Engine(metadata)
    val source = source(engine, TorrentDiscoveryPrivacy.TRACKER_ONLY)
    try {
      val restricted = source.resolve(magnet)
      val explicit = source.resolve(magnet, TorrentDiscoveryPrivacy.PUBLIC)
      assertEquals(listOf(TorrentDiscoveryPrivacy.TRACKER_ONLY, TorrentDiscoveryPrivacy.PUBLIC),
        engine.fetches)
      val restrictedState = Json.decodeFromString<TorrentResumeState>(
        source.buildResumeState(restricted, 0).data)
      assertEquals(2, restrictedState.version)
      assertEquals(TorrentDiscoveryPrivacy.TRACKER_ONLY, restrictedState.privacy)
      source.download(context(explicit))
      assertEquals(TorrentDiscoveryPrivacy.PUBLIC, engine.tasks.single().privacy)
    } finally { source.close() }
  }

  @Test
  fun resolvedAndSavedPrivacySurviveAChangedDefaultAndMetadataRefetch() = runTest {
    val engine = Engine(metadata)
    val source = source(engine, TorrentDiscoveryPrivacy.PUBLIC)
    val resolved = source.resolve(magnet, TorrentDiscoveryPrivacy.TRACKER_ONLY)
    val first = context(resolved)
    val saved: SourceResumeState
    try {
      source.download(first)
      assertEquals(TorrentDiscoveryPrivacy.TRACKER_ONLY, engine.tasks.single().privacy)
      saved = assertNotNull(source.updateResumeState(first))
      assertEquals(TorrentDiscoveryPrivacy.TRACKER_ONLY,
        Json.decodeFromString<TorrentResumeState>(saved.data).privacy)
    } finally { source.close() }
    for (refetch in listOf(false, true)) {
      val restoredEngine = Engine(metadata)
      val restored = source(restoredEngine, TorrentDiscoveryPrivacy.PUBLIC)
      try {
        val state = if (refetch) {
          val decoded = Json.decodeFromString<TorrentResumeState>(saved.data)
          SourceResumeState(TorrentDownloadSource.TYPE,
            Json.encodeToString(decoded.copy(metainfo = "", resumeData = "")))
        } else saved
        val resumed = context()
        restored.resume(resumed, state)
        assertEquals(TorrentDiscoveryPrivacy.TRACKER_ONLY, restoredEngine.tasks.single().privacy)
        assertEquals(if (refetch) listOf(TorrentDiscoveryPrivacy.TRACKER_ONLY) else emptyList(),
          restoredEngine.fetches)
        assertEquals(TorrentDiscoveryPrivacy.TRACKER_ONLY,
          Json.decodeFromString<TorrentResumeState>(
            assertNotNull(restored.updateResumeState(resumed)).data).privacy)
      } finally { restored.close() }
    }
  }

  @Test
  fun legacyStateWithoutPrivacyUsesTheConfiguredDefaultAndMigrates() = runTest {
    for (privacy in TorrentDiscoveryPrivacy.entries) {
      val engine = Engine(metadata)
      val source = source(engine, privacy)
      try {
        val legacy = TorrentResumeState(metadata.infoHash.hex, 0, "", setOf("0"), "/tmp/empty")
        val encoded = Json.encodeToString(legacy)
        assertFalse("privacy" in encoded)
        val resumed = context()
        source.resume(resumed, SourceResumeState(TorrentDownloadSource.TYPE, encoded))
        assertEquals(listOf(privacy), engine.fetches)
        assertEquals(privacy, engine.tasks.single().privacy)
        val saved = Json.decodeFromString<TorrentResumeState>(
          assertNotNull(source.updateResumeState(resumed)).data)
        assertEquals(2, saved.version)
        assertEquals(privacy, saved.privacy)
      } finally { source.close() }
    }
  }

  @Test
  fun malformedOrDowngradedPrivacyFailsBeforeStartingTheEngine() = runTest {
    val engine = FakeTorrentEngine()
    val source = source(engine, TorrentDiscoveryPrivacy.PUBLIC)
    try {
      val resolved = source.resolveMetainfo(metadata.metainfoBytes,
        TorrentDiscoveryPrivacy.TRACKER_ONLY)
      val valid = Json.parseToJsonElement(source.buildResumeState(resolved, 0).data).jsonObject
      val invalid = listOf(valid - "privacy", valid + ("version" to JsonPrimitive(1)),
        valid + ("version" to JsonPrimitive(99)), valid + ("privacy" to JsonPrimitive("unknown")))
      for (fields in invalid) {
        assertFailsWith<KetchError.CorruptResumeState> {
          source.resume(context(), SourceResumeState(TorrentDownloadSource.TYPE,
            JsonObject(fields).toString()))
        }
        assertFalse(engine.started)
      }
    } finally { source.close() }
  }
}
