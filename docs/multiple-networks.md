# Downloading over multiple network interfaces

`MultiNetworkHttpEngine` distributes HTTP HEAD and GET requests round-robin across a fixed
list of network-bound engines. Ketch's concurrent range requests can therefore download
different parts of one file over different networks. Existing segment, queue, speed-limit,
pause/resume, and retry settings still apply.

Interface discovery and runtime selection are exposed through `KetchApi` and `RemoteKetch`.
Desktop, Android, and CLI instances enable these controls, including through their daemon REST
API. There is no settings UI or CLI selection flag yet. FTP and BitTorrent connections do not
use this dispatcher.

## Configure a local or remote instance

Both methods operate on the instance that performs downloads. With `RemoteKetch`, the returned
interfaces belong to the remote host, not the client device:

```kotlin
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.NetworkInterfaceConfig

suspend fun selectInterfaces(api: KetchApi, selectedIds: List<String>) {
  val networks = api.networkInterfaces()
  check(networks.supported) { "This instance does not support interface selection" }
  // Populate your interface picker from networks.available (id, name, addresses).
  api.updateNetworkInterfaces(NetworkInterfaceConfig(selectedIds))
}

// Restore system-default routing:
// api.updateNetworkInterfaces(NetworkInterfaceConfig())
```

The selection is runtime-only; it resets when the instance restarts. IDs are opaque and local
to the target instance. Duplicate, blank, unknown, or unavailable IDs are rejected. Failed
updates leave the previous selection intact. An unavailable selected interface remains in
`config.interfaceIds` even when it disappears from `available`, so clients can display it and
remove it. There is no automatic failover or removal.

Updates affect new HTTP requests, including retries and new segments of active downloads.
Existing requests continue on their old network, and retired transports close once those
requests finish. Reapply the selection to refresh addresses after an interface's address changes.

| REST endpoint | Behavior |
|---|---|
| `GET /api/network-interfaces` | Returns `supported`, `available`, and `config` |
| `PUT /api/network-interfaces` | Accepts `{"interfaceIds":["id-from-discovery"]}`; returns updated state |

Use the same bearer token as other server endpoints. Invalid selections return HTTP 400;
unsupported backends return `supported: false` on GET and HTTP 501 on PUT. `RemoteKetch`
also treats HTTP 404 from older servers as unsupported. PUT with `{"interfaceIds":[]}`
restores system-default routing.

For SDK instances, enable runtime configuration by using the configurable factory:

```kotlin
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces

// JVM / Desktop:
val ketch = Ketch(httpEngine = KtorHttpEngine.withNetworkInterfaces())

// Android: pass your ConnectivityManager instead:
// val ketch = Ketch(httpEngine = KtorHttpEngine.withNetworkInterfaces(connectivityManager))
```

JVM discovery lists active non-loopback interfaces with usable non-link-local addresses.
Selection uses the first IPv4 address, or the first IPv6 address when no IPv4 address exists.
Android discovery lists currently available networks with internet capability and requires
`INTERNET` and `ACCESS_NETWORK_STATE`. Android network IDs change when networks reconnect;
the app still owns requesting and retaining non-default networks. Custom transports can implement
`NetworkInterfaceProvider` and supply it to `ConfigurableNetworkHttpEngine`.

The factories below are alternatives for fixed SDK configurations. A plain `KtorHttpEngine`
or fixed `MultiNetworkHttpEngine` reports runtime interface selection as unsupported.

## JVM / Desktop

Create one engine for each local IP address assigned to the interfaces you want to use:

```kotlin
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.MultiNetworkHttpEngine
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.forLocalAddress
import java.net.InetAddress

// Replace these example addresses with addresses assigned to your local interfaces.
val wifi = KtorHttpEngine.forLocalAddress(InetAddress.getByName("192.168.1.20"))
val ethernet = KtorHttpEngine.forLocalAddress(InetAddress.getByName("192.168.2.20"))
val ketch = Ketch(
  httpEngine = MultiNetworkHttpEngine(listOf(wifi, ethernet)),
  config = DownloadConfig(maxConnectionsPerDownload = 4),
)
```

Use `java.net.NetworkInterface.getNetworkInterfaces()` and each interface's `inetAddresses`
to discover addresses. Choose a concrete IPv4 or IPv6 address; wildcard, multicast, and
unassigned addresses are rejected. The address family must match an address of the destination.

The factory uses Ktor's OkHttp transport with a separate connection pool per engine and binds
TCP sockets before connecting, including HTTPS sockets. DNS uses the system resolver and
filters results to the chosen source address family. System proxies are disabled. Source-address
binding still depends on OS routing; it does not guarantee a physical egress interface on every
OS or VPN configuration. Configure routes as needed. A removed address produces a failure;
the factory never falls back to an unbound socket. Recreate engines after address changes.

## Android

Create one engine per available `android.net.Network`:

```kotlin
import com.linroid.ketch.core.engine.MultiNetworkHttpEngine
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.forNetwork

// wifiNetwork and cellularNetwork come from your ConnectivityManager callbacks.
val httpEngine = MultiNetworkHttpEngine(
  listOf(
    KtorHttpEngine.forNetwork(wifiNetwork),
    KtorHttpEngine.forNetwork(cellularNetwork)
  )
)
// Pass httpEngine to Ketch(httpEngine = httpEngine).
```

The factory binds both sockets and DNS to the supplied network without changing the process's
default network. Each engine has its own connection pool and connects directly without a system
proxy. Acquire and retain networks with `ConnectivityManager` and the permissions required by
the network APIs your app uses. The caller owns network callbacks and unregisters them when done.
Selecting cellular explicitly can consume mobile data. Network loss fails affected requests;
it does not silently switch those requests to the system default network.

See Android's [Network API](https://developer.android.com/reference/android/net/Network) for
socket and DNS binding, and
[ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager)
for acquiring networks.

## Dispatch and lifecycle

- Use at least two concurrent segments to use multiple interfaces within one download.
  A server must support byte ranges for segmentation; a single GET stays on one network.
- Selection is global to the dispatcher, including metadata requests and all tasks. It balances
  request counts, not throughput or active connections. It does not bond individual TCP streams.
- Each request keeps its selected engine until it finishes or fails. Errors and cancellation
  propagate unchanged. The dispatcher does not replay partially delivered data. Ketch's existing
  retry/resume path issues subsequent requests, which participate in round-robin selection.
- There is no health tracking or automatic removal of failed networks. Use
  `updateNetworkInterfaces` with a configurable engine, or rebuild the owning Ketch instance
  when using a fixed engine list.
- All selected networks must return the same file content. Source-IP-bound authentication,
  inconsistent CDN content, or networks with different access permissions may prevent downloads.
- The dispatcher copies its engine list and takes ownership of its delegates. Closing Ketch
  closes the dispatcher and each distinct delegate once. Do not share those delegates with other
  Ketch instances. Close delegates yourself if construction fails before ownership is transferred.

## Other platforms

The dispatcher is multiplatform and accepts custom `HttpEngine` implementations. Native binding
factories are currently provided for JVM and Android only. iOS requires a custom network-bound
engine. Browsers do not expose network-interface selection; browser clients can instead use a
remote Ketch instance configured on a supported host. Passing multiple ordinary `KtorHttpEngine()`
instances does not select different interfaces.
