package com.linroid.ketch.core.media

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.sanitizeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer

/** Downloads finite, unencrypted single-stream HLS and DASH without an external process. */
internal class MediaDownloadSource(private val http: HttpEngine) : DownloadSource {
  override val type: String = "media"

  override fun canHandle(url: String): Boolean =
    Regex("https?://[^?#]+\\.(m3u8|mpd)(?:[?#].*)?", RegexOption.IGNORE_CASE).matches(url)

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource {
    val plan = plan(url, properties)
    val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
      .substringBeforeLast('.').ifBlank { "media" }
    return ResolvedSource(
      url = url,
      sourceType = type,
      totalBytes = -1,
      supportsResume = false,
      suggestedFileName = sanitizeFileName("$name.${plan.extension}"),
      maxSegments = 1,
      metadata = mapOf("media.extension" to plan.extension),
    )
  }

  override suspend fun download(context: DownloadContext) {
    mediaRequire(context.request.selectedFileIds.isEmpty(),
      "Media track selection is not supported")
    val plan = plan(context.url, context.headers)
    val expected = context.preResolved?.metadata?.get("media.extension")
    mediaRequire(expected == null || expected == plan.extension,
      "Media format changed; add it again")
    context.segments.value = emptyList()
    disk { context.fileAccessor.preallocate(0) }
    var written = 0L
    context.onProgress(0, 0)
    for (part in plan.parts) {
      currentCoroutineContext().ensureActive()
      var received = 0L
      val expectedBytes = part.range?.let { it.last - it.first + 1 }
      val headers = mediaHeaders(context.url, part.url, context.headers)
      http.download(part.url, part.range, headers) { data ->
        currentCoroutineContext().ensureActive()
        mediaRequire(expectedBytes == null || data.size <= expectedBytes - received,
          "Media server returned a different byte range")
        mediaRequire(data.size <= Long.MAX_VALUE - written, "Media output is too large")
        context.throttle(data.size)
        disk { context.fileAccessor.writeAt(written, data) }
        written += data.size
        received += data.size
        context.onProgress(written, 0)
      }
      mediaRequire(received > 0 && (expectedBytes == null || received == expectedBytes),
        "Media segment is empty or incomplete")
    }
    context.onProgress(written, written)
  }

  override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) {
    // Byte offsets cannot safely identify a segment in a refreshed manifest.
    download(context)
  }

  override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState =
    SourceResumeState(type, "{}")

  private suspend fun plan(original: String, headers: Map<String, String>): MediaPlan {
    var url = mediaUrl(original, original)
    var requestHeaders = headers
    val visited = mutableSetOf<String>()
    repeat(4) {
      mediaRequire(visited.add(url), "HLS playlist cycle")
      val buffer = Buffer()
      val effective = http.downloadResource(url, requestHeaders) { bytes ->
        mediaRequire(buffer.size + bytes.size <= 1024 * 1024, "Media manifest exceeds 1 MiB")
        buffer.write(bytes)
      }
      val base = mediaUrl(url, effective)
      requestHeaders = mediaHeaders(url, base, requestHeaders)
      val text = buffer.readUtf8()
      if (text.removePrefix("\uFEFF").trimStart().startsWith('<')) return parseDash(text, base)
      val playlist = parseHls(text, base)
      playlist.plan?.let { return it }
      val next = playlist.variants.maxBy { it.bandwidth }.url
      requestHeaders = mediaHeaders(base, next, requestHeaders)
      url = next
    }
    throw KetchError.SourceError(type, detail = "Too many nested HLS playlists")
  }

  private suspend fun disk(action: suspend () -> Unit) {
    try {
      action()
    } catch (e: CancellationException) {
      throw e
    } catch (e: KetchError) {
      throw e
    } catch (e: Exception) {
      throw KetchError.Disk(e)
    }
  }
}
