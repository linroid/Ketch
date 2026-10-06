# Finite media downloads

The optional `hls` and `dash` libraries recognize HTTP(S) URLs ending in `.m3u8` and `.mpd`,
respectively (before query parameters), and download supported segments into one file. The apps
and CLI register both sources. SDK consumers can install either source independently; core alone
handles these URLs as ordinary HTTP files. Signed URL query strings are preserved.

The browser extension's page resource picker checks `hls.finite` or `dash.finite` before submitting
playlists. Instances with both sources also advertise `media.finite` for older clients.

## Download an m3u8 playlist

Paste the HTTP(S) `.m3u8` URL into the app's Add sheet, or pass it to the CLI:

```bash
ketch 'https://example.com/video/index.m3u8'
```

Use a destination directory to keep the media extension chosen by Ketch. An explicit file
destination is used as given; naming it `.mp4` does not convert a transport stream to MP4.

For the SDK, add either or both dependencies (Android, iOS, JVM, JS/Node.js and WasmWasi):

```kotlin
implementation("com.linroid.ketch:hls:<latest-version>")
implementation("com.linroid.ketch:dash:<latest-version>")
```

Register the sources with the same HTTP engine supplied to `Ketch`. No external executable is
required. The example uses `KtorHttpEngine` on Android, iOS or JVM; Node.js and WasmWasi require
a custom `HttpEngine`.

```kotlin
import com.linroid.ketch.hls.HlsDownloadSource
import com.linroid.ketch.dash.DashDownloadSource

val httpEngine = KtorHttpEngine()
val ketch = Ketch(
  httpEngine = httpEngine,
  additionalSources = listOf(HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)),
)
ketch.start()
val task = ketch.download(DownloadRequest(
  url = "https://example.com/video/index.m3u8",
  destination = Destination("downloads/"),
))
```

The playlist must be finite (`EXT-X-ENDLIST`). A master playlist automatically selects its
highest-bandwidth variant. The result contains the media bytes, rather than the playlist text.
Progress has an unknown total until completion, and pause/resume restarts the transfer.

## Supported formats and limits

Supported formats:

- HLS media playlists with `EXT-X-ENDLIST`: MPEG-TS, packed AAC/MP3, or fragmented MP4 with
  one `EXT-X-MAP`. Byte ranges, including consecutive implicit offsets, are supported.
- HLS master playlists: the highest-bandwidth variant is chosen. External audio/video
  renditions are rejected because combining separate tracks requires a merger.
- Static MP4 DASH with one period, one adaptation set and one chosen representation (highest
  bandwidth). `SegmentList` and `SegmentTemplate` with a finite duration or bounded
  `SegmentTimeline` are supported, including initialization segments and byte ranges.

This is segment concatenation, without transcoding or an external executable. It does not
support live streams, encrypted media/DRM, separate audio/video tracks, HLS discontinuities,
changing initialization segments, DASH `SegmentBase`, multiple periods, or arbitrary media
containers. Unsupported manifests fail before any segment is written. The original playlist
is never reported as a completed media download. A selected single stream may itself contain
both audio and video; an audio-only stream remains audio-only.

Downloads are sequential, obey speed limits, and report the final byte count. Pause/retry
restarts from the beginning because a refreshed manifest cannot safely resume at an arbitrary
byte offset. Output names use `.ts`, `.aac`, `.mp3` or `.mp4`. Filenames in the playlist are not
trusted as filesystem paths. Manifests are limited to 1 MiB, 10,000 parts, four nested HLS
playlists, and bounded XML depth/node counts. XML DTDs and external entities are rejected.
Credentials are omitted when a segment or nested playlist moves to another origin. HTTPS
references cannot downgrade to HTTP.

Custom `HttpEngine` implementations that follow redirects must override `downloadResource`
and return the final response URL. Relative manifest references resolve against that URL.
The supplied Ktor engine and network wrappers implement this. Sources are checked in registration
order, before the built-in HTTP fallback. Both sources share bounded fetching, URL validation
and sequential transfer helpers in core; each protocol owns its parser and tests in its own
module. Existing tasks saved with the former `media` source type route by URL to the installed
HLS or DASH source when restored.

The common parser tests cover ranges, URL resolution, manifest bounds, timeline expansion,
credential scoping and unsupported formats. The Ktor integration test downloads self-generated
H.264 fixtures through a redirect and verifies exact assembled bytes, names and completed size.
It runs offline with the normal JVM suite; FFmpeg is not needed to run tests.
