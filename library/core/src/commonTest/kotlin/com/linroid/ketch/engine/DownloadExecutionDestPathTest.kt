package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadExecution
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Verifies where a download into a folder is saved: a name from the server, the link or a
 * [FileNameResolver] never leaves the folder, a file destination is used as it is, and downloads
 * running at once never share a path.
 */
class DownloadExecutionDestPathTest {
  @Test
  fun execute_suggestedNameWithParentSegments_staysInFolder() = runTest {
    withFolder { folder ->
      val path = outputPathFor(folder, suggestedFileName = "../../.config/autostart/x.desktop")

      assertEquals((folder / "x.desktop").toString(), path)
    }
  }

  @Test
  fun execute_suggestedNameWithBackslashes_staysInFolder() = runTest {
    withFolder { folder ->
      val path = outputPathFor(folder, suggestedFileName = "..\\..\\Startup\\evil.bat")

      assertEquals((folder / "evil.bat").toString(), path)
    }
  }

  @Test
  fun execute_absoluteSuggestedName_staysInFolder() = runTest {
    withFolder { folder ->
      val path = outputPathFor(folder, suggestedFileName = "/etc/x")

      assertEquals((folder / "x").toString(), path)
    }
  }

  @Test
  fun execute_suggestedNameLeavingNothing_usesFallbackName() = runTest {
    withFolder { folder ->
      val path = outputPathFor(folder, suggestedFileName = "..")

      assertEquals((folder / DefaultFileNameResolver.FALLBACK).toString(), path)
    }
  }

  @Test
  fun execute_resolverNameWithParentSegments_staysInFolder() = runTest {
    withFolder { folder ->
      val path = outputPathFor(
        folder,
        suggestedFileName = null,
        fileNameResolver = { _, _ -> "../escape.bin" },
      )

      assertEquals((folder / "escape.bin").toString(), path)
    }
  }

  @Test
  fun execute_destinationNameLeavingFolder_failsWithDiskError() = runTest {
    withFolder { folder ->
      val execution = execution(
        request = DownloadRequest("fixture:input", destination = Destination("..")),
        source = FixtureSource("file.bin"),
        config = DownloadConfig(defaultDirectory = folder.toString(), saveIntervalMs = 60_000),
      )

      assertFailsWith<KetchError.Disk> { execution.execute() }
    }
  }

  @Test
  fun execute_fileDestination_usedAsIs() = runTest {
    withFolder { folder ->
      val output = "$folder/sub/../file.bin"
      val source = FixtureSource("ignored.bin")

      execution(DownloadRequest("fixture:input", destination = Destination(output)), source)
        .execute()

      assertEquals(listOf(output), source.outputPaths)
    }
  }

  @Test
  fun execute_concurrentDownloadsWithSameName_getDifferentPaths() = runTest {
    withFolder { folder ->
      val gate = CompletableDeferred<Unit>()
      val source = FixtureSource("same.bin", firstWaitsFor = gate)
      val request = DownloadRequest("fixture:input", destination = Destination("$folder/"))

      // The first holds its path without having created the file yet.
      val first = launch { execution(request, source).execute() }
      runCurrent()
      execution(request, source).execute()
      gate.complete(Unit)
      first.join()
      // Once the first has stopped, its path is free again: no file was written.
      execution(request, source).execute()

      assertEquals(
        listOf(folder / "same.bin", folder / "same (1).bin", folder / "same.bin")
          .map { it.toString() },
        source.outputPaths,
      )
    }
  }

  @Test
  fun download_contentDispositionWithEncodedTraversal_savedInFolder() = runTest {
    withFolder { folder ->
      val output = downloadOverHttp(
        folder,
        url = "https://example.com/file",
        contentDisposition =
          "attachment; filename*=UTF-8''..%2F..%2F.config%2Fautostart%2Fx.desktop",
      )

      assertEquals(folder / "x.desktop", output)
    }
  }

  @Test
  fun download_urlWithEncodedSlashes_savedInFolder() = runTest {
    withFolder { folder ->
      val output = downloadOverHttp(folder, url = "https://example.com/files/..%2F..%2Fevil.sh")

      assertEquals(folder / "evil.sh", output)
    }
  }

  @Test
  fun download_urlWithEncodedBackslashes_savedInFolder() = runTest {
    withFolder { folder ->
      val output = downloadOverHttp(folder, url = "https://example.com/files/..%5C..%5Cevil.sh")

      assertEquals(folder / "evil.sh", output)
    }
  }

