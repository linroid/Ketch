package com.linroid.kdown.app

import com.linroid.kdown.app.backend.BackendConfig
import com.linroid.kdown.app.backend.BackendEntry
import com.linroid.kdown.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for BackendManager backend switching logic.
 *
 * These tests are structured against the actual BackendManager API:
 * - `switchTo(id: String)` - switches by backend ID
 * - `addRemote(host, port, token)` - adds a remote entry
 * - `removeBackend(id: String)` - removes by backend ID
 * - `activeBackend: StateFlow<BackendEntry?>` - current entry
 * - `activeApi: StateFlow<KDownApi>` - current API instance
 * - `backends: StateFlow<List<BackendEntry>>` - all entries
 * - `isLocalServerSupported: Boolean` - platform capability
 * - `serverState: StateFlow<ServerState>` - HTTP server state
 *
 * Test categories:
 *  1. FakeKDownApi (test double validation)
 *  2. BackendConfig / ConnectionState model tests
 *  3. BackendManager lifecycle
 *  4. Backend switching
 *  5. Connection state tracking
 *  6. Error handling on switch failure
 *  7. Concurrent safety
 *  8. Remove backend (auto-switch to embedded)
 *  9. Backend label propagation
 * 10. Backend list ordering
 *
 * TESTABILITY BLOCKER for categories 3-10:
 * BackendManager takes `BackendFactory` in its constructor, and
 * hardcodes `createEmbedded()` which creates a real KDown instance.
 * To unit-test BackendManager, we need either:
 *   (a) Extract an interface from BackendFactory and accept it in
 *       BackendManager constructor, OR
 *   (b) Make BackendManager accept an `embeddedApiFactory` lambda
 *
 * Once the testability refactor is done, uncomment categories 3-10
 * and replace `BackendFactory` with `FakeBackendFactory`.
 *
 * ## UI behaviors for future instrumented tests
 *
 * The following behaviors are pure Compose UI logic (not in
 * BackendManager) and require `createComposeRule()` to test:
 *
 * - Port validation: "Add" button disabled when port outside
 *   1-65535 or host blank. Error text below port field.
 * - Double-tap guard: `switchingBackendId` state prevents
 *   tapping other entries during an active switchTo() call.
 * - Sheet dismiss timing: sheet stays open on failure,
 *   only dismisses after successful switchTo() return.
 * - "Not connected" vs "Disconnected" chip: inactive remotes
 *   show gray "Not connected"; only active backend shows red
 *   "Disconnected" on connection loss (via `isActive` param
 *   on ConnectionStatusChip).
 */
class BackendManagerTest {

  // -------------------------------------------------------
  // 1. FakeKDownApi (test double validation)
  // -------------------------------------------------------

  @Test
  fun fakeKDownApi_initialState() {
    val fake = FakeKDownApi(backendLabel = "TestBackend")
    assertEquals("TestBackend", fake.backendLabel)
    assertFalse(fake.closed)
    assertEquals(0, fake.closeCallCount)
    assertEquals(0, fake.downloadCallCount)
  }

  @Test
  fun fakeKDownApi_close_setsClosed() {
    val fake = FakeKDownApi()
    fake.close()
    assertTrue(fake.closed)
    assertEquals(1, fake.closeCallCount)
  }

  @Test
  fun fakeKDownApi_close_calledTwice_incrementsCount() {
    val fake = FakeKDownApi()
    fake.close()
    fake.close()
    assertEquals(2, fake.closeCallCount)
  }

  @Test
  fun fakeKDownApi_tasks_initiallyEmpty() {
    val fake = FakeKDownApi()
    assertTrue(fake.tasks.value.isEmpty())
  }

  // -------------------------------------------------------
  // 1b. FakeBackendFactory (test double validation)
  // -------------------------------------------------------

  @Test
  fun fakeBackendFactory_create_embedded_usesLambda() {
    val embedded = FakeKDownApi("MyCore")
    val factory = FakeBackendFactory(
      embeddedFactory = { embedded },
    )
    val result = factory.create(BackendConfig.Embedded)
    assertEquals("MyCore", result.backendLabel)
    assertEquals(1, factory.createCallCount)
    assertEquals(BackendConfig.Embedded, factory.lastCreatedConfig)
  }

  @Test
  fun fakeBackendFactory_create_remote_defaultLabel() {
    val factory = FakeBackendFactory()
    val result = factory.create(
      BackendConfig.Remote("myhost", 9000)
    )
    assertEquals("Remote · myhost:9000", result.backendLabel)
  }

