package com.linroid.ketch.core.media

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.sanitizeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer

/** Shared bounded manifest fetching and sequential transfer for finite media sources. */
class MediaDownloadHelper(private val http: HttpEngine, private val type: String) {
  /** Builds metadata for an already validated plan. */
  fun resolve(url: String, plan: MediaPlan): ResolvedSource {
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

  /** Writes a validated plan in order, truncating partial output and applying task throttling. */
  suspend fun download(context: DownloadContext, plan: MediaPlan) {
    mediaRequire(context.request.selectedFileIds.isEmpty(),
      "Media track selection is not supported")
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

  /** Media transfers restart from zero; no byte offset can identify a refreshed manifest part. */
  fun buildResumeState(): SourceResumeState = SourceResumeState(type, "{}")

  /** Fetches at most 1 MiB and retains the final URL and headers safe for that origin. */
  suspend fun fetchManifest(url: String, headers: Map<String, String>): MediaManifest {
    val checked = mediaUrl(type, url, url)
    val buffer = Buffer()
    val effective = http.downloadResource(checked, headers) { bytes ->
      mediaRequire(buffer.size + bytes.size <= 1024 * 1024, "Media manifest exceeds 1 MiB")
      buffer.write(bytes)
    }
    val base = mediaUrl(type, checked, effective)
    return MediaManifest(buffer.readUtf8(), base, mediaHeaders(checked, base, headers))
  }

  private fun mediaRequire(value: Boolean, message: String) {
    if (!value) throw KetchError.SourceError(type, detail = message)
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
