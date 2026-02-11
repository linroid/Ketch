# Multi-Backend Example App Architecture

## Overview

The example app (`app/shared`) currently creates a hardcoded `KDown` instance. This
design adds support for three backend modes, all working through the existing `KDownApi`
interface:

1. **Embedded** (default) -- in-process `KDown` instance, works on all platforms
2. **Remote server** -- connects to an existing KDown daemon via `RemoteKDown`
3. **Local server** -- starts `KDownServer` in-process (JVM/Desktop only)

Users can configure multiple backends (e.g., several remote servers) and switch between
them. The embedded backend is always present and cannot be removed.

## Design Principles

- **`KDownApi` is the only abstraction the UI needs.** No new download interfaces.
- **Backend list model.** Users manage a list of configured backends (like bookmarks).
  The embedded backend is always present. Remote servers can be added/removed freely.
- **Lambda injection over expect/actual.** Local server support is JVM-only. Instead
  of expect/actual declarations on every platform, use a lambda parameter injected from
  the JVM entry point. CommonMain checks `lambda != null` to gate UI visibility.
- **Compose state drives everything.** The UI observes `StateFlow`s; backend switching
  is a state change, not a navigation event.

---

## 1. Data Model

### BackendConfig

Describes how to create a backend. Sealed class in `commonMain`:

```kotlin
// app/shared/src/commonMain/.../backend/BackendConfig.kt
package com.linroid.kdown.app.backend

sealed class BackendConfig {
  /** In-process KDown instance. Default on all platforms. */
  data object Embedded : BackendConfig()

  /** Connect to an existing KDown daemon. Works on all platforms. */
  data class Remote(
    val host: String,
    val port: Int = 8642,
    val apiToken: String? = null
  ) : BackendConfig() {
    val baseUrl: String get() = "http://$host:$port"
  }

  /** Start a local KDownServer in-process. JVM only. */
  data class LocalServer(
    val port: Int = 8642,
    val apiToken: String? = null
  ) : BackendConfig()
}
```

### BackendType

Simple enum for UI display logic:

```kotlin
enum class BackendType {
  EMBEDDED,
  REMOTE,
  LOCAL_SERVER
}
```

### BackendEntry

A configured backend in the list. Combines config with runtime identity and state:

```kotlin
data class BackendEntry(
  val id: String,
  val type: BackendType,
  val label: String,
  val config: BackendConfig,
  val connectionState: StateFlow<BackendConnectionState>
)
```

- `id`: Stable identifier. `"embedded"` for the built-in backend. UUID for user-added
  backends.
- `label`: Human-readable display name. "Embedded" for the built-in backend. For remote,
  derived from host:port (e.g., "192.168.1.5:8642"). For local server, "Local Server".
- `connectionState`: Per-backend connection status. For embedded, always `Connected`.
  For remote, mirrors `RemoteKDown.connectionState`. For local server, `Connected` once
  the server is listening.

### BackendConnectionState

UI-friendly connection status, unified across backend types:

```kotlin
sealed class BackendConnectionState {
  data object Connected : BackendConnectionState()
  data object Connecting : BackendConnectionState()
  data class Disconnected(val reason: String? = null) : BackendConnectionState()
}
```

This maps from `RemoteKDown.connectionState` for remote backends and is always
`Connected` for embedded/local-server backends.

### Platform availability (no expect/actual needed)

Local server support is determined at runtime via lambda injection rather than
expect/actual. See section 3 (BackendFactory) for details. The UI checks
`backendManager.isLocalServerSupported` which is simply `localServerFactory != null`.

---

## 2. BackendManager

Manages the list of configured backends, the active backend, and lifecycle transitions.
Lives in `commonMain`.

```kotlin
class BackendManager(
  private val factory: BackendFactory
) {
  /** All configured backends. Embedded is always first. */
  val backends: StateFlow<List<BackendEntry>>

  /** The currently active backend entry. */
  val activeBackend: StateFlow<BackendEntry>

  /** The KDownApi for the active backend. UI observes this for tasks. */
  val activeApi: StateFlow<KDownApi>

  /**
   * Switch to a different configured backend by ID.
   * Closes the old backend's KDownApi, creates a new one.
   * Throws on failure (old backend remains active).
   */
  suspend fun switchTo(id: String)

  /**
   * Add a new remote server to the backend list.
   * Does NOT activate it -- call switchTo() afterward.
   * Returns the new BackendEntry.
   */
  fun addRemote(host: String, port: Int = 8642, token: String? = null): BackendEntry

  /**
   * Remove a backend by ID. Cannot remove the embedded backend.
   * If the removed backend is active, switches to embedded first.
   */
  suspend fun removeBackend(id: String)

  /** Whether the current platform supports starting a local server. */
  val isLocalServerSupported: Boolean  // delegates to factory.isLocalServerSupported

  /**
   * Start a local server and add it to the backend list. JVM only.
   * Throws UnsupportedOperationException if !isLocalServerSupported.
   * Returns the new BackendEntry.
   */
  fun addLocalServer(port: Int = 8642, token: String? = null): BackendEntry

  /** Close the active backend and release all resources. */
  fun close()
}
```

