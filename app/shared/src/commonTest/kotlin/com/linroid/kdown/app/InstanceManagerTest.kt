package com.linroid.kdown.app

import com.linroid.kdown.remote.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for InstanceManager instance switching logic.
 *
 * These tests are structured against the actual InstanceManager API:
 * - `switchTo(instance: InstanceEntry)` - switches by instance ref
 * - `addRemote(host, port, token)` - adds a remote entry
 * - `removeInstance(instance: InstanceEntry)` - removes an instance
 * - `activeInstance: StateFlow<InstanceEntry?>` - current entry
 * - `activeApi: StateFlow<KDownApi>` - current API instance
 * - `instances: StateFlow<List<InstanceEntry>>` - all entries
 * - `isLocalServerSupported: Boolean` - platform capability
 * - `serverState: StateFlow<ServerState>` - HTTP server state
 *
 * Test categories:
 *  1. FakeKDownApi (test double validation)
 *  2. ConnectionState model tests
 *  3. InstanceManager lifecycle
 *  4. Instance switching
 *  5. Connection state tracking
 *  6. Error handling on switch failure
 *  7. Concurrent safety
 *  8. Remove instance (auto-switch to embedded)
 *  9. Instance label propagation
 * 10. Instance list ordering
 *
 * TESTABILITY BLOCKER for categories 3-10:
 * InstanceManager takes `InstanceFactory` in its constructor, and
 * hardcodes `createEmbedded()` which creates a real KDown instance.
 * To unit-test InstanceManager, we need either:
 *   (a) Extract an interface from InstanceFactory and accept it in
 *       InstanceManager constructor, OR
 *   (b) Make InstanceManager accept an `embeddedFactory` lambda
 *
 * Once the testability refactor is done, uncomment categories 3-10
 * and replace `InstanceFactory` with `FakeInstanceFactory`.
 *
 * ## UI behaviors for future instrumented tests
 *
 * The following behaviors are pure Compose UI logic (not in
 * InstanceManager) and require `createComposeRule()` to test:
 *
 * - Port validation: "Add" button disabled when port outside
 *   1-65535 or host blank. Error text below port field.
 * - Double-tap guard: `switchingInstance` state prevents
 *   tapping other entries during an active switchTo() call.
 * - Sheet dismiss timing: sheet stays open on failure,
 *   only dismisses after successful switchTo() return.
 * - Connection status chip: only shown for RemoteInstance
 *   entries; inactive remotes show gray "Not connected";
 *   only active remote shows red "Disconnected" on
 *   connection loss (via `isActive` param on
 *   ConnectionStatusChip).
 */
class InstanceManagerTest {

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
  // 2. ConnectionState model tests
  // -------------------------------------------------------

