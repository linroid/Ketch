# Proxies

Ketch can send HTTP(S) downloads, HLS and DASH streams included, through an HTTP or SOCKS5 proxy,
connect directly, or follow the system's proxy settings (the default). This page shows how to set a
proxy in the apps, the CLI, `config.toml` and the SDK, and explains what goes through it.

## Set a proxy

### In the apps

Open Settings → Network and choose **System**, **Direct** or **Manual**. A manual proxy takes a URL,
an optional username and password, and hosts to reach directly. The setting applies to the device
chosen in Settings: the one the app runs, or a remote device that supports proxies.

### In `config.toml`

The apps, `ketch server`, `ketch mcp --standalone` and `ketch <url>` read `[download.proxy]`:

```toml
[download.proxy]
mode = "manual"            # "system" (default), "direct" or "manual"
url = "socks5://127.0.0.1:1080"
username = "me"
password = "secret"
bypass = ["*.lan", "10.0.0.0/8"]
```

Only `manual` reads the other fields; a `system` or `direct` table that still holds them loads
without them.

### With the CLI

`ketch <url>` and `ketch add` take these options for one download:

```bash
ketch --proxy socks5://127.0.0.1:1080 https://example.com/file.iso
```

```bash
ketch --proxy http://proxy.lan:3128 --proxy-bypass '*.lan,10.0.0.0/8' https://example.com/file.iso
```

```bash
ketch --no-proxy https://example.com/file.iso
```

- `--proxy <url>` replaces the configured proxy.
- `--proxy-bypass <hosts>` lists comma-separated hosts to reach directly with `--proxy`.
- `--no-proxy` connects directly.

Without them, `ketch <url>` uses `[download.proxy]`, and `ketch add` uses the setting of the device
it adds to. `ketch add` refuses these options for a device that does not support proxies.

## What uses the proxy

| Traffic | Proxy |
| --- | --- |
| HTTP(S) downloads, HLS and DASH streams, and previews (`resolve`) | The proxy setting |
| FTP downloads, BitTorrent peers | Always direct |
| BitTorrent trackers over HTTP, `.torrent` files and web seeds | The system's settings |
| AI discovery's page fetches | Never proxied |

A download reads the setting when it starts or resumes, so a change applies to downloads started
afterwards.

## Proxy URLs

- `http://host:port` is an HTTP proxy. Plain HTTP requests go to it with absolute URLs, and HTTPS
  goes through `CONNECT` tunnels. Proxies reached over TLS (`https://` proxies) are not supported.
- `socks5://host:port` (or `socks5h://`) is a SOCKS5 proxy, which resolves server names itself.
- Without a scheme, the URL names an HTTP proxy. Without a port, it is 80 for HTTP and 1080 for
  SOCKS5. Credentials in the URL must be percent-encoded.

## Hosts reached directly

Requests to `localhost`, `*.localhost`, `127.0.0.0/8` and `::1` always go directly. With a manual
proxy, so do requests whose host matches a `bypass` entry. Entries are compared with the host as
written in the URL; names are never resolved.

| Entry | Matches |
| --- | --- |
| `example.com`, `.example.com`, `*.example.com` | `example.com` and its subdomains |
| `192.168.1.7`, `::1`, `[fd00::1]` | that address |
| `10.0.0.0/8`, `fd00::/8` | addresses in the range |
| `<local>` | host names without a dot |
| `*` | every host |

A `:port` suffix is ignored.

## Credentials and security

The username and password only go to the proxy: as HTTP Basic authentication in `CONNECT` requests
and in plain HTTP requests, which the proxy reads and removes, and as SOCKS5 username/password
authentication. They never reach the servers you download from. Logs show the proxy's address
without them.

The password is stored in plain text, like the server's `apiToken`: in `config.toml`, and in the
task database for each task whose request names a proxy. The REST API returns it to callers that
hold the server's token, so that the apps can edit a remote device's proxy.