### Key behaviors

1. **Embedded is always present.** The backend list is initialized with a single embedded
   entry (`id = "embedded"`). It cannot be removed.
2. **Adding backends does not activate them.** `addRemote()` and `addLocalServer()` only
   add to the list. The user must explicitly `switchTo()` to activate.
3. **Removing the active backend auto-switches to embedded.** If the user removes the
   currently active remote backend, the manager switches to embedded before removing it.
4. **Only one active backend at a time.** Switching closes the previous backend's
   `KDownApi` and factory resources.
5. **Connection state is per-entry.** Each `BackendEntry` has its own
   `connectionState` StateFlow. For inactive remote entries, the state is `Disconnected`
   (connection is only established on activation). For the active entry, it reflects
   actual connection status.

### Why `addRemote` / `addLocalServer` instead of `switchTo(BackendConfig)`

The UX design calls for a backend list where users can add servers and switch between
them later. Separating "add" from "activate" means:
- The backend selector sheet can show all configured servers with their status
- Users can remove servers they no longer need
- The add-remote dialog is separate from the switch action

---

## 3. BackendFactory (lambda injection, no expect/actual)

Per kmp-expert review: instead of expect/actual classes with 4 platform files, we use
**lambda injection** to provide the JVM-only local server capability. This keeps all
factory logic in `commonMain` with zero platform-specific files.

### LocalServerHandle

A simple interface representing a running local server. Defined in `commonMain` so
`BackendManager` can manage its lifecycle without referencing `KDownServer` directly:

```kotlin
// commonMain
interface LocalServerHandle {
  val api: KDownApi   // The core KDown instance the server wraps
  fun stop()
}
```

### BackendFactory

A regular class in `commonMain` (not expect/actual). Takes an optional lambda for
local server creation:

```kotlin
// commonMain
class BackendFactory(
  private val localServerFactory: ((BackendConfig.LocalServer) -> LocalServerHandle)? = null
) {
  /** Whether this platform supports starting a local server. */
  val isLocalServerSupported: Boolean
    get() = localServerFactory != null

  private var localServer: LocalServerHandle? = null

  fun create(config: BackendConfig): KDownApi {
    return when (config) {
      is BackendConfig.Embedded -> createEmbeddedKDown()
      is BackendConfig.Remote ->
        RemoteKDown(config.baseUrl, config.apiToken)
      is BackendConfig.LocalServer -> {
        val factory = localServerFactory
          ?: throw UnsupportedOperationException(
            "Local server not supported on this platform"
          )
        val handle = factory(config)
        localServer = handle
        handle.api  // UI interacts with core KDown directly
      }
    }
  }

  fun closeResources() {
    localServer?.stop()
    localServer = null
  }

  private fun createEmbeddedKDown(): KDown {
    return KDown(
      httpEngine = KtorHttpEngine(),
      config = DownloadConfig(
        maxConnections = 4,
        retryCount = 3,
        queueConfig = QueueConfig(maxConcurrentDownloads = 3)
      ),
      logger = Logger.console()
    )
  }
}
```

### JVM entry point provides the lambda

Only the JVM/Desktop entry point passes the `localServerFactory`. All other platforms
pass `null` (the default):

```kotlin
// app/desktop/src/main/kotlin/.../main.kt (JVM only)
fun main() = application {
  val backendManager = remember {
    BackendManager(
      BackendFactory(localServerFactory = { config ->
        val kdown = KDown(
          httpEngine = KtorHttpEngine(),
          logger = Logger.console()
        )
        val server = KDownServer(
          kdown,
          KDownServerConfig(
            port = config.port,
            apiToken = config.apiToken
          )
        )
        server.start(wait = false)
        object : LocalServerHandle {
          override val api: KDownApi = kdown
          override fun stop() = server.stop()
        }
      })
    )
  }
  // ...
}

// iOS, Android, wasmJs entry points:
BackendManager(BackendFactory())  // no lambda = no local server
```

### Why lambda injection over expect/actual

- **Eliminates 8 files.** No `BackendFactory.jvm.kt`, `BackendFactory.ios.kt`,
  `BackendFactory.android.kt`, `BackendFactory.wasmJs.kt`, and no `Platform.*.kt` files.
- **All factory logic in one place.** Easier to understand and maintain.
- **Server dependency stays in jvmMain.** Only `app/desktop` (pure JVM) references
  `KDownServer` and `KDownServerConfig`. The `app/shared` KMP module never touches them.
