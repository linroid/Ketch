package com.linroid.ketch.updater

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.engine.HttpEngine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where Ketch finds its releases. */
interface ReleaseFeed {
  /** The newest release, leaving out drafts and pre-releases. */
  suspend fun latest(): Release

  /** The release of [version]. */
  suspend fun release(version: ReleaseVersion): Release
}

/**
 * The releases of a GitHub [repository], read from the GitHub REST API. GitHub allows 60 requests
 * an hour from one address without a token, plenty for a check a day.
 *
 * @param httpEngine makes the HTTP engine of one request, which closes it afterwards.
 * @throws UpdateException from every call when GitHub cannot be reached or has no such release.
 */
class GitHubReleases(
  private val httpEngine: () -> HttpEngine,
  private val repository: String = KETCH_REPOSITORY,
  private val apiUrl: String = "https://api.github.com",
  private val timeout: Duration = 30.seconds,
) : ReleaseFeed {
  private val log = KetchLogger("GitHubReleases")

  override suspend fun latest(): Release =
    fetch("releases/latest", missing = "$repository has no published release")

  override suspend fun release(version: ReleaseVersion): Release =
    fetch("releases/tags/v$version", missing = "$repository has no release v$version")

  /** Reads the release at [path] of the repository's API; [missing] explains a 404. */
  private suspend fun fetch(path: String, missing: String): Release {
    val url = "$apiUrl/repos/$repository/$path"
    log.d { "Fetching $url" }
    val body = ByteArrayOutputStream()
    val engine = httpEngine()
    try {
      withTimeout(timeout) {
        engine.download(url, range = null, headers = HEADERS) { chunk ->
          if (body.size() + chunk.size > MAX_BODY_BYTES) {
            throw UpdateException("GitHub's answer is larger than $MAX_BODY_BYTES bytes")
          }
          body.write(chunk)
        }
      }
    } catch (e: TimeoutCancellationException) {
      throw UpdateException("GitHub didn't answer within $timeout", e)
    } catch (e: KetchError.Http) {
      throw when (e.code) {
        404 -> UpdateException(missing, e)
        403, 429 -> UpdateException(
          "GitHub refused the request (HTTP ${e.code}), likely its hourly limit; try again later",
          e,
        )
        else -> UpdateException("GitHub answered HTTP ${e.code}", e)
      }
    } catch (e: KetchError) {
      // The engine wraps what the body callback throws.
      (e.cause as? UpdateException)?.let { throw it }
      throw UpdateException("Couldn't reach GitHub: ${e.describeCauses()}", e)
    } finally {
      engine.close()
    }
    val release = try {
      JSON.decodeFromString(GitHubRelease.serializer(), body.toString(Charsets.UTF_8.name()))
    } catch (e: SerializationException) {
      throw UpdateException("Couldn't read GitHub's answer: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
      throw UpdateException("Couldn't read GitHub's answer: ${e.message}", e)
    }
    val version = ReleaseVersion.parse(release.tagName)
      ?: throw UpdateException("The release tag ${release.tagName} is not a version")
    return Release(
      version = version,
      pageUrl = release.htmlUrl,
      assets = release.assets.map { asset ->
        ReleaseAsset(
          name = asset.name,
          url = asset.url,
          size = asset.size,
          sha256 = asset.digest?.takeIf { it.startsWith(SHA256_PREFIX) }
            ?.removePrefix(SHA256_PREFIX)?.lowercase(),
        )
      },
    )
  }

  private companion object {
    const val SHA256_PREFIX = "sha256:"
    const val MAX_BODY_BYTES = 4 * 1024 * 1024
    val HEADERS = mapOf(
      "Accept" to "application/vnd.github+json",
      "X-GitHub-Api-Version" to "2022-11-28",
      // GitHub refuses API requests without one.
      "User-Agent" to "Ketch/${KetchApi.VERSION}",
    )
    val JSON = Json { ignoreUnknownKeys = true }
  }
}

/** The fields Ketch reads of GitHub's release object. */
@Serializable
internal class GitHubRelease(
  @SerialName("tag_name") val tagName: String,
  @SerialName("html_url") val htmlUrl: String,
  val assets: List<GitHubAsset> = emptyList(),
)

/** The fields Ketch reads of a release asset; `digest` is `sha256:<hex>`. */
@Serializable
internal class GitHubAsset(
  val name: String,
  @SerialName("browser_download_url") val url: String,
  val size: Long = 0,
  val digest: String? = null,
)
