# Downloading over multiple network interfaces

Ketch can spread the HTTP requests of a download across several network interfaces, such as Wi-Fi
and Ethernet, or Wi-Fi and mobile data, so that different parts of one file arrive over different
networks. This page shows how to choose interfaces in the apps, over the REST API and in the SDK,
and explains what to expect.

## Choose interfaces in the apps

Open **Settings → Network**. It lists the interfaces of the device chosen in Settings (by default
the one shown) and applies the ticked ones immediately. Untick them all to go back to the system's
default routing.

- Desktop and Android, and `ketch server`, support it. The iOS app reports it as unsupported, and
  browsers cannot choose interfaces, but the web app can change a remote device's selection.
- The selection is not saved: it resets when the device's Ketch restarts.
- There is no CLI option for it yet; use the apps or the REST API against `ketch server`.

## What to expect

- **One download needs several connections.** Requests take turns across the selected networks, so
  a download uses more than one network only when it runs at least two segments at once. The
  server must support byte ranges; a single request stays on one network.
- **It balances requests, not speed.** Requests are spread round-robin across all downloads and the
  metadata requests before them. Ketch does not bond connections or measure each network's speed.
- **Only HTTP(S) downloads.** FTP and BitTorrent connections ignore the selection.
- **No automatic failover.** A network that disappears fails the requests using it, and Ketch's
  usual retries send new requests round-robin again. Ketch never removes a network from the
  selection on its own.
- **Every network must see the same file.** Authentication tied to an IP address, CDNs that serve
  different content, or networks with different access can break downloads.
- **Mobile data costs.** Selecting cellular explicitly uses mobile data.
- A [proxy](proxy.md) set for downloads is used on every selected network, so it must be reachable
  from each.

Changes apply to new requests, including retries and new segments of running downloads. Requests
already running finish on their old network. After an interface's address changes, apply the
selection again to pick up the new address.

## Over the REST API

`ketch server` and other Ketch servers expose the selection, behind the same bearer token as other
endpoints:

| Endpoint | Behavior |
| --- | --- |
| `GET /api/network-interfaces` | Returns `supported`, `available` and the current `config` |
| `PUT /api/network-interfaces` | Takes `{"interfaceIds":["id-from-discovery"]}` and returns the new state |

```bash
curl -H "Authorization: Bearer $TOKEN" http://nas.local:8642/api/network-interfaces
```

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"interfaceIds":["<id-1>","<id-2>"]}' http://nas.local:8642/api/network-interfaces
```

- `{"interfaceIds":[]}` restores the system's default routing.
- Duplicate, blank, unknown or unavailable IDs return HTTP 400 and keep the previous selection.
- A server that does not support it returns `supported: false` on GET and HTTP 501 on PUT.
- IDs are opaque and only mean something to that server; always take them from `available`.

## In the SDK

### Runtime selection

Build `Ketch` with the configurable engine, then pick interfaces through `KetchApi`:

```kotlin
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces

// JVM / desktop:
val ketch = Ketch(httpEngine = KtorHttpEngine.withNetworkInterfaces())

// Android: pass your ConnectivityManager.
// val ketch = Ketch(httpEngine = KtorHttpEngine.withNetworkInterfaces(connectivityManager))
```

```kotlin
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.NetworkInterfaceConfig

suspend fun selectInterfaces(api: KetchApi, selectedIds: List<String>) {
  val networks = api.networkInterfaces()
  check(networks.supported) { "This instance does not support interface selection" }
  // Fill your picker from networks.available (id, name, addresses).
  api.selectNetworkInterfaces(NetworkInterfaceConfig(selectedIds))
}

// Back to the system's default routing:
// api.selectNetworkInterfaces(NetworkInterfaceConfig())
```

Both calls act on the instance that downloads. With `RemoteKetch`, the interfaces belong to the
remote host, not the client device, and a server too old to know the endpoint (HTTP 404) counts as
unsupported. A failed update leaves the previous selection in place. A selected interface that
disappears stays in `config.interfaceIds` even though it leaves `available`, so clients can show it
and let the user remove it.

What gets listed:

- **JVM**: active, non-loopback interfaces with a usable address that is not link-local. Ketch binds
  to the first IPv4 address, or the first IPv6 address when there is no IPv4 one.
- **Android**: available networks with internet access, named by transport ("Wi-Fi", "Mobile data",
  "Ethernet", "VPN", "Bluetooth"), with the interface added when two share a name
  ("Wi-Fi (wlan1)"). It needs the `INTERNET` and `ACCESS_NETWORK_STATE` permissions. Network IDs
  change when networks reconnect, and your app still owns requesting and keeping non-default
  networks.

For other transports, implement `NetworkInterfaceProvider` and pass it to
`ConfigurableNetworkHttpEngine`. A plain `KtorHttpEngine`, or a fixed `MultiNetworkHttpEngine`,
reports runtime selection as unsupported.

### A fixed set of networks

If your app always uses the same networks, build one engine per network and combine them with
`MultiNetworkHttpEngine`.

On the JVM, create one engine per local IP address:

```kotlin
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.MultiNetworkHttpEngine
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.forLocalAddress
import java.net.InetAddress