- **Runtime capability check is natural.** `isLocalServerSupported` = `lambda != null`.

**Note on LocalServer backend:** When running a local server, the UI interacts with
the core `KDown` instance directly (same process). The server is started alongside it
so external clients (browser, other devices) can also connect. This avoids HTTP
round-trip overhead for local UI operations.

---

## 4. State Flow & Switching Sequence

### Data flow

```
BackendManager
  |
  |-- backends: StateFlow<List<BackendEntry>>    <-- backend selector list
  |-- activeBackend: StateFlow<BackendEntry>     <-- current selection
  |-- activeApi: StateFlow<KDownApi>             <-- UI task list
  |
  |   BackendEntry
  |     |-- id, type, label, config
  |     |-- connectionState: StateFlow<BackendConnectionState>
```

### switchTo(id) sequence

```
User taps a backend in the selector sheet
  --> BackendManager.switchTo(id)
    1. Find entry in backends list by id
    2. If same as active, no-op
    3. Close current activeApi (api.close())
    4. Close factory resources (factory.closeResources())
    5. Create new KDownApi via factory.create(entry.config)
    6. Update activeBackend, activeApi flows
    7. If embedded/local: call (api as KDown).loadTasks()
    8. If remote: start observing RemoteKDown.connectionState
       --> map to entry's connectionState flow
    9. Emit Connected (or Connecting for remote)
  --> UI recomposes with new activeApi.tasks
```

### addRemote() sequence

```
User fills in "Add Remote Server" dialog, taps Add
  --> BackendManager.addRemote(host, port, token)
    1. Create BackendEntry with UUID id, type=REMOTE, config=Remote(...)
    2. Entry's connectionState = Disconnected (not yet connected)
    3. Add to backends list
    4. Return the entry
  --> UI shows new entry in backend selector sheet
  --> User can tap it to switchTo(entry.id) and activate
```

---

## 5. Compose Integration

### App composable changes

`App()` accepts a `BackendManager` instead of creating `KDown` directly:

```kotlin
@Composable
fun App(backendManager: BackendManager) {
  val activeApi by backendManager.activeApi.collectAsState()
  val activeBackend by backendManager.activeBackend.collectAsState()
  val backends by backendManager.backends.collectAsState()
  val tasks by activeApi.tasks.collectAsState()
  val connectionState by activeBackend.connectionState.collectAsState()
  val version by activeApi.version.collectAsState()

  // ... rest of UI uses `activeApi` instead of `kdown`
}
```

### Platform entry points

```kotlin
// Desktop main.kt (JVM -- provides localServerFactory lambda)
fun main() = application {
  val backendManager = remember {
    BackendManager(
      BackendFactory(localServerFactory = { config ->
        val kdown = KDown(httpEngine = KtorHttpEngine(), logger = Logger.console())
        val server = KDownServer(kdown, KDownServerConfig(
          port = config.port, apiToken = config.apiToken
        ))
        server.start(wait = false)
        object : LocalServerHandle {
          override val api: KDownApi = kdown
          override fun stop() = server.stop()
        }
      })
    )
  }
  DisposableEffect(Unit) {
    onDispose { backendManager.close() }
  }
  Window(
    onCloseRequest = ::exitApplication,
    title = "KDown Examples"
  ) {
    App(backendManager)
  }
}

// iOS, Android, wasmJs entry points -- no local server support
val backendManager = BackendManager(BackendFactory())
App(backendManager)
```

### Top app bar

Shows current backend and connection status:

```
[TopAppBar]
  Title: "KDown"
  Subtitle: clickable chip showing "v1.0.0 · Core"
            or "v1.0.0 · Remote · 192.168.1.5:8642"
  Trailing: Connection status dot
            - Green = Connected
            - Yellow/Amber = Connecting
            - Red = Disconnected
  Tap subtitle chip --> opens backend selector bottom sheet
```

### Backend selector (ModalBottomSheet)

```
[Backend Selector Sheet]
  -- Backend List --
  [*] Embedded                         [Connected]
  [ ] Remote · 192.168.1.5:8642        [Disconnected]  [X remove]
  [ ] Remote · nas.local:8642          [Disconnected]  [X remove]
  [ ] Local Server · :8642             [Connected]     [X remove]

  -- Actions --
  [+ Add Remote Server]
  [+ Start Local Server]   (only shown when backendManager.isLocalServerSupported)
```

Tapping a backend entry calls `switchTo(entry.id)`. The [X] button calls
`removeBackend(entry.id)`.

### Add Remote Server (AlertDialog)

```
[Add Remote Server]
  Host:  [________________]
  Port:  [8642____________]
  Token: [________________]  (optional)

  [Cancel]  [Add]
```

Tapping "Add" calls `addRemote(host, port, token)`. The new entry appears in the
backend selector sheet but is not activated until the user taps it.

---

## 6. File / Package Layout

