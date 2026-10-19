package com.linroid.ketch.updater

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.engine.HttpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Where Ketch finds its releases. */
interface ReleaseFeed {
  /** The newest release, leaving out drafts and pre-releases. */
  suspend fun latest(): Release

  /** The release of [version]. */
  suspend fun release(version: ReleaseVersion): Release

  /**
   * One [page] of the published releases, newest first, starting at 1; an empty list past the
   * last. Pre-releases are included; releases whose tag is not a version, such as the browser
   * extension's, are left out.
   */
  suspend fun releases(page: Int): List<Release>
}

/**
 * The releases after [since] up to [version], newest first, for the notes of everything an
 * update brings: those the pages of [ReleaseFeed.releases] list, read until one reaches [since]
 * or for [MAX_RELEASE_PAGES] pages, and [version], asked for on its own when they do not list
 * it. Only [version] when [since] is `null` or not older.
 */
suspend fun ReleaseFeed.releasesBetween(
  since: ReleaseVersion?,
  version: ReleaseVersion,
): List<Release> {
  if (since == null || since >= version) return listOf(release(version))
  val found = listed(MAX_RELEASE_PAGES) { it > since }.filter { it.version <= version }
  // The pages may miss it, as when it is newer than they reach.
  val all = if (found.none { it.version == version }) found + release(version) else found
  return all.sortedByDescending { it.version }
}

/**
 * Every release from [first] on, newest first, for the history of Ketch's releases: those the
 * pages of [ReleaseFeed.releases] list, read until one reaches a release older than [first] or
 * for [MAX_HISTORY_PAGES] pages. Only lists, never asks for a release by its version, so it works
 * whatever version the app runs, a development build's included.
 */
suspend fun ReleaseFeed.releasesFrom(first: ReleaseVersion): List<Release> =
  listed(MAX_HISTORY_PAGES) { it >= first }.sortedByDescending { it.version }

/**
 * The releases [wanted] keeps that the pages of [ReleaseFeed.releases] list, read until one lists
 * a release [wanted] does not keep or for [maxPages] pages, each version once.
 */
private suspend fun ReleaseFeed.listed(
  maxPages: Int,
  wanted: (ReleaseVersion) -> Boolean,
): List<Release> {
  val found = mutableListOf<Release>()
  for (page in 1..maxPages) {
    val releases = releases(page)
    found += releases.filter { wanted(it.version) }
    if (releases.isEmpty() || releases.any { !wanted(it.version) }) break
  }
  return found.distinctBy { it.version }
}

/** The most pages of [ReleaseFeed.releases] that [releasesBetween] reads. */
const val MAX_RELEASE_PAGES: Int = 5

/**
 * The most pages of [ReleaseFeed.releases] that [releasesFrom] reads: 200 releases from GitHub,
 * pre-releases and the browser extension's included, a few requests of its hourly 60.
 */
const val MAX_HISTORY_PAGES: Int = 20

/**
 * The releases of a GitHub [repository], read from the GitHub REST API. GitHub allows 60 requests
 * an hour from one address without a token, which other programs on a shared address may have
 * used up, so [latest] reads the website first.
 *
 * @param httpEngine makes the HTTP engine of one request, which closes it afterwards.
 * @param webUrl the website, where `releases/latest` redirects to the latest release's tag and
 *   its [SHA256SUMS] file downloads, neither counting against the API's limit.
 * @throws UpdateException from every call when GitHub cannot be reached or has no such release.
 */
