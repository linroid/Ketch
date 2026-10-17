package com.linroid.ketch.hls

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.media.MediaDownloadHelper
import com.linroid.ketch.core.media.MediaManifest
import com.linroid.ketch.core.media.MediaPlan
import com.linroid.ketch.core.media.MediaVariant
import com.linroid.ketch.core.media.mediaHeaders
import com.linroid.ketch.core.media.mediaUrl
import com.linroid.ketch.core.media.mediaVariantIds
import com.linroid.ketch.core.media.mediaVariantNames
import com.linroid.ketch.core.media.mergeTracks
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Downloads finite HLS streams into one file: unencrypted or AES-128 encrypted, in the variant
 * the task selects (the highest bandwidth by default), with a separate fragmented MP4 audio
 * rendition merged in.
 * Register through `Ketch(additionalSources = listOf(HlsDownloadSource(httpEngine)))`.
 * Transfers share the supplied engine; closing this source does not close it.
 */
class HlsDownloadSource(httpEngine: HttpEngine) : DownloadSource<Unit> {
  override val type: String = "hls"
  override val stateSerializer: KSerializer<Unit> = Unit.serializer()
  override val features: Set<String> = setOf(
    KetchFeatures.FINITE_HLS,
    KetchFeatures.FILE_SELECTION,
    KetchFeatures.AWAIT_FILE_SELECTION,
  )
  override val previousTypes: Set<String> = setOf("media")
  private val helper = MediaDownloadHelper(httpEngine, type)

  override fun canHandle(input: ResolveInput): Boolean =
    input is ResolveInput.Url &&
      Regex("https?://[^?#]+\\.m3u8(?:[?#].*)?", RegexOption.IGNORE_CASE).matches(input.url)

  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
    val (url, headers) = input as ResolveInput.Url
    val choice = plan(url, headers, config.proxy) { variants -> variants.firstOrNull() }
    return helper.resolve(url, choice.plan, choice.variants, choice.chosen?.id)
  }

  override suspend fun planSelection(request: SelectionRequest<Unit>): SelectionPlan =
    helper.planSelection(request)

  /**
   * Media transfers restart from zero; no byte offset can identify a refreshed manifest part. A
   * new variant selected while it runs restarts it with that variant.
   */
  override suspend fun download(context: DownloadContext, state: Unit?) {
    helper.downloadFollowingSelection(context) { selected ->
      plan(context.url, context.headers, context.config.proxy) { variants ->
        helper.chosenVariant(context, selected, variants)
      }.plan
    }
  }

  private class Choice(
    val plan: MediaPlan,
    val variants: List<MediaVariant>,
    val chosen: MediaVariant?,
  )

  /** Plans the variant [choose] picks of a master playlist's variants, best first. */
  private suspend fun plan(
    original: String,
    headers: Map<String, String>,
    proxy: Proxy,
    choose: (List<MediaVariant>) -> MediaVariant?,
  ): Choice {
    val top = helper.fetchManifest(mediaUrl("hls", original, original), headers, proxy)
    val playlist = parseHls(top.text, top.url)
    playlist.plan?.let { return Choice(it, emptyList(), choose(emptyList())) }
    // Audio-only variants rank after those with video, whatever their bandwidth.
    val ranked = playlist.variants.sortedWith(
      compareBy<HlsVariant> { it.audioOnly }.thenByDescending { it.bandwidth }
    )
    val variants = variants(ranked, playlist)
    val chosen = choose(variants) ?: variants.first()
    val variant = ranked[variants.indexOf(chosen)]
    val video = mediaPlan(top, variant.url, depth = 1, proxy)
    val audio = playlist.audioFor(variant)?.url?.takeIf { it != variant.url }
      ?.let { mediaPlan(top, it, depth = MAX_DEPTH - 1, proxy) }
      ?: return Choice(video, variants, chosen)
    mediaRequire(video.parts.first().init && audio.parts.first().init,
      "Separate HLS audio is supported for fragmented MP4 streams only")
    return Choice(mergeTracks(video, audio), variants, chosen)
  }

  /** The media playlist at [url], referenced from [from], taking the best of nested masters. */
  private suspend fun mediaPlan(
    from: MediaManifest,
    url: String,
    depth: Int,
    proxy: Proxy,
  ): MediaPlan {
    var next = url
    var requestHeaders = mediaHeaders(from.url, url, from.headers)
    val visited = mutableSetOf(from.url)
    for (level in depth until MAX_DEPTH) {
      mediaRequire(visited.add(next), "HLS playlist cycle")
      val manifest = helper.fetchManifest(next, requestHeaders, proxy)
      val playlist = parseHls(manifest.text, manifest.url)
      playlist.plan?.let { return it }
      next = playlist.variants.maxBy { it.bandwidth }.url
      requestHeaders = mediaHeaders(manifest.url, next, manifest.headers)
    }
    throw KetchError.SourceError(type, detail = "Too many nested HLS playlists")
  }

  private fun variants(ranked: List<HlsVariant>, playlist: HlsPlaylist): List<MediaVariant> {
    val heights = ranked.map { variant ->
      variant.attributes["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull()
    }
    val names = mediaVariantNames(heights, ranked.map { it.bandwidth })
    val ids = mediaVariantIds(ranked.map { variant ->
      listOf(variant.url.substringBefore('?'), variant.bandwidth.toString(),
        variant.attributes["RESOLUTION"], variant.attributes["CODECS"], variant.audioGroup)
        .joinToString("|")
    })
    return ranked.mapIndexed { index, variant ->
      MediaVariant(
        id = ids[index],
        name = names[index],
        bandwidth = variant.bandwidth,
        metadata = buildMap {
          variant.attributes["RESOLUTION"]?.let { put("resolution", it) }
          variant.attributes["CODECS"]?.let { put("codecs", it) }
          variant.attributes["FRAME-RATE"]?.let { put("frameRate", it) }
          playlist.audioFor(variant)?.let { audio ->
            (audio.name ?: audio.language)?.let { put("audio", it) }
          }
        },
      )
    }
  }

  private companion object {
    const val MAX_DEPTH = 4
  }
}
