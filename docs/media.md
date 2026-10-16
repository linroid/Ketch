# HLS and DASH streams

Ketch can save a finished HLS (`.m3u8`) or DASH (`.mpd`) stream as one media file, by downloading
its segments and joining them in order. This page shows how to download a stream in the apps, the
CLI and the SDK, and lists which streams work.

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

### What you get

- The file holds the media, not the playlist text. Ketch names it `.ts`, `.aac`, `.mp3` or `.mp4`
  to match the segments. Save to a folder to keep that name.
- A file destination is used as given: naming it `.mp4` does not convert a transport stream to
  MP4. Ketch joins segments; it never transcodes.
- For an HLS master playlist, Ketch picks the variant with the highest bandwidth. For DASH it picks
  the representation with the highest bandwidth.
- The total size is unknown until the download completes, so progress shows bytes received.
- Pausing, or a retry after an error, starts the stream over from the beginning (see
  [why](#how-it-works)).
- Speed limits, proxies and checksums apply as for other HTTP downloads.

## Supported streams

| Format | Supported |
| --- | --- |
| HLS media playlists | Finished playlists (`EXT-X-ENDLIST`) of MPEG-TS, packed AAC or MP3, or fragmented MP4 with one `EXT-X-MAP`. Byte ranges are supported, including implicit offsets. |
| HLS master playlists | The highest-bandwidth variant is downloaded, up to four nested playlists deep. |
| DASH | Static MP4 manifests with one period, one adaptation set and one representation. `SegmentList`, and `SegmentTemplate` with a finite duration or bounded `SegmentTimeline`, including initialization segments and byte ranges. |

Not supported:

- live streams;
- encrypted streams and DRM;
- separate audio and video tracks (HLS renditions or several DASH adaptation sets), as joining
  them needs a muxer;
- HLS discontinuities and changing initialization segments;
- DASH `SegmentBase` and multiple periods;
- other containers.

A stream Ketch cannot handle fails before any segment is written, and the playlist itself is never
reported as a finished download. A single selected stream may contain both audio and video; an
audio-only stream stays audio-only.

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
  send a playlist.
- **Redirects**: relative references resolve against the manifest's final URL. A custom
  `HttpEngine` that follows redirects must override `downloadResource` and return the final URL.
  `KtorHttpEngine` and the network engines do.
- **Older tasks**: tasks saved with the former `media` source type are restored by the HLS or DASH
  source that matches their URL.

## How it works

Both sources share core helpers (in `com.linroid.ketch.core.media`) that fetch manifests within
their limits, validate URLs, scope headers per origin and download segments one after another into
the output file. Each module owns its parser and its tests.

A download cannot resume partway: the manifest is fetched again for every run, and a refreshed
manifest gives no reliable way to match a byte offset to a segment. So pausing or retrying restarts
the transfer, and the source keeps no resume state.

The parser tests cover ranges, URL resolution, manifest limits, timeline expansion, credential
scoping and unsupported formats. A Ktor integration test downloads self-generated H.264 fixtures
through a redirect and checks the assembled bytes, names and size. It runs offline with the normal
JVM tests; FFmpeg is not needed.

## See also

- [Proxies](proxy.md), which HLS and DASH downloads follow
- [Logging](logging.md)
