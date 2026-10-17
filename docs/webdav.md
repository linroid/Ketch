# WebDAV

Ketch downloads files from WebDAV servers, such as Nextcloud, ownCloud, a NAS (Synology, QNAP,
TrueNAS), Apache `mod_dav`, nginx or IIS, with parallel connections and resume. The apps, the CLI
and `ketch server` register it; in the SDK it is the `webdav` artifact.

## Links

| Link | Reaches the server over |
|---|---|
| `webdav://host/path/file.iso`, `dav://host/path/file.iso` | HTTP |
| `webdavs://host/path/file.iso`, `davs://host/path/file.iso` | HTTPS |

Write the user name and password into the link, percent-encoding `@`, `:` and other reserved
characters: `davs://alice:p%40ss@cloud.example.com/remote.php/dav/files/alice/movie.mkv`. In the
apps, a link that needs a sign-in shows a Sign in action in the add sheet instead. Links name one
file: a folder is refused.

A plain `https://` link to the same file downloads too, as an HTTP download: what the WebDAV
link adds is the `PROPFIND` check below and Digest sign-in.

## How a download runs

1. `PROPFIND` with `Depth: 0` reads the file's size, entity tag (`getetag`), modification time,
   type and whether it is a folder. A server that answers `403`, `405` or `501` serves the file
   but not its properties, and Ketch reads its headers instead.
2. A ranged `GET` of the first byte checks whether the server sends byte ranges.
3. The file downloads in segments, one ranged `GET` per connection, like an HTTP download. A
   server without ranges uses one connection and restarts from zero. Every segment carries
   `If-Match` with the strong entity tag the probe saw, else `If-Unmodified-Since` with its
   modification time, so a file replaced during the download fails with `FileChanged` (`412`)
   instead of mixing two versions.
4. A pause, a retry or a restart of Ketch continues from the bytes written, once the server
   reports the same strong entity tag, else the same modification time, and size; a file that
   changed fails with `FileChanged`. A weak entity tag (`W/"…"`) only says the content is
   equivalent, so it is never compared, and a file with neither validator downloads again from
   the start.

Proxies, the network interfaces chosen in Settings, speed limits, stalled-connection timeouts,
checksums and the live connections list apply as they do to HTTP downloads.

## Signing in

Ketch sends no credentials until the server asks for them (`401`). It then answers:

- **Digest** (RFC 7616: `SHA-256` or `MD5`, with or without `-sess`, `qop=auth`), when the
  server offers it; a nonce that expires during a download is answered again.
- **Basic** otherwise. Over plain `webdav://` or `dav://` the password crosses the network
  readable, which Ketch logs a warning about; prefer `webdavs://` or `davs://`.

Credentials come from the link, else from an `Authorization: Basic` header of the request (what
the apps' Sign in adds). Any other `Authorization` header, such as a bearer token, is sent as it
is. Credentials the server refuses fail with `AuthenticationFailed`. Redirects to another host
never carry them; after a redirect to another path of the same server, such as a canonical
path, requests go to that path and Digest answers name it.

## In code

```kotlin
val httpEngine = KtorHttpEngine()
val ketch = Ketch(
  httpEngine = httpEngine,
  additionalSources = listOf(WebDavDownloadSource(httpEngine)),
)
ketch.download(DownloadRequest("davs://alice:secret@nas.local/share/backup.tar"))
```

`WebDavDownloadSource` needs an engine that implements `HttpEngine.request`, as `KtorHttpEngine`
does, for its `PROPFIND`s.

## Limits

- One file per link: folders, and downloading a folder's files, are not supported.
- No NTLM or Negotiate (Kerberos) sign-in, and no Digest `auth-int`.