  @Test
  fun fakeBackendFactory_create_remote_customFactory() {
    val custom = FakeKDownApi("Custom Remote")
    val factory = FakeBackendFactory()
    factory.remoteFactory = { custom }
    val result = factory.create(
      BackendConfig.Remote("host", 8642)
    )
    assertEquals("Custom Remote", result.backendLabel)
  }

  @Test
  fun fakeBackendFactory_failOnNextCreate_throwsThenResets() {
    val factory = FakeBackendFactory()
    factory.failOnNextCreate = true

    val result1 = runCatching {
      factory.create(BackendConfig.Remote("host"))
    }
    assertTrue(result1.isFailure)

    // Next call should succeed (flag auto-resets)
    val result2 = runCatching {
      factory.create(BackendConfig.Remote("host"))
    }
    assertTrue(result2.isSuccess)
  }

  @Test
  fun fakeBackendFactory_closeResources_incrementsCount() {
    val factory = FakeBackendFactory()
    assertEquals(0, factory.closeResourcesCallCount)
    factory.closeResources()
    assertEquals(1, factory.closeResourcesCallCount)
    factory.closeResources()
    assertEquals(2, factory.closeResourcesCallCount)
  }

  // -------------------------------------------------------
  // 2. BackendConfig model tests
  // -------------------------------------------------------

  @Test
  fun embeddedConfig_isSingleton() {
    val a = BackendConfig.Embedded
    val b = BackendConfig.Embedded
    assertEquals(a, b)
  }

  @Test
  fun remoteConfig_baseUrl_combinesHostAndPort() {
    val config = BackendConfig.Remote(
      host = "192.168.1.5",
      port = 9000,
    )
    assertEquals("http://192.168.1.5:9000", config.baseUrl)
  }

  @Test
  fun remoteConfig_defaultPort_is8642() {
    val config = BackendConfig.Remote(host = "localhost")
    assertEquals(8642, config.port)
  }

  @Test
  fun remoteConfig_equality() {
    val a = BackendConfig.Remote("host1", 8642, "token")
    val b = BackendConfig.Remote("host1", 8642, "token")
    assertEquals(a, b)
  }

  @Test
  fun remoteConfig_inequality_differentHost() {
    val a = BackendConfig.Remote("host1")
    val b = BackendConfig.Remote("host2")
    assertNotEquals(a, b)
  }

  @Test
  fun backendConnectionState_types() {
    val connected = ConnectionState.Connected
    val connecting = ConnectionState.Connecting
    val disconnected =
      ConnectionState.Disconnected("timeout")

    assertTrue(connected is ConnectionState)
    assertTrue(connecting is ConnectionState)
    assertTrue(disconnected is ConnectionState)
    assertEquals("timeout", disconnected.reason)
  }

  @Test
  fun backendConnectionState_disconnected_nullReason() {
    val state = ConnectionState.Disconnected()
    assertEquals(null, state.reason)
  }

  @Test
  fun remoteConfig_baseUrl_localhostDefault() {
    val config = BackendConfig.Remote(host = "localhost")
    assertEquals("http://localhost:8642", config.baseUrl)
  }

  @Test
  fun remoteConfig_apiToken_nullable() {
    val withToken = BackendConfig.Remote("h", 8642, "tok")
    val withoutToken = BackendConfig.Remote("h", 8642)
    assertEquals("tok", withToken.apiToken)
    assertEquals(null, withoutToken.apiToken)
  }

  @Test
  fun remoteConfig_inequality_differentPort() {
    val a = BackendConfig.Remote("host", 8642)
    val b = BackendConfig.Remote("host", 9000)
    assertNotEquals(a, b)
  }

  @Test
  fun remoteConfig_inequality_differentToken() {
    val a = BackendConfig.Remote("host", 8642, "token1")
    val b = BackendConfig.Remote("host", 8642, "token2")
    assertNotEquals(a, b)
  }

  @Test
  fun backendConnectionState_connected_isSingleton() {
    val a = ConnectionState.Connected
    val b = ConnectionState.Connected
    assertEquals(a, b)
  }

  @Test
  fun backendConnectionState_connecting_isSingleton() {
    val a = ConnectionState.Connecting
    val b = ConnectionState.Connecting
    assertEquals(a, b)
  }

  @Test
  fun backendConnectionState_disconnected_equality() {
    val a = ConnectionState.Disconnected("timeout")
    val b = ConnectionState.Disconnected("timeout")
    assertEquals(a, b)
  }

  @Test
  fun backendConnectionState_disconnected_inequality() {
    val a = ConnectionState.Disconnected("timeout")
    val b = ConnectionState.Disconnected("refused")
    assertNotEquals(a, b)
  }

