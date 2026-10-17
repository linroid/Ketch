# HLS and DASH streams

Ketch can save a finished HLS (`.m3u8`) or DASH (`.mpd`) stream as one media file, by downloading
its segments and joining them in order. It lets you choose the quality, merges separate audio and
video tracks into one MP4, and decrypts AES-128 HLS streams. This page shows how to download a
stream in the apps, the CLI and the SDK, and lists which streams work.

## Download a stream

### In the apps

Paste the `http://` or `https://` URL of the `.m3u8` or `.mpd` file into the Add sheet. The
[browser extension](../app/browser-extension/README.md) can also send playlists it finds on a page
to devices that support them.

### With the CLI

```bash
ketch 'https://example.com/video/index.m3u8'
```

Quote the URL so the shell keeps its query string; signed URLs keep their query parameters.

To see a stream's qualities and download another one:

```bash
ketch --list-files 'https://example.com/video/index.m3u8'
```

```bash
ketch 'https://example.com/video/index.m3u8' --files 1
```

### Choose the quality

A stream that offers several qualities (HLS variants, DASH representations) lists them as a single
choice, best first, such as `1080p · 5.1 Mbps`. Ketch downloads the best one unless you choose
another: in the apps, open the task's **Files** tab in the inspector and pick a quality; in the
SDK, pass its ID in `DownloadRequest.selectedFileIds` or call `DownloadTask.selectFiles`. Choosing
another quality while the task downloads starts it over with that one. "Best" is the highest
declared bandwidth among the variants with video; audio-only variants come last.

### What you get

- The file holds the media, not the playlist text. Ketch names it `.ts`, `.aac`, `.mp3` or `.mp4`
  to match the segments. Save to a folder to keep that name.
- A file destination is used as given: naming it `.mp4` does not convert a transport stream to
  MP4. Ketch joins segments; it never transcodes.
- For an HLS master playlist, Ketch downloads the chosen variant, the best by default. When that
  variant plays with a separate audio rendition, Ketch takes the group's default rendition (else
  the first) and merges it in. For DASH it downloads the chosen video representation and the best
  representation of the main audio adaptation set (`Role` `main`, else the first).
- Merged tracks are written as one fragmented MP4 (`.mp4`) with the video as track 1 and the
  audio as track 2, their fragments interleaved by start time. Ketch rewrites box headers only; it
  never decodes or re-encodes samples.
- AES-128 HLS segments (`EXT-X-KEY:METHOD=AES-128`) are decrypted as they arrive; the file on disk
  is never encrypted. The key is fetched once per download with the stream's headers, scoped to
  its origin like every other request.
