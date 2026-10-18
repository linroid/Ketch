# Metalink

A Metalink lists the mirrors that serve a file, with the file's size and hashes. Ketch downloads
it from all of them at once and checks what it gets: the parts of the file come from different
mirrors, a mirror that fails or sends bad data is left out, and the finished file is verified
against the Metalink's checksum.

## What is supported

- Metalink 4 ([RFC 5854](https://www.rfc-editor.org/rfc/rfc5854), `.meta4`) and Metalink 3
  (`.metalink`), from an `http(s)` link ending in either extension or from a file you open or
  drop on the app (`ResolveInput.Content`, whose task downloads from a `metalink:` URL). The
  document is read once and kept with the task, so a resume never fetches it again.
- HTTP and HTTPS mirrors. FTP, BitTorrent `metaurl`s and other resources are skipped, as are
  URLs with a user name and password and files without a usable mirror. At most 64 mirrors are
  kept per file, best priority first (Metalink 4 `priority`, Metalink 3 `preference`); their
  `location` is kept but does not change the order.
- Hashes in MD5, SHA-1, SHA-256 and SHA-512 (`sha-256` and `sha256` alike). The strongest one is
  the checksum Ketch verifies the finished file against (`ResolvedSource.checksum`), unless the
  download names its own `DownloadRequest.checksum`; a file that does not match fails with
  `KetchError.ChecksumMismatch` and is deleted.
- Piece hashes (`<pieces>`): once every byte is in, Ketch checks each piece. The mirrors that
  wrote a piece that does not match are dropped while another one remains, and the failed
  pieces are fetched again from the others, at most three times before the download fails.
- A size the Metalink leaves out is asked of the file's first three mirrors with `HEAD` as the
  Metalink is read (for its first 32 files), with the request's headers and proxy.
- A Metalink listing several files offers them as a single choice: a task downloads one, saved
  under its own name, which you choose before it starts (the add sheet, or a task waiting for
  its files). To download another file of the same Metalink, add it again.

The source lists `download.metalink` (`KetchFeatures.METALINK`) in `KetchStatus.features`,
together with `download.fileSelection` and `download.awaitFileSelection`.

## How mirrors are used

The file is split into segments like any HTTP download, one per connection
(`DownloadConfig.maxConnectionsPerDownload`, or the task's own count), and resegmented when the
count changes. Each segment is fetched from the least busy mirror, preferring better priorities,
so with more mirrors than connections the best ones are used and the rest wait as fallbacks. A
connection that finishes early takes over the rest of a slower one's range, from whichever mirror
it picks next, so a slow mirror never holds the download back.

When a mirror fails, the segment continues from the byte it reached on another mirror. A mirror
answering with an error retrying cannot fix (such as 404, or a different byte range) is dropped
at once; one that fails otherwise waits 1 s, doubling up to 60 s (or what `Retry-After` asks),
and is dropped after five failures in a row. Once only one mirror is left, its failures go to
Ketch's own retries, exactly as a plain HTTP download's do, and every mirror is tried again on
the next attempt.

The request's headers, such as cookies, go only to mirrors on the Metalink's own origin; others
get `User-Agent`, `Accept`, `Accept-Language` and `Accept-Encoding`. Mirrors go through the
task's proxy. Live connections report one connection per segment request, with the mirror's
host.

## Security

A Metalink fetched over HTTPS keeps its plain HTTP mirrors only when the file has a SHA-1 or
stronger hash (whole-file or pieces), since nothing else would tell a mirror's bytes from an
attacker's. A Metalink you open from disk is yours to trust, so its HTTP mirrors are kept.

The XML reader takes no document type declarations, so no entity expands or reaches outside the
document, and it bounds nesting, element counts and the document's size (8 MiB).

## Limits

- One file per task, as above; downloading every file of a Metalink at once is not supported.
- Mirrors are expected to support byte ranges. A mirror that ignores the range is dropped.
- Piece checks read the finished file back, so files the platform cannot read back (some
  Android documents) are checked as a whole only.
- Links that announce a Metalink through HTTP headers (`Link: rel=describedby`,
  [RFC 6249](https://www.rfc-editor.org/rfc/rfc6249)) download as plain HTTP.
- The apps open `.meta4` and `.metalink` files dropped on them or added in the add sheet, but do
  not register as the system's handler for them.

## Using it in the SDK

```kotlin
val httpEngine = KtorHttpEngine()
val ketch = Ketch(
  httpEngine = httpEngine,
  additionalSources = listOf(MetalinkDownloadSource(httpEngine)),
)
ketch.download(DownloadRequest(url = "https://example.com/release.iso.meta4"))
```

`MetalinkDownloadSource` lives in `library:metalink` (`com.linroid.ketch:metalink`), on every
target `library:core` has. Its mirror logic is core's `MirrorDownloadHelper`, which other sources
listing mirrors can use too.