  // -------------------------------------------------------
  // 2b. BackendEntry construction tests
  // -------------------------------------------------------

  @Test
  fun backendEntry_embeddedConstruction() {
    val entry = BackendEntry(
      id = "embedded",
      label = "Embedded",
      config = BackendConfig.Embedded,
      connectionState = MutableStateFlow(
        ConnectionState.Connected
      )
    )
    assertEquals("embedded", entry.id)
    assertTrue(entry.isEmbedded)
    assertEquals("Embedded", entry.label)
    assertEquals(BackendConfig.Embedded, entry.config)
    assertEquals(
      ConnectionState.Connected,
      entry.connectionState.value
    )
  }

  @Test
  fun backendEntry_remoteConstruction() {
    val config = BackendConfig.Remote("myhost", 9000)
    val entry = BackendEntry(
      id = "remote-myhost:9000",
      label = "myhost:9000",
      config = config,
      connectionState = MutableStateFlow(
        ConnectionState.Disconnected()
      )
    )
    assertEquals("remote-myhost:9000", entry.id)
    assertFalse(entry.isEmbedded)
    assertEquals("myhost:9000", entry.label)
    assertTrue(
      entry.config is BackendConfig.Remote
    )
    assertTrue(
      entry.connectionState.value
        is ConnectionState.Disconnected
    )
  }

  @Test
  fun backendEntry_equality_sameFields() {
    val connState = MutableStateFlow(
      ConnectionState.Connected
    )
    val a = BackendEntry(
      id = "test",
      label = "Test",
      config = BackendConfig.Embedded,
      connectionState = connState,
    )
    val b = BackendEntry(
      id = "test",
      label = "Test",
      config = BackendConfig.Embedded,
      connectionState = connState,
    )
    assertEquals(a, b)
  }

  @Test
  fun backendEntry_inequality_differentId() {
    val connState = MutableStateFlow(
      ConnectionState.Connected
    )
    val a = BackendEntry(
      id = "a",
      label = "Test",
      config = BackendConfig.Embedded,
      connectionState = connState,
    )
    val b = BackendEntry(
      id = "b",
      label = "Test",
      config = BackendConfig.Embedded,
      connectionState = connState,
    )
    assertNotEquals(a, b)
  }

  // -------------------------------------------------------
  // 3. BackendManager lifecycle tests
  //
  // BLOCKED: Requires testability refactor of BackendManager
  // (accept interface instead of BackendFactory).
  // -------------------------------------------------------

  // @Test
  // fun initialBackend_isEmbedded() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory) // needs interface
  //   val active = manager.activeBackend.value
  //   assertEquals("embedded", active?.id)
  //   assertTrue(active?.isEmbedded == true)
  //   assertEquals(BackendConfig.Embedded, active?.config)
  //   manager.close()
  // }

  // @Test
  // fun initialBackendList_containsOnlyEmbedded() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   val list = manager.backends.value
  //   assertEquals(1, list.size)
  //   assertEquals("embedded", list[0].id)
  //   manager.close()
  // }

  // @Test
  // fun close_closesActiveApi() = runTest {
  //   val embedded = FakeKDownApi("Core")
  //   val factory = FakeBackendFactory(
  //     embeddedFactory = { embedded }
  //   )
  //   val manager = BackendManager(factory)
  //   manager.close()
  //   assertTrue(embedded.closed)
  //   assertEquals(1, embedded.closeCallCount)
  // }

  // -------------------------------------------------------
  // 4. Backend switching tests
  //
  // BackendManager.switchTo(id) looks up entry by ID from
  // backends list, creates the API client, sets
  // activeApi + activeBackend, then closes old API.
  // -------------------------------------------------------

  // @Test
  // fun switchTo_remote_updatesActiveBackend() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   val entry = manager.addRemote("localhost")
  //   manager.switchTo(entry.id)
  //   assertEquals(entry.id, manager.activeBackend.value?.id)
  //   assertFalse(manager.activeBackend.value?.isEmbedded == true)
  //   manager.close()
  // }

  // @Test
  // fun switchTo_sameId_isNoOp() = runTest {
  //   val embedded = FakeKDownApi("Core")
  //   val factory = FakeBackendFactory(
  //     embeddedFactory = { embedded }
  //   )
  //   val manager = BackendManager(factory)
  //   manager.switchTo("embedded")
  //   assertFalse(
  //     embedded.closed,
  //     "Same-ID switch should not close current API"
  //   )
  //   manager.close()
  // }

