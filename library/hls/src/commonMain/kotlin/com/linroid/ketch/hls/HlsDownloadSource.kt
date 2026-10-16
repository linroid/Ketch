package com.linroid.ketch.hls

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.media.MediaDownloadHelper
import com.linroid.ketch.core.media.MediaPlan
import com.linroid.ketch.core.media.mediaHeaders
import com.linroid.ketch.core.media.mediaUrl
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Downloads finite, unencrypted HLS streams into one file.
 * Register through `Ketch(additionalSources = listOf(HlsDownloadSource(httpEngine)))`.
 * Transfers share the supplied engine; closing this source does not close it.
 */
class HlsDownloadSource(httpEngine: HttpEngine) : DownloadSource<Unit> {
  override val type: String = "hls"
  override val stateSerializer: KSerializer<Unit> = Unit.serializer()
  override val features: Set<String> = setOf(KetchFeatures.FINITE_HLS)
  override val previousTypes: Set<String> = setOf("media")
  private val helper = MediaDownloadHelper(httpEngine, type)

  override fun canHandle(input: ResolveInput): Boolean =
    input is ResolveInput.Url &&
      Regex("https?://[^?#]+\\.m3u8(?:[?#].*)?", RegexOption.IGNORE_CASE).matches(input.url)

  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
    val (url, headers) = input as ResolveInput.Url
    return helper.resolve(url, plan(url, headers, config.proxy))
  }

  /** Media transfers restart from zero; no byte offset can identify a refreshed manifest part. */
  override suspend fun download(context: DownloadContext, state: Unit?) {
    helper.download(context, plan(context.url, context.headers, context.config.proxy))
  }

  private suspend fun plan(
    original: String,
    headers: Map<String, String>,
    proxy: Proxy,
  ): MediaPlan {
    var url = mediaUrl("hls", original, original)
    var requestHeaders = headers
    val visited = mutableSetOf<String>()
    repeat(4) {
      mediaRequire(visited.add(url), "HLS playlist cycle")
      val manifest = helper.fetchManifest(url, requestHeaders, proxy)
      val playlist = parseHls(manifest.text, manifest.url)
      playlist.plan?.let { return it }
      val next = playlist.variants.maxBy { it.bandwidth }.url
      requestHeaders = mediaHeaders(manifest.url, next, manifest.headers)
      url = next
    }
    throw KetchError.SourceError(type, detail = "Too many nested HLS playlists")
  }
}
