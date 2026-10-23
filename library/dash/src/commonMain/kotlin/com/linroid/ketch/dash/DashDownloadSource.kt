package com.linroid.ketch.dash

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.TransferWatchdog
import com.linroid.ketch.core.media.MediaDownloadHelper
import com.linroid.ketch.core.media.MediaVariant
import com.linroid.ketch.core.media.mediaVariantIds
import com.linroid.ketch.core.media.mediaVariantNames
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Downloads finite, unencrypted DASH streams into one file: the representation the task selects
 * (the highest bandwidth by default), with a separate audio adaptation set merged in.
 * Register through `Ketch(additionalSources = listOf(DashDownloadSource(httpEngine)))`.
 * Transfers share the supplied engine; closing this source does not close it.
 */
class DashDownloadSource(httpEngine: HttpEngine) : DownloadSource<Unit> {
  override val type: String = "dash"
  override val stateSerializer: KSerializer<Unit> = Unit.serializer()
  override val features: Set<String> = setOf(
    KetchFeatures.FINITE_DASH,
    KetchFeatures.FILE_SELECTION,
    KetchFeatures.AWAIT_FILE_SELECTION,
  )
  override val previousTypes: Set<String> = setOf("media")
  private val helper = MediaDownloadHelper(httpEngine, type)

  override fun canHandle(input: ResolveInput): Boolean =
    input is ResolveInput.Url &&
      Regex("https?://[^?#]+\\.mpd(?:[?#].*)?", RegexOption.IGNORE_CASE).matches(input.url)

  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
    val (url, headers) = input as ResolveInput.Url
    val stream = stream(url, headers, config.proxy, TransferWatchdog.of(config))
    val variants = variants(stream)
    // The plan's container and tracks are all a resolution needs: no segment index is fetched.
    return helper.resolve(url, stream.plan(), variants, variants.firstOrNull()?.id)
  }

  override suspend fun planSelection(request: SelectionRequest<Unit>): SelectionPlan =
    helper.planSelection(request)

  /**
   * Media transfers restart from zero; no byte offset can identify a refreshed manifest part. A
   * new representation selected while it runs restarts it with that one.
   */
  override suspend fun download(context: DownloadContext, state: Unit?) {
    helper.downloadFollowingSelection(context) { selected ->
      val stream = stream(context.url, context.headers, context.config.proxy, context.watchdog)
      val variants = variants(stream)
      val chosen = helper.chosenVariant(context, selected, variants)
      val index = chosen?.let(variants::indexOf) ?: 0
      val indexes = stream.indexesNeeded(index).associateWith { file ->
        helper.fetchRange(
          streamUrl = context.url,
          url = file.url,
          range = file.indexRange!!,
          headers = context.headers,
          proxy = context.config.proxy,
          watchdog = context.watchdog,
        )
      }
      stream.plan(index, indexes)
    }
  }

  /** The presentation at [original], its manifest fetched under [watchdog]. */
  private suspend fun stream(
    original: String,
    headers: Map<String, String>,
    proxy: Proxy,
    watchdog: TransferWatchdog,
  ): DashStream {
    val manifest = helper.fetchManifest(original, headers, proxy, watchdog)
    return parseDashStream(manifest.text, manifest.url)
  }

  private fun variants(stream: DashStream): List<MediaVariant> {
    val names = mediaVariantNames(
      stream.variants.map { it.height },
      stream.variants.map { it.bandwidth },
      stream.variants.map { it.metadata["codecs"] },
    )
    val ids = mediaVariantIds(stream.variants.map { it.key })
    return stream.variants.mapIndexed { index, variant ->
      MediaVariant(ids[index], names[index], variant.bandwidth, variant.metadata)
    }
  }
}