  @Test
  fun execute_noDestination_savedInMatchingCategoryFolder() = runTest {
    withFolder { folder ->
      val path = categoryOutputPath(folder, destination = null, suggestedFileName = "clip.MKV")

      assertEquals((folder / "Video" / "clip.MKV").toString(), path)
    }
  }

  @Test
  fun execute_bareNameDestination_categoryChosenByThatName() = runTest {
    withFolder { folder ->
      val path = categoryOutputPath(
        folder,
        destination = Destination("talk.mp4"),
        suggestedFileName = "ignored.zip",
      )

      assertEquals((folder / "Video" / "talk.mp4").toString(), path)
    }
  }

  @Test
  fun execute_folderDestination_ignoresCategories() = runTest {
    withFolder { folder ->
      val path = categoryOutputPath(
        folder,
        destination = Destination("$folder/"),
        suggestedFileName = "clip.mkv",
      )

      assertEquals((folder / "clip.mkv").toString(), path)
    }
  }

  @Test
  fun execute_noMatchingCategory_savedInDefaultFolder() = runTest {
    withFolder { folder ->
      val path = categoryOutputPath(folder, destination = null, suggestedFileName = "notes.txt")

      assertEquals((folder / "notes.txt").toString(), path)
    }
  }

  @Test
  fun execute_contentTypeFromSource_matchesMimeRule() = runTest {
    withFolder { folder ->
      val path = categoryOutputPath(
        folder,
        destination = null,
        suggestedFileName = "watch",
        contentType = "video/webm",
      )

      assertEquals((folder / "Video" / "watch").toString(), path)
    }
  }

  @Test
  fun execute_hostRule_matchesRequestHost() = runTest {
    withFolder { folder ->
      val path = categoryOutputPath(
        folder,
        destination = null,
        suggestedFileName = "tool.zip",
        url = "fixture://downloads.Example.com/tool.zip",
      )

      assertEquals((folder / "Sites" / "Example" / "tool.zip").toString(), path)
    }
  }

  @Test
  fun execute_categoryFolderNames_madeSafe() = runTest {
    withFolder { folder ->
      val unsafe = DownloadCategory(folder = "Disk?Images / ISO.", extensions = listOf("iso"))
      val source = FixtureSource("ubuntu.iso")
      val config = DownloadConfig(
        defaultDirectory = folder.toString(),
        saveIntervalMs = 60_000,
        categories = listOf(unsafe),
      )

      execution(DownloadRequest("fixture:input"), source, config = config).execute()

      val expected = folder / "Disk_Images" / "ISO" / "ubuntu.iso"
      assertEquals(expected.toString(), source.outputPaths.single())
    }
  }

  @Test
  fun download_contentTypeFromServer_savedInCategoryFolder() = runTest {
    withFolder { folder ->
      val engine = FakeHttpEngine()
      engine.serverInfo = engine.serverInfo.copy(contentType = "application/pdf")
      val dispatcher = StandardTestDispatcher(testScheduler)
      val docs = DownloadCategory(folder = "Documents", mimeTypes = listOf("application/pdf"))
      val ketch = Ketch(
        httpEngine = engine,
        taskStore = InMemoryTaskStore(),
        config = DownloadConfig(
          defaultDirectory = folder.toString(),
          retryCount = 0,
          categories = listOf(docs),
        ),
        dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      )
      try {
        val task = ketch.download(DownloadRequest("https://example.com/report"))
        runCurrent()

        val output = assertIs<DownloadState.Completed>(task.state.value).outputPath
        assertEquals((folder / "Documents" / "report").toString(), output)
        assertTrue(platformFileSystem.exists(output.toPath()))
      } finally {
        ketch.close()
        runCurrent()
      }
    }
  }