All new code in `app/shared/src/commonMain/` under a `backend` sub-package.
**No platform-specific source sets needed** thanks to lambda injection:

```
app/shared/src/
  commonMain/kotlin/com/linroid/kdown/examples/
    App.kt                        (modified: accept BackendManager param)
    backend/
      BackendConfig.kt            (sealed class: Embedded, Remote, LocalServer)
      BackendConnectionState.kt   (sealed class: Connected, Connecting, Disconnected)
      BackendEntry.kt             (data class with id, type, label, config, connectionState)
      BackendType.kt              (enum: EMBEDDED, REMOTE, LOCAL_SERVER)
      BackendManager.kt           (manages backend list, active backend, switching)
      BackendFactory.kt           (regular class with optional localServerFactory lambda)
      LocalServerHandle.kt        (interface: api + stop())

app/desktop/src/main/kotlin/com/linroid/kdown/examples/
  main.kt                        (modified: create BackendFactory with localServerFactory lambda)
```

Note: The `localServerFactory` lambda is only provided in the JVM desktop entry point
(`app/desktop`), which already depends on the `server` module. No changes
needed in iOS, Android, or wasmJs entry points.

---

## 7. Dependency Changes

### app/shared/build.gradle.kts

```kotlin
commonMain.dependencies {
  // existing
  implementation(projects.library.ktor)
  // add
  implementation(projects.library.remote)  // RemoteKDown
}
// No jvmMain changes -- server dependency lives in desktopApp, not here
```

### app/desktop/build.gradle.kts

```kotlin
dependencies {
  implementation(projects.app.shared)
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutinesSwing)
  // add
  implementation(projects.server)  // KDownServer -- only referenced in main.kt lambda
}
```

The `server` module dependency belongs in `desktopApp` (not `app/shared/jvmMain`)
because only the desktop entry point provides the `localServerFactory` lambda that
references `KDownServer`. The `app/shared` KMP module never imports server classes.

No changes needed for iOS, Android, or wasmJs -- they only use `library.remote` which
is already KMP.

### All platform entry points need updating

Per kmp-expert review, all entry points must create `BackendManager` and pass to `App()`:
- `app/desktop/main.kt` -- with `localServerFactory` lambda (shown in section 5)
- `app/android` -- `BackendManager(BackendFactory())`
- iOS `MainViewController.kt` -- `BackendManager(BackendFactory())`
- wasmJs entry point -- `BackendManager(BackendFactory())`

---

## 8. Error Handling

### Backend switch failures

If `switchTo()` fails (e.g., remote server unreachable, port already in use), the
manager:

1. Keeps the old backend active (does not close it before the new one succeeds)
2. Throws the exception, which the UI catches and shows as an error snackbar

```kotlin
scope.launch {
  try {
    backendManager.switchTo(entry.id)
  } catch (e: Exception) {
    errorMessage = "Failed to switch backend: ${e.message}"
  }
}
```

### Remote disconnection

When a remote backend disconnects, its `connectionState` transitions to `Disconnected`.
The UI shows a connection indicator change but does NOT auto-switch. `RemoteKDown`
handles reconnection with exponential backoff. The user can manually switch to another
backend if they prefer.

### Removing the active backend

If the user removes the currently active backend, the manager switches to embedded
first, then removes the entry. This ensures there is always an active backend.

---

## 9. What This Design Does NOT Do

- **No ViewModel layer.** The example app is intentionally simple. `BackendManager`
  holds the StateFlows directly. A production app would wrap this in a ViewModel.
- **No persistent backend list.** The backend list is not saved to disk. The app
  always starts with only the embedded backend. A production app might persist
  configured remote servers.
- **No simultaneous active backends.** Only one backend is active at a time. Switching
  closes the previous one.
- **No new modules.** All code lives in the existing `app/shared` and
  `app/desktop` modules. No platform-specific source sets needed in
  `app/shared` -- lambda injection handles the JVM-only local server case.

---

## 10. Summary of Changes

| Area | Change |
|------|--------|
| `app/shared/commonMain` | Add `backend/` package: `BackendConfig`, `BackendConnectionState`, `BackendEntry`, `BackendType`, `BackendManager`, `BackendFactory`, `LocalServerHandle` (all in commonMain, no expect/actual) |
| `app/shared/commonMain/App.kt` | Accept `BackendManager` param, use `activeApi` StateFlow, add backend indicator chip in TopAppBar, add backend selector bottom sheet |
| `app/shared/build.gradle.kts` | Add `library.remote` to commonMain dependencies |
| `app/desktop/main.kt` | Create `BackendFactory` with `localServerFactory` lambda, pass `BackendManager` to `App()` |
| `app/desktop/build.gradle.kts` | Add `projects.server` dependency |
| All platform entry points | Create `BackendManager(BackendFactory())` and pass to `App()` |
