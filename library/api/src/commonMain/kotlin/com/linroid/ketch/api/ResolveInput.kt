package com.linroid.ketch.api

/** What [KetchApi.resolve] probes: a URL the instance fetches, or content the caller holds. */
sealed interface ResolveInput {
  /**
   * A URL the instance fetches, as it does a download's: an HTTP(S) or FTP link, a magnet, or a
   * file on the instance's own disk.
   *
   * @property headers request headers for HTTP sources; other sources may ignore them
   */
  data class Url(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
  ) : ResolveInput

  /**
   * File content the caller holds, such as a picked `.torrent` file; remote instances accept at
   * most [MAX_CONTENT_BYTES]. The result does not keep it, so download it by passing the result in
   * [DownloadRequest.resolvedSource], with its [ResolvedSource.url] as [DownloadRequest.url].
   *
   * @property fileName the original file name, whose extension can identify the content
   */
  class Content(
    val bytes: ByteArray,
    val fileName: String? = null,
  ) : ResolveInput {
    override fun equals(other: Any?): Boolean =
      other is Content && fileName == other.fileName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "Content(fileName=$fileName, size=${bytes.size})"

    companion object {
      /** The most content a remote instance accepts. */
      const val MAX_CONTENT_BYTES: Long = 16L * 1024 * 1024
    }
  }
}

/** [KetchApi.resolve] for [url], with [headers] for HTTP sources. */
suspend fun KetchApi.resolve(
  url: String,
  headers: Map<String, String> = emptyMap(),
): ResolvedSource = resolve(ResolveInput.Url(url, headers))

/** [KetchApi.resolve] for file [content] the caller holds, named [fileName]. */
suspend fun KetchApi.resolve(
  content: ByteArray,
  fileName: String? = null,
): ResolvedSource = resolve(ResolveInput.Content(content, fileName))
