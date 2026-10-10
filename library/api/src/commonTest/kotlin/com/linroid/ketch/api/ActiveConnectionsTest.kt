package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ActiveConnectionsTest {
  @Test
  fun direction_unknownValue_decodesAsOutgoing() {
    val json = """{"id":1,"taskId":"t","source":"torrent","host":"10.0.0.2",""" +
      """"direction":"relayed","openedAt":"2026-10-01T14:30:00Z"}"""

    val connection = Json.decodeFromString(ActiveConnection.serializer(), json)

    assertEquals(ConnectionDirection.OUTGOING, connection.direction)
  }

  @Test
  fun route_unknownValue_decodesAsUnknown() {
    val json = """{"id":1,"taskId":"t","source":"http","host":"example.com",""" +
      """"route":"tor","openedAt":"2026-10-01T14:30:00Z"}"""

    val connection = Json.decodeFromString(ActiveConnection.serializer(), json)

    assertEquals(ConnectionRoute.UNKNOWN, connection.route)
  }

  @Test
  fun enums_encodeLowerCaseNames_andDecodeThem() {
    val connection = ActiveConnection(
      id = 7,
      taskId = "t",
      source = "torrent",
      direction = ConnectionDirection.INCOMING,
      host = "10.0.0.2",
      route = ConnectionRoute.SOCKS5_PROXY,
      openedAt = Instant.fromEpochMilliseconds(0),
    )

    val json = Json.encodeToString(ActiveConnection.serializer(), connection)

    assertTrue("\"direction\":\"incoming\"" in json, json)
    assertTrue("\"route\":\"socks5_proxy\"" in json, json)
    assertEquals(connection, Json.decodeFromString(ActiveConnection.serializer(), json))
  }

  @Test
  fun snapshot_withoutOptionalFields_decodesDefaults() {
    // The oldest shape: no route, peer, protocol or counters.
    val json = """{"sampledAt":"2026-10-01T14:30:00Z","connections":[""" +
      """{"id":1,"taskId":"t","source":"http","host":"example.com",""" +
      """"openedAt":"2026-10-01T14:29:00Z"}]}"""

    val snapshot = Json.decodeFromString(ActiveConnections.serializer(), json)

    val connection = snapshot.connections.single()
    assertEquals(ConnectionRoute.UNKNOWN, connection.route)
    assertEquals(ConnectionDirection.OUTGOING, connection.direction)
    assertNull(connection.peer)
    assertNull(connection.port)
    assertEquals(0, snapshot.total)
  }
}