  @Test
  fun connectionState_types() {
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
  fun connectionState_disconnected_nullReason() {
    val state = ConnectionState.Disconnected()
    assertEquals(null, state.reason)
  }

  @Test
  fun connectionState_connected_isSingleton() {
    val a = ConnectionState.Connected
    val b = ConnectionState.Connected
    assertEquals(a, b)
  }

  @Test
  fun connectionState_connecting_isSingleton() {
    val a = ConnectionState.Connecting
    val b = ConnectionState.Connecting
    assertEquals(a, b)
  }

  @Test
  fun connectionState_disconnected_equality() {
    val a = ConnectionState.Disconnected("timeout")
    val b = ConnectionState.Disconnected("timeout")
    assertEquals(a, b)
  }

  @Test
  fun connectionState_disconnected_inequality() {
    val a = ConnectionState.Disconnected("timeout")
    val b = ConnectionState.Disconnected("refused")
    assertNotEquals(a, b)
  }

  // -------------------------------------------------------
  // 3. InstanceManager lifecycle tests
  //
  // BLOCKED: Requires testability refactor of InstanceManager
  // (accept interface instead of InstanceFactory).
  // -------------------------------------------------------

  // @Test
  // fun initialInstance_isEmbedded() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory) // needs interface
  //   val active = manager.activeInstance.value
  //   assertTrue(active is EmbeddedInstance)
  //   manager.close()
  // }

  // @Test
  // fun initialInstanceList_containsOnlyEmbedded() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   val list = manager.instances.value
  //   assertEquals(1, list.size)
  //   assertTrue(list[0] is EmbeddedInstance)
  //   manager.close()
  // }

  // @Test
  // fun close_closesActiveApi() = runTest {
  //   val embedded = FakeKDownApi("Core")
  //   val factory = FakeInstanceFactory(
  //     embeddedFactory = { embedded }
  //   )
  //   val manager = InstanceManager(factory)
  //   manager.close()
  //   assertTrue(embedded.closed)
  //   assertEquals(1, embedded.closeCallCount)
  // }

  // -------------------------------------------------------
  // 4. Instance switching tests
  //
  // InstanceManager.switchTo(instance) accepts the instance
  // directly, sets activeApi + activeInstance, then closes
  // old API.
  // -------------------------------------------------------

  // @Test
  // fun switchTo_remote_updatesActiveInstance() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   val entry = manager.addRemote("localhost")
  //   manager.switchTo(entry)
  //   assertEquals(entry, manager.activeInstance.value)
  //   assertTrue(manager.activeInstance.value is RemoteInstance)
  //   manager.close()
  // }

  // @Test
  // fun switchTo_sameInstance_isNoOp() = runTest {
  //   val embedded = FakeKDownApi("Core")
  //   val factory = FakeInstanceFactory(
  //     embeddedFactory = { embedded }
  //   )
  //   val manager = InstanceManager(factory)
  //   val active = manager.activeInstance.value!!
  //   manager.switchTo(active)
  //   assertFalse(
  //     embedded.closed,
  //     "Same-instance switch should not close current API"
  //   )
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 5. Connection state tracking
  // -------------------------------------------------------

  // @Test
  // fun addRemote_connectionState_fromRemoteKDown() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   val entry = manager.addRemote("localhost")
  //   // RemoteInstance.connectionState delegates to
  //   // RemoteKDown.connectionState directly
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

  // -------------------------------------------------------
  // 7. Concurrent safety
  // -------------------------------------------------------

  // -------------------------------------------------------
  // 8. Remove instance (auto-switch to embedded)
  // -------------------------------------------------------

  // @Test
  // fun removeActiveRemote_switchesToEmbedded() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   val entry = manager.addRemote("10.0.0.1")
  //   manager.switchTo(entry)
  //   manager.removeInstance(entry)
  //   assertTrue(
  //     manager.activeInstance.value is EmbeddedInstance
  //   )
  //   assertFalse(
  //     manager.instances.value.contains(entry),
  //     "Removed entry should not be in instances list"
  //   )
  //   manager.close()
  // }

  // @Test
  // fun removeEmbedded_throws() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   val embedded = manager.activeInstance.value!!
  //   val result = runCatching {
  //     manager.removeInstance(embedded)
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
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   manager.addRemote("newhost", 9999)
  //   assertTrue(
  //     manager.activeInstance.value is EmbeddedInstance,
  //     "Adding remote should not switch active instance"
  //   )
  //   manager.close()
  // }

  // @Test
  // fun addRemote_appendsToInstancesList() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   val entry = manager.addRemote("host1")
  //   val list = manager.instances.value
  //   assertEquals(2, list.size)
  //   assertTrue(list[0] is EmbeddedInstance)
  //   assertEquals(entry, list[1])
  //   manager.close()
  // }

  // -------------------------------------------------------
  // 10. Instance list ordering
  // -------------------------------------------------------

  // @Test
  // fun instances_embeddedAlwaysFirst() = runTest {
  //   val factory = FakeInstanceFactory()
  //   val manager = InstanceManager(factory)
  //   manager.addRemote("host1")
  //   manager.addRemote("host2")
  //   val list = manager.instances.value
  //   assertTrue(
  //     list.first() is EmbeddedInstance,
  //     "Embedded should always be first"
  //   )
  //   manager.close()
  // }
}