  /**
   * Runs a download of [url] whose source suggests [suggestedFileName] and reports
   * [contentType], with [folder] as the default folder and categories for videos and for
   * `example.com`, and returns where it was saved.
   */
  private suspend fun TestScope.categoryOutputPath(
    folder: Path,
    destination: Destination?,
    suggestedFileName: String,
    contentType: String? = null,
    url: String = "fixture:input",
  ): String {
    val categories = listOf(
      DownloadCategory(
        folder = "Video",
        extensions = listOf("mp4", "mkv"),
        mimeTypes = listOf("video/*"),
      ),
      DownloadCategory(folder = "Sites/Example", hosts = listOf("example.com")),
    )
    val config = DownloadConfig(
      defaultDirectory = folder.toString(),
      saveIntervalMs = 60_000,
      categories = categories,
    )
    val source = FixtureSource(suggestedFileName, contentType = contentType)
    execution(DownloadRequest(url, destination = destination), source, config = config).execute()
    return source.outputPaths.single()
  }

  /** Runs a download whose source suggests [suggestedFileName] into [folder]. */
  private suspend fun TestScope.outputPathFor(
    folder: Path,
    suggestedFileName: String?,
    fileNameResolver: FileNameResolver = DefaultFileNameResolver(),
  ): String {
    val source = FixtureSource(suggestedFileName)
    val request = DownloadRequest("fixture:input", destination = Destination("$folder/"))
    execution(request, source, fileNameResolver = fileNameResolver).execute()
    return source.outputPaths.single()
  }

  /**
   * Downloads [url] over a fake HTTP server into [folder] and returns where the file was saved,
   * after checking that nothing was written anywhere else under the test's root folder.
   */
  private suspend fun TestScope.downloadOverHttp(
    folder: Path,
    url: String,
    contentDisposition: String? = null,
  ): Path {
    val engine = FakeHttpEngine()
    engine.serverInfo = engine.serverInfo.copy(contentDisposition = contentDisposition)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = engine,
      taskStore = InMemoryTaskStore(),
      config = DownloadConfig(retryCount = 0),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      val task = ketch.download(DownloadRequest(url, destination = Destination("$folder/")))
      runCurrent()

      val output = assertIs<DownloadState.Completed>(task.state.value).outputPath.toPath()
      val root = checkNotNull(folder.parent?.parent)
      val written = platformFileSystem.listRecursively(root)
        .filter { platformFileSystem.metadata(it).isRegularFile }
        .toList()
      assertEquals(listOf(output), written)
      return output
    } finally {
      ketch.close()
      runCurrent()
    }
  }

  /** Runs [block] with an empty download folder two levels below a temporary root. */
  private suspend fun withFolder(block: suspend (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-dest-${Random.nextLong()}"
    val folder = root / "outer" / "downloads"
    platformFileSystem.createDirectories(folder)
    try {
      block(folder)
    } finally {
      platformFileSystem.deleteRecursively(root)
    }
  }

  private fun TestScope.execution(
    request: DownloadRequest,
    source: DownloadSource,
    fileNameResolver: FileNameResolver = DefaultFileNameResolver(),
    config: DownloadConfig = DownloadConfig(saveIntervalMs = 60_000),
  ): DownloadExecution {
    val now = Clock.System.now()
    val handle = object : TaskHandle {
      override val taskId = "dest-${Random.nextLong()}"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val record = AtomicSaver(
        TaskRecord(taskId, request, state = TaskState.QUEUED, createdAt = now, updatedAt = now),
      ) {}
    }
    val dispatcher = StandardTestDispatcher(testScheduler)
    return DownloadExecution(
      handle = handle,
      sourceResolver = SourceResolver(listOf(source)),
      fileNameResolver = fileNameResolver,
      config = config,
      globalLimiter = SpeedLimiter.Unlimited,
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      openFile = { _, _ -> NoOpFileAccessor },
    )
  }

  /**
   * Suggests [suggestedFileName] and records the output path of every download; the first waits
   * for [firstWaitsFor] before it finishes. Writes nothing.
   */
  private class FixtureSource(
    private val suggestedFileName: String?,
    private val firstWaitsFor: CompletableDeferred<Unit>? = null,
    private val contentType: String? = null,
  ) : DownloadSource {
    val outputPaths = mutableListOf<String>()

    override val type = "fixture"
    override fun canHandle(url: String) = true
    override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
      url = url, sourceType = type, totalBytes = 4, supportsResume = true,
      suggestedFileName = suggestedFileName, maxSegments = 1, contentType = contentType,
    )
    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, "")
    override suspend fun download(context: DownloadContext) {
      outputPaths += context.outputPath.orEmpty()
      context.segments.value = listOf(Segment(0, 0, 3, 4))
      if (outputPaths.size == 1) firstWaitsFor?.await()
    }
    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
  }
}
