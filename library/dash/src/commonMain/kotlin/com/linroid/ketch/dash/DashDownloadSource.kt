package com.linroid.ketch.dash

import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.media.MediaDownloadHelper
import com.linroid.ketch.core.media.MediaPlan

/**
 * Downloads finite, unencrypted DASH streams into one file.
 * Register through `Ketch(additionalSources = listOf(DashDownloadSource(httpEngine)))`.
 * Transfers share the supplied engine; closing this source does not close it.
 */
class DashDownloadSource(httpEngine: HttpEngine) : DownloadSource {
  override val type: String = "dash"
  override val features: Set<String> = setOf(KetchFeatures.FINITE_DASH)
  override val previousTypes: Set<String> = setOf("media")
  private val helper = MediaDownloadHelper(httpEngine, type)

  override fun canHandle(url: String): Boolean =
    Regex("https?://[^?#]+\\.mpd(?:[?#].*)?", RegexOption.IGNORE_CASE).matches(url)

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
    val manifest = helper.fetchManifest(original, headers)
    return parseDash(manifest.text, manifest.url)
  }
}
