# Proxies

HTTP(S) downloads, including HLS and DASH streams, can reach their servers through an HTTP or
SOCKS5 proxy, directly, or as the system's proxy settings say. Other traffic does not use this
setting: FTP and BitTorrent peer connections always go directly, BitTorrent's requests to HTTP
trackers and for `.torrent` files follow the system's proxy settings, and Discover's page fetches
never use a proxy.

## Settings

`DownloadConfig.proxy` sets the proxy of every download, `DownloadRequest.proxy` replaces it for
one download, and `KetchApi.resolve` uses the global one. A download reads the setting when it
starts or resumes, like the other `DownloadConfig` fields besides the speed and queue limits.

```kotlin
ProxyConfig.System                                  // the default: the system's settings
ProxyConfig.Direct                                  // no proxy
ProxyConfig.manual("http://proxy.lan:3128")         // an HTTP proxy, which tunnels HTTPS
ProxyConfig.manual(
  "socks5://user:secret@127.0.0.1:1080",            // credentials move to username/password
  bypass = listOf("*.lan", "10.0.0.0/8", "<local>"),
)
```

In `config.toml`, the apps and `ketch server` read the same setting from `[download.proxy]`:

```toml
[download.proxy]
mode = "manual"            # "system", "direct" or "manual"
url = "socks5://127.0.0.1:1080"
username = "me"
password = "secret"
bypass = ["*.lan", "10.0.0.0/8"]
```

The apps edit it in Settings → Network, for the embedded device or, when it lists the
`http.proxy` feature, a remote one. `RemoteKetch` refuses a proxy for a server without that
feature, which would ignore it, with `UnsupportedOperationException`.

### Proxy URLs

- `http://host:port` is an HTTP proxy. Plain HTTP requests go to it with absolute URLs, HTTPS
  through `CONNECT` tunnels. TLS to the proxy itself (`https://` proxies) is not supported.
- `socks5://host:port` (or `socks5h://`) is a SOCKS5 proxy, which resolves server names itself.
- Without a scheme, the URL names an HTTP proxy; without a port, it is 80 for HTTP and 1080 for
  SOCKS5. Credentials in the URL are percent-encoded.

### Credentials

`username` and `password` go only to the proxy: with HTTP Basic authentication in `CONNECT`
requests and with plain HTTP requests, which the proxy reads and removes, and with SOCKS5
username/password authentication. They never reach the servers downloads come from. Logs print
the proxy's address without them, and `ProxyConfig.toString()` masks the password.

The password is stored in plain text, like the server's `apiToken`: in `config.toml`, and with
each task whose request names a proxy in the task database. The REST API returns it to callers
holding the server's token, so that the apps can edit a remote device's proxy.

### Bypass list

Requests to `localhost`, `*.localhost`, `127.0.0.0/8` and `::1` always go directly. With a
manual proxy, so do those whose host matches `bypass`, compared with the URL's host as written,
never resolved:

| Entry | Matches |
| --- | --- |
| `example.com`, `.example.com`, `*.example.com` | `example.com` and its subdomains |
| `192.168.1.7`, `::1`, `[fd00::1]` | that address |
| `10.0.0.0/8`, `fd00::/8` | addresses in the range |
| `<local>` | host names without a dot |
| `*` | every host |

A `:port` suffix is ignored. `ProxyConfig.isValidBypass(entry)` checks an entry.

## The system's settings

`ProxyMode.SYSTEM` follows the platform:

- **JVM**: the `https_proxy` (for HTTPS URLs) or `http_proxy` variable, else `all_proxy`, each
  read in lower case first, and `no_proxy` for hosts reached directly, as curl reads them. A
  variable naming something other than an HTTP or SOCKS5 proxy URL fails the download rather
  than connecting without it. Without one, the JVM's default `ProxySelector` decides: the
  `http.proxyHost`, `https.proxyHost` and `socksProxyHost` system properties, and the operating
  system's settings when `java.net.useSystemProxies` is `true` at startup, as the desktop app's
  launcher sets it. Proxy auto-configuration (PAC) scripts are not evaluated.
- **Android**: the active network's proxy, as OkHttp finds it.
- **iOS**: the system's settings, as `NSURLSession` applies them.

## Transports

On the JVM, requests that go directly use CIO, as before; those through a proxy, and direct ones
whose URL the JVM's `ProxySelector` would send through a proxy, use OkHttp limited to HTTP/1.1, so
that each segment keeps a connection of its own. One client is kept per route. Android uses
OkHttp and iOS `NSURLSession` sessions with their proxy dictionary set (empty for direct
requests); HTTP proxy credentials answer the proxy's challenge, SOCKS5 ones go in the dictionary.

## Multiple networks

The interface-bound engines (`forLocalAddress`, `forNetwork`) apply the proxy too, reaching it
from the selected address or network, so a proxy must be reachable on every selected network.
There, the system's proxy is the environment's and the JVM's on the JVM, and the selected
network's own HTTP proxy on Android (none when the network uses a PAC script); see
[multiple network interfaces](multiple-networks.md).

## CLI

`ketch <url>` uses `[download.proxy]` of the config file, which defaults to the environment
variables above. `--proxy <url>` replaces it for the download, `--proxy-bypass <hosts>` lists
comma-separated hosts it skips, and `--no-proxy` connects directly. `ketch server` and
`ketch mcp` use the config file's setting.