class GitHubReleases(
  private val httpEngine: () -> HttpEngine,
  private val repository: String = KETCH_REPOSITORY,
  private val apiUrl: String = "https://api.github.com",
  private val webUrl: String = "https://github.com",
  private val timeout: Duration = 30.seconds,
) : ReleaseFeed {
  private val log = KetchLogger("GitHubReleases")

  /**
   * The latest release, with its files and digests from its [SHA256SUMS] file, and without notes
   * or a publish time; from the API, with them, for releases published before that file or when
   * the website fails.
   */
  override suspend fun latest(): Release =
    latestFromWeb()
      ?: single("releases/latest", missing = "$repository has no published release")

  override suspend fun release(version: ReleaseVersion): Release =
    single("releases/tags/v$version", missing = "$repository has no release v$version")

  override suspend fun releases(page: Int): List<Release> {
    val path = "releases?per_page=$RELEASES_PER_PAGE&page=$page"
    val releases = fetch(path, ListSerializer(GitHubRelease.serializer()), "$repository is gone")
    // Drafts only show to the repository's maintainers.
    return releases.filterNot { it.draft }.mapNotNull { release ->
      ReleaseVersion.parse(release.tagName)?.let { release.toRelease(it) }
    }
  }

  /** Reads the release at [path] of the repository's API; [missing] explains a 404. */
  private suspend fun single(path: String, missing: String): Release {
    val release = fetch(path, GitHubRelease.serializer(), missing)
    val version = ReleaseVersion.parse(release.tagName)
      ?: throw UpdateException("The release tag ${release.tagName} is not a version")
    return release.toRelease(version)
  }

  /**
   * The latest release as the website names it, with the files its [SHA256SUMS] file lists, or
   * `null` when the website fails or the release has no such file (yet).
   */
  private suspend fun latestFromWeb(): Release? {
    val tag = latestTag() ?: return null
    val version = ReleaseVersion.parse(tag) ?: return null.also {
      log.d { "The latest release tag $tag is not a version" }
    }
    val base = "$webUrl/$repository/releases"
    val sums = try {
      get("$base/download/$tag/$SHA256SUMS", WEB_HEADERS, missing = "no $SHA256SUMS")
    } catch (e: UpdateException) {
      log.d { "Reading the release from the API, as $tag has no usable $SHA256SUMS: ${e.message}" }
      return null
    }
    val assets = parseSha256Sums(sums.toString(Charsets.UTF_8.name())).map { (name, sha256) ->
      ReleaseAsset(
        name = name,
        url = "$base/download/$tag/${encodePathSegment(name)}",
        size = 0,
        sha256 = sha256,
      )
    }
    if (assets.isEmpty()) {
      log.d { "Reading the release from the API, as the $SHA256SUMS of $tag lists no file" }
      return null
    }
    return Release(version = version, pageUrl = "$base/tag/$tag", assets = assets)
  }

  /** The tag `releases/latest` redirects to, or `null` when it does not. */
  private suspend fun latestTag(): String? {
    val url = "$webUrl/$repository/releases/latest"
    log.d { "Fetching $url" }
    val engine = httpEngine()
    val target = try {
      withTimeoutOrNull(timeout) {
        engine.request("HEAD", url, WEB_HEADERS).takeIf { it.isSuccess }?.url
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.d { "Reading the release from the API, as $url failed: ${e.describeCauses()}" }
      null
    } finally {
      engine.close()
    }
    val tag = target?.substringAfter(TAG_PATH, missingDelimiterValue = "")
      ?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')
    if (tag.isNullOrEmpty() || '/' in tag) {
      log.d { "Reading the release from the API, as $url led to no release tag" }
      return null
    }
    return tag
  }

  /** Reads [path] of the repository's API as [what]; [missing] explains a 404. */
  private suspend fun <T> fetch(
    path: String,
    what: DeserializationStrategy<T>,
    missing: String,
  ): T {
    val body = get("$apiUrl/repos/$repository/$path", HEADERS, missing)
    return try {
      JSON.decodeFromString(what, body.toString(Charsets.UTF_8.name()))
    } catch (e: SerializationException) {
      throw UpdateException("Couldn't read GitHub's answer: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
      throw UpdateException("Couldn't read GitHub's answer: ${e.message}", e)
    }
  }

  /** The body of [url], at most [MAX_BODY_BYTES]; [missing] explains a 404. */
  private suspend fun get(
    url: String,
    headers: Map<String, String>,
    missing: String,
  ): ByteArrayOutputStream {
    log.d { "Fetching $url" }
    val body = ByteArrayOutputStream()
    val engine = httpEngine()
    try {
      withTimeout(timeout) {
        engine.download(url, range = null, headers = headers) { chunk ->
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
    return body
  }

  private fun GitHubRelease.toRelease(version: ReleaseVersion) = Release(
    version = version,
    pageUrl = htmlUrl,
    assets = assets.map { asset ->
      ReleaseAsset(
        name = asset.name,
        url = asset.url,
        size = asset.size,
        sha256 = asset.digest?.takeIf { it.startsWith(SHA256_PREFIX) }
          ?.removePrefix(SHA256_PREFIX)?.lowercase(),
      )
    },
    notes = body.orEmpty(),
    publishedAt = publishedAt?.let { time ->
      try {
        Instant.parse(time)
      } catch (e: IllegalArgumentException) {
        log.d { "Ignoring the publish time of $tagName: ${e.describeCauses()}" }
        null
      }
    },
  )

  private companion object {
    const val SHA256_PREFIX = "sha256:"
    const val TAG_PATH = "/releases/tag/"

    /** A release takes about 25 KiB with its files, so a page about 250 KiB. */
    const val RELEASES_PER_PAGE = 10
    const val MAX_BODY_BYTES = 4 * 1024 * 1024
    val HEADERS = mapOf(
      "Accept" to "application/vnd.github+json",
      "X-GitHub-Api-Version" to "2022-11-28",
      // GitHub refuses API requests without one.
      "User-Agent" to "Ketch/${KetchApi.VERSION}",
    )
    val WEB_HEADERS = mapOf("User-Agent" to "Ketch/${KetchApi.VERSION}")
    val JSON = Json { ignoreUnknownKeys = true }
  }
}

/** The file of each release listing its other files' SHA-256 digests, uploaded last. */
const val SHA256SUMS: String = "SHA256SUMS"

/**
 * The files and digests of a [SHA256SUMS] file, as `sha256sum` writes them: a lowercase or
 * uppercase hex digest, a space, a space or `*`, and the file name. Other lines are left out.
 */
internal fun parseSha256Sums(text: String): List<Pair<String, String>> =
  text.lineSequence().mapNotNull { line ->
    SHA256_LINE.matchEntire(line.trimEnd('\r'))?.destructured?.let { (digest, name) ->
      name to digest.lowercase()
    }
  }.filter { (name, _) -> '/' !in name && '\\' !in name }.distinctBy { it.first }.toList()

private val SHA256_LINE = Regex("([0-9a-fA-F]{64}) [ *](.+)")

/** [segment] percent-encoded for a URL path: anything but unreserved characters. */
private fun encodePathSegment(segment: String): String = buildString {
  for (byte in segment.encodeToByteArray()) {
    val c = byte.toInt().toChar()
    if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") {
      append(c)
    } else {
      append('%').append("%02X".format(byte.toInt() and 0xFF))
    }
  }
}

/**
 * The fields Ketch reads of GitHub's release object: `body` holds its notes in Markdown and
 * `published_at` an ISO 8601 time, both `null` for a draft.
 */
@Serializable
internal class GitHubRelease(
  @SerialName("tag_name") val tagName: String,
  @SerialName("html_url") val htmlUrl: String,
  val assets: List<GitHubAsset> = emptyList(),
  val body: String? = null,
  @SerialName("published_at") val publishedAt: String? = null,
  val draft: Boolean = false,
)

/** The fields Ketch reads of a release asset; `digest` is `sha256:<hex>`. */
@Serializable
internal class GitHubAsset(
  val name: String,
  @SerialName("browser_download_url") val url: String,
  val size: Long = 0,
  val digest: String? = null,
)