  // @Test
  // fun switchTo_unknownId_throws() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   val result = runCatching {
  //     manager.switchTo("nonexistent-backend")
  //   }
  //   assertTrue(
  //     result.isFailure,
  //     "Unknown ID should throw"
  //   )
  //   assertTrue(
  //     result.exceptionOrNull()
  //       is IllegalArgumentException,
  //     "Should be IllegalArgumentException"
  //   )
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 5. Connection state tracking
  // -------------------------------------------------------

  // @Test
  // fun embeddedEntry_connectionState_isConnected() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   assertEquals(
  //     ConnectionState.Connected,
  //     manager.activeBackend.value?.connectionState?.value
  //   )
  //   manager.close()
  // }

  // @Test
  // fun addRemote_connectionState_isDisconnected() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   val entry = manager.addRemote("localhost")
  //   assertTrue(
  //     entry.connectionState.value
  //       is ConnectionState.Disconnected,
  //     "New remote should start Disconnected"
  //   )
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 6. Error handling on switch failure
  // -------------------------------------------------------

  // @Test
  // fun switchTo_factoryFailure_keepsOldBackend() = runTest {
  //   val embedded = FakeKDownApi("Core")
  //   val factory = FakeBackendFactory(
  //     embeddedFactory = { embedded }
  //   )
  //   val manager = BackendManager(factory)
  //   val entry = manager.addRemote("unreachable")
  //   factory.failOnNextCreate = true
  //   try {
  //     manager.switchTo(entry.id)
  //   } catch (_: Exception) { }
  //   assertFalse(
  //     embedded.closed,
  //     "Old API must NOT be closed on switch failure"
  //   )
  //   assertEquals(
  //     "embedded",
  //     manager.activeBackend.value?.id,
  //     "Should still be on embedded"
  //   )
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 7. Concurrent safety
  // -------------------------------------------------------

  // @Test
  // fun rapidSwitching_lastOneWins() = runTest {
  //   val remotes = (0..3).map {
  //     FakeKDownApi("Remote-$it")
  //   }
  //   var idx = 0
  //   val factory = FakeBackendFactory()
  //   factory.remoteFactory = { remotes[idx++] }
  //   val manager = BackendManager(factory)
  //   val entries = (0..3).map {
  //     manager.addRemote("host-$it")
  //   }
  //   entries.forEach { manager.switchTo(it.id) }
  //   assertEquals(
  //     entries[3].id,
  //     manager.activeBackend.value?.id
  //   )
  //   for (i in 0..2) {
  //     assertTrue(
  //       remotes[i].closed,
  //       "Remote-$i should be closed"
  //     )
  //   }
  //   assertFalse(remotes[3].closed)
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 8. Remove backend (auto-switch to embedded)
  // -------------------------------------------------------

  // @Test
  // fun removeActiveRemote_switchesToEmbedded() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   val entry = manager.addRemote("10.0.0.1")
  //   manager.switchTo(entry.id)
  //   manager.removeBackend(entry.id)
  //   assertEquals(
  //     "embedded",
  //     manager.activeBackend.value?.id
  //   )
  //   assertFalse(
  //     manager.backends.value.any { it.id == entry.id },
  //     "Removed entry should not be in backends list"
  //   )
  //   manager.close()
  // }

  // @Test
  // fun removeEmbedded_throws() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   val result = runCatching {
  //     manager.removeBackend("embedded")
  //   }
  //   assertTrue(
  //     result.isFailure,
  //     "Removing embedded should throw"
  //   )
  //   assertTrue(
  //     result.exceptionOrNull()
  //       is IllegalArgumentException,
  //     "Should throw IllegalArgumentException"
  //   )
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 9. addRemote behavior
  // -------------------------------------------------------

  // @Test
  // fun addRemote_doesNotActivate() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   manager.addRemote("newhost", 9999)
  //   assertEquals(
  //     "embedded",
  //     manager.activeBackend.value?.id,
  //     "Adding remote should not switch active backend"
  //   )
  //   manager.close()
  // }

  // @Test
  // fun addRemote_appendsToBackendsList() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   val entry = manager.addRemote("host1")
  //   val list = manager.backends.value
  //   assertEquals(2, list.size)
  //   assertEquals("embedded", list[0].id)
  //   assertEquals(entry.id, list[1].id)
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 10. Backend list ordering
  // -------------------------------------------------------

  // @Test
  // fun backends_embeddedAlwaysFirst() = runTest {
  //   val factory = FakeBackendFactory()
  //   val manager = BackendManager(factory)
  //   manager.addRemote("host1")
  //   manager.addRemote("host2")
  //   val list = manager.backends.value
  //   assertEquals(
  //     "embedded",
  //     list.first().id,
  //     "Embedded should always be first"
  //   )
  //   manager.close()
  // }
}
