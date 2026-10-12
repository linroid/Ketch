package com.linroid.ketch.dash

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.media.MediaDownloadHelper
import com.linroid.ketch.core.media.MediaPlan
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Downloads finite, unencrypted DASH streams into one file.
 * Register through `Ketch(additionalSources = listOf(DashDownloadSource(httpEngine)))`.
 * Transfers share the supplied engine; closing this source does not close it.
 */
class DashDownloadSource(httpEngine: HttpEngine) : DownloadSource<Unit> {
  override val type: String = "dash"
  override val stateSerializer: KSerializer<Unit> = Unit.serializer()
  override val features: Set<String> = setOf(KetchFeatures.FINITE_DASH)
  override val previousTypes: Set<String> = setOf("media")
  private val helper = MediaDownloadHelper(httpEngine, type)

  override fun canHandle(input: ResolveInput): Boolean =
    input is ResolveInput.Url &&
      Regex("https?://[^?#]+\\.mpd(?:[?#].*)?", RegexOption.IGNORE_CASE).matches(input.url)

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
    proxy: ProxyConfig,
  ): MediaPlan {
    val manifest = helper.fetchManifest(original, headers, proxy)
    return parseDash(manifest.text, manifest.url)
  }
}