## The system's settings

**System** follows the platform:

- **JVM** (desktop, CLI, server): the `https_proxy` (for HTTPS URLs) or `http_proxy` variable, else
  `all_proxy`, each read in lower case first, and `no_proxy` for hosts reached directly, as curl
  reads them. A variable that names something other than an HTTP or SOCKS5 proxy URL fails the
  download rather than connecting without it. Without these variables the JVM's default
  `ProxySelector` decides: the `http.proxyHost`, `https.proxyHost` and `socksProxyHost` system
  properties, and the operating system's settings when `java.net.useSystemProxies` is `true` at
  startup, as the desktop app's launcher sets it. Proxy auto-configuration (PAC) scripts are not
  evaluated.
- **Android**: the active network's proxy.
- **iOS**: the system's settings, as `NSURLSession` applies them.

## For developers

`DownloadConfig.proxy` sets the proxy of every download, and `DownloadRequest.proxy` replaces it for
one download. `KetchApi.resolve` uses the global one. The setting is a sealed `Proxy`:

```kotlin
Proxy.System                                        // the default: the system's settings
Proxy.Direct                                        // no proxy
Proxy.Manual("http://proxy.lan:3128")               // an HTTP proxy, which tunnels HTTPS
Proxy.Manual(
  "socks5://user:secret@127.0.0.1:1080",            // credentials are kept in its address
  bypass = listOf("*.lan", "10.0.0.0/8", "<local>"),
)
Proxy.Manual(ProxyAddress(ProxyAddress.Type.SOCKS5, "127.0.0.1", 1080, "user", "secret"))
```

```kotlin
val current = ketch.status().config
ketch.updateConfig(current.copy(proxy = Proxy.Manual("socks5://127.0.0.1:1080")))
```

- `Proxy.Manual(url)` throws `IllegalArgumentException` for an invalid URL or bypass entry;
  `Proxy.Manual.isValidBypass(entry)` checks an entry first. `toString()` masks the password.
- JSON (`PUT /api/config`, `POST /api/tasks`) and TOML carry the same flat
  `{mode, url, username, password, bypass}` object, so older servers, clients and config files
  read it.
- A `Ketch` instance lists the `http.proxy` feature (`KetchFeatures.PROXY`) when its `HttpEngine`
  reports `supportsProxies`, as `KtorHttpEngine` does unless it was given an `HttpClient` of its
  own (which only follows the system's settings). Otherwise `updateConfig` and `download` refuse
  any proxy but `Proxy.System` with `UnsupportedOperationException`. `RemoteKetch` does the same
  for a server without the feature, which would ignore the proxy.
- A custom `HttpEngine` that supports proxies overrides `withProxy` and reports
  `supportsProxies`.

## How it works

On the JVM, direct requests use Ktor's CIO client. Requests through a proxy, and direct ones whose
URL the JVM's `ProxySelector` would send through a proxy, use OkHttp limited to HTTP/1.1, so each
segment keeps a connection of its own. Ketch keeps one client per route, and remembers the
redirects a request followed per proxy, since a server may send each proxy to another mirror.

Android uses OkHttp. iOS uses `NSURLSession` sessions with their proxy dictionary set (empty for
direct requests); HTTP proxy credentials answer the proxy's challenge, and SOCKS5 ones go in the
dictionary. SOCKS5 on the JVM and Android is Ketch's own client (RFC 1928 and 1929), which lets the
proxy resolve names.

### With multiple networks

The interface-bound engines (`forLocalAddress`, `forNetwork`) use the proxy too, reaching it from
the selected address or network, so the proxy must be reachable on every selected network. There,
**System** means the environment variables and the JVM's `ProxySelector` on the JVM, and the
selected network's own HTTP proxy on Android (none when that network uses a PAC script). See
[multiple network interfaces](multiple-networks.md).

## See also

- [Multiple network interfaces](multiple-networks.md)
- [HLS and DASH streams](media.md)
