package com.linroid.ketch.hls

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.media.MediaDownloadHelper
import com.linroid.ketch.core.media.MediaPlan
import com.linroid.ketch.core.media.mediaHeaders
import com.linroid.ketch.core.media.mediaUrl

/**
 * Downloads finite, unencrypted HLS streams into one file.
 * Register through `Ketch(additionalSources = listOf(HlsDownloadSource(httpEngine)))`.
 * Transfers share the supplied engine; closing this source does not close it.
 */
class HlsDownloadSource(httpEngine: HttpEngine) : DownloadSource {
  override val type: String = "hls"
  override val features: Set<String> = setOf(KetchFeatures.FINITE_HLS)
  override val previousTypes: Set<String> = setOf("media")
  private val helper = MediaDownloadHelper(httpEngine, type)

  override fun canHandle(url: String): Boolean =
    Regex("https?://[^?#]+\\.m3u8(?:[?#].*)?", RegexOption.IGNORE_CASE).matches(url)

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    helper.resolve(url, plan(url, properties))

  override suspend fun download(context: DownloadContext) {
    helper.download(context, plan(context.url, context.headers))
  }

  override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) {
    download(context)
  }

  override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState =
    helper.buildResumeState()

  private suspend fun plan(original: String, headers: Map<String, String>): MediaPlan {
    var url = mediaUrl(original, original)
    var requestHeaders = headers
    val visited = mutableSetOf<String>()
    repeat(4) {
      mediaRequire(visited.add(url), "HLS playlist cycle")
      val manifest = helper.fetchManifest(url, requestHeaders)
      val playlist = parseHls(manifest.text, manifest.url)
      playlist.plan?.let { return it }
      val next = playlist.variants.maxBy { it.bandwidth }.url
      requestHeaders = mediaHeaders(manifest.url, next, manifest.headers)
      url = next
    }
    throw KetchError.SourceError(type, detail = "Too many nested HLS playlists")
  }
}