// Replace these with addresses assigned to your interfaces.
val wifi = KtorHttpEngine.forLocalAddress(InetAddress.getByName("192.168.1.20"))
val ethernet = KtorHttpEngine.forLocalAddress(InetAddress.getByName("192.168.2.20"))
val ketch = Ketch(
  httpEngine = MultiNetworkHttpEngine(listOf(wifi, ethernet)),
  config = DownloadConfig(maxConnectionsPerDownload = 4),
)
```

Find addresses with `java.net.NetworkInterface.getNetworkInterfaces()` and each interface's
`inetAddresses`. Use a concrete IPv4 or IPv6 address: wildcard, multicast and unassigned addresses
are rejected, and the address family must match one of the server's addresses.

On Android, create one engine per `android.net.Network`:

```kotlin
import com.linroid.ketch.core.engine.MultiNetworkHttpEngine
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.forNetwork

// wifiNetwork and cellularNetwork come from your ConnectivityManager callbacks.
val httpEngine = MultiNetworkHttpEngine(
  listOf(
    KtorHttpEngine.forNetwork(wifiNetwork, connectivityManager = connectivityManager),
    KtorHttpEngine.forNetwork(cellularNetwork, connectivityManager = connectivityManager),
  ),
)
val ketch = Ketch(httpEngine = httpEngine)
```

Acquire and keep networks with `ConnectivityManager` and the permissions its APIs need, and
unregister your callbacks when done. See Android's
[Network](https://developer.android.com/reference/android/net/Network) and
[ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager)
references.

With a fixed list, rebuild the `Ketch` instance to change networks. After an address changes on
the JVM, create the engines again.

### Ownership

`MultiNetworkHttpEngine` copies its engine list and takes ownership of the engines. Closing Ketch
closes it and each distinct engine once, so do not share those engines with other Ketch instances.
If construction fails before ownership passes, close the engines yourself.

### Other platforms

`MultiNetworkHttpEngine` is multiplatform and accepts any `HttpEngine`, but network-bound engines
ship for JVM and Android only; iOS needs a custom one. Several plain `KtorHttpEngine()` instances do
not use different interfaces. Browser clients can control a remote Ketch instance on a supported
host instead.

## How it works

`MultiNetworkHttpEngine` hands each HTTP HEAD and GET to the next engine in turn. A request keeps
its engine until it finishes or fails; errors and cancellation pass through unchanged, and partly
delivered data is never replayed. Ketch's retries and resumes issue new requests, which take their
turn like any other.

- **JVM** (`forLocalAddress`): each engine uses Ktor's OkHttp transport with its own connection
  pools and binds its TCP sockets to the address before connecting, HTTPS sockets and sockets to a
  proxy included. DNS uses the system resolver, filtered to the address's family. **System** proxy
  settings mean the environment variables and the JVM's `ProxySelector`. Binding a source address
  still depends on the OS's routes: it does not guarantee the physical interface on every OS or
  VPN setup, so configure routes as needed. A removed address fails requests; the engine never
  falls back to an unbound socket.
- **Android** (`forNetwork`): sockets and DNS are bound to the network without changing the
  process's default network, and each engine has its own connection pools. **System** proxy
  settings mean the network's own HTTP proxy, which `forNetwork` reads when given a
  `connectivityManager` (`withNetworkInterfaces` passes its own); without it, requests connect
  directly. Losing the network fails its requests rather than moving them to the default network.
- **Runtime selection** (`ConfigurableNetworkHttpEngine`): a new selection builds new engines for
  new requests, and the engines it replaces close once their requests finish.

## See also

- [Proxies](proxy.md)
- [REST API and other developer docs](developers.md)