- The total size is unknown until the download completes, so progress shows bytes received.
- Pausing, or a retry after an error, starts the stream over from the beginning (see
  [why](#how-it-works)).
- Speed limits, proxies and checksums apply as for other HTTP downloads.

## Supported streams

| Format | Supported |
| --- | --- |
| HLS media playlists | Finished playlists (`EXT-X-ENDLIST`) of MPEG-TS, packed AAC or MP3, or fragmented MP4 with one `EXT-X-MAP`. Byte ranges are supported, including implicit offsets. |
| HLS master playlists | The chosen variant (the best by default), up to four nested playlists deep, with a separate fragmented MP4 audio rendition merged in. |
| HLS encryption | `METHOD=AES-128` with the `identity` key format, with an explicit `IV` or the media sequence number. An encrypted `EXT-X-MAP` needs an `IV`. Keys may change between segments. |
| DASH | Static MP4 manifests with one period: the chosen video representation (the best by default) and the main audio adaptation set, or one audio adaptation set alone. `SegmentList`, and `SegmentTemplate` with a finite duration or bounded `SegmentTimeline`, including initialization segments and byte ranges. Text and image adaptation sets are ignored. |

Not supported:

- live streams;
- DRM: HLS `SAMPLE-AES` and key formats other than `identity` (FairPlay, Widevine, PlayReady),
  and DASH `ContentProtection`;
- separate audio for MPEG-TS, AAC or MP3 HLS variants: only fragmented MP4 tracks are merged;
- choosing the audio language or a second audio track: the default rendition or main adaptation
  set is used, and subtitles are not downloaded;
- HLS discontinuities and changing initialization segments;
- DASH `SegmentBase` and multiple periods;
- other containers.

A stream Ketch cannot handle fails before any segment is written, and the playlist itself is never
reported as a finished download. A single selected stream may contain both audio and video; an
audio-only stream stays audio-only. A merged track whose initialization segment holds more than
one track, or whose fragments are not fragmented MP4, fails the download.

### Limits

- Manifests up to 1 MiB and 10,000 segments.
- XML depth and node counts are bounded, and DASH manifests with DTDs or external entities are
  rejected.
- Names in the playlist are never used as file paths.
- A segment or nested playlist on another origin gets only `User-Agent`, `Accept`,
  `Accept-Language` and `Accept-Encoding`, never credentials or cookies. HTTPS references cannot
  point to HTTP.

## Platform support

The apps (desktop, Android, iOS) and the CLI include both sources; the web app downloads streams
through the device it controls. In the SDK, the `hls` and `dash` modules run on Android, iOS, JVM,
JS (Node.js) and WasmWasi, and need no external program.

## For developers

Add either module or both, and register each source with the same HTTP engine you give `Ketch`:

```kotlin
implementation("com.linroid.ketch:hls:<latest-version>")
implementation("com.linroid.ketch:dash:<latest-version>")
```

```kotlin
import com.linroid.ketch.dash.DashDownloadSource
import com.linroid.ketch.hls.HlsDownloadSource

val httpEngine = KtorHttpEngine()
val ketch = Ketch(
  httpEngine = httpEngine,
  additionalSources = listOf(HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)),
)
ketch.start()
val task = ketch.download(
  DownloadRequest(
    url = "https://example.com/video/index.m3u8",
    destination = Destination("downloads/"),
  ),
)
```

`KtorHttpEngine` covers Android, iOS and the JVM; Node.js and WasmWasi need an `HttpEngine` of your
own. Without these modules, Ketch downloads `.m3u8` and `.mpd` URLs as ordinary files.

- **Which URLs**: `http` and `https` URLs whose path ends in `.m3u8` or `.mpd`, ignoring case, query
  and fragment. Sources are checked in registration order, before the built-in HTTP source.
- **Feature flags**: an instance with the HLS source lists `hls.finite` in
  `KetchStatus.features`, one with the DASH source `dash.finite`, and one with both also
  `media.finite` for older clients. Clients such as the browser extension check these before they
  send a playlist. Both sources also list `download.fileSelection` and
  `download.awaitFileSelection`, as their variants are a single choice.
- **Variants**: `ResolvedSource.files` lists them best first with `selectionMode`
  `FileSelectionMode.SINGLE` (empty for a stream with one), each with `bandwidth` and, when the
  manifest says, `resolution`, `codecs`, `frameRate` and `audio` in its metadata. IDs are short
  hashes of what identifies each variant in the manifest (an HLS variant's URL path without its
  query, bandwidth, resolution, codecs and audio group; a DASH representation's and its adaptation
  set's `id`, bandwidth and size), so they survive a refreshed or re-signed manifest, also on a
  resumed download. A chosen ID the manifest no longer lists fails the download rather than
  download another quality. Sizes are unknown (`-1`).
- **Containers**: the file is named for the best variant's container. Choosing a variant in
  another container (a TS variant in a master whose best variant is fragmented MP4) fails rather
  than write it under the wrong extension, unless the destination is a file you named.
- **Redirects**: relative references resolve against the manifest's final URL. A custom
  `HttpEngine` that follows redirects must override `downloadResource` and return the final URL.
  `KtorHttpEngine` and the network engines do.
- **Older tasks**: tasks saved with the former `media` source type are restored by the HLS or DASH
  source that matches their URL.

## How it works

Both sources share core helpers (in `com.linroid.ketch.core.media`) that fetch manifests within
their limits, validate URLs, scope headers per origin and download segments one after another into
the output file. Each module owns its parser and its tests. The helpers also hold the parts both
formats need: a pure Kotlin AES-128-CBC decryptor (`Aes128CbcDecryptor`, so it runs on every
target) and the fragmented MP4 track merger (`mergeTracks`, `Mp4Tracks.kt`), which joins two
initialization segments into one movie and rewrites each fragment's track ID, sequence number and
any absolute base data offset as it streams through, dropping `styp`, `sidx` and other boxes
between fragments. Box sizes never change, so the offsets within each fragment stay valid.

A download cannot resume partway: the manifest is fetched again for every run, and a refreshed
manifest gives no reliable way to match a byte offset to a segment. So pausing or retrying restarts
the transfer, and the source keeps no resume state.

The parser tests cover ranges, URL resolution, manifest limits, timeline expansion, credential
scoping, variants, renditions, keys and unsupported formats; the core tests check the decryptor
against NIST and OpenSSL vectors and the merger against synthetic boxes. A Ktor integration test
downloads self-generated H.264 and AAC fixtures through a redirect and checks the assembled bytes,
names and size, and for a DASH stream with separate tracks and an AES-128 HLS stream with a
separate audio rendition that the merged file holds both tracks and every fragment's decrypted
media in order. It runs offline with the normal JVM tests; FFmpeg is not needed.

## See also

- [Proxies](proxy.md), which HLS and DASH downloads follow
- [Logging](logging.md)
