package com.linroid.kdown

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedLimitTest {

  @Test
  fun unlimited_hasZeroBytesPerSecond() {
    assertEquals(0L, SpeedLimit.Unlimited.bytesPerSecond)
  }

  @Test
  fun unlimited_isUnlimited() {
    assertTrue(SpeedLimit.Unlimited.isUnlimited)
  }

  @Test
  fun of_nonZero_isNotUnlimited() {
    assertFalse(SpeedLimit.of(1024).isUnlimited)
  }

  @Test
  fun of_createsWithBytesPerSecond() {
    val limit = SpeedLimit.of(5000)
    assertEquals(5000L, limit.bytesPerSecond)
  }

  @Test
  fun of_zeroBytesPerSecond_throws() {
    assertFailsWith<IllegalArgumentException> {
      SpeedLimit.of(0)
    }
  }

  @Test
  fun of_negativeBytesPerSecond_throws() {
    assertFailsWith<IllegalArgumentException> {
      SpeedLimit.of(-1)
    }
  }

  @Test
  fun kbps_multipliesBy1024() {
    val limit = SpeedLimit.kbps(10)
    assertEquals(10 * 1024L, limit.bytesPerSecond)
  }

  @Test
  fun mbps_multipliesBy1024Squared() {
    val limit = SpeedLimit.mbps(2)
    assertEquals(2 * 1024 * 1024L, limit.bytesPerSecond)
  }

  @Test
  fun kbps_zero_throws() {
    assertFailsWith<IllegalArgumentException> {
      SpeedLimit.kbps(0)
    }
  }

  @Test
  fun mbps_zero_throws() {
    assertFailsWith<IllegalArgumentException> {
      SpeedLimit.mbps(0)
    }
  }

  @Test
  fun kbps_negative_throws() {
    assertFailsWith<IllegalArgumentException> {
      SpeedLimit.kbps(-1)
    }
  }

  @Test
  fun mbps_negative_throws() {
    assertFailsWith<IllegalArgumentException> {
      SpeedLimit.mbps(-1)
    }
  }

  @Test
  fun of_oneByte_isSmallestLimit() {
    val limit = SpeedLimit.of(1)
    assertEquals(1L, limit.bytesPerSecond)
    assertFalse(limit.isUnlimited)
  }

  @Test
  fun of_largeValue_isPreserved() {
    val limit = SpeedLimit.of(Long.MAX_VALUE)
    assertEquals(Long.MAX_VALUE, limit.bytesPerSecond)
    assertFalse(limit.isUnlimited)
  }

  @Test
  fun equality_sameValue() {
    assertEquals(SpeedLimit.of(1024), SpeedLimit.of(1024))
  }

  @Test
  fun equality_differentValue() {
    assertFalse(SpeedLimit.of(1024) == SpeedLimit.of(2048))
  }

  @Test
  fun kbps_one_equals1024Bytes() {
    assertEquals(SpeedLimit.of(1024), SpeedLimit.kbps(1))
  }

  @Test
  fun mbps_one_equals1048576Bytes() {
    assertEquals(SpeedLimit.of(1024 * 1024), SpeedLimit.mbps(1))
  }

  @Test
  fun unlimited_equalsAnotherUnlimited() {
    assertEquals(SpeedLimit.Unlimited, SpeedLimit.Unlimited)
  }

  @Test
  fun of_longMinValue_throws() {
    assertFailsWith<IllegalArgumentException> {
      SpeedLimit.of(Long.MIN_VALUE)
    }
  }

  @Test
  fun serialization_unlimited_roundTrips() {
    val json = Json
    val serialized = json.encodeToString(
      SpeedLimit.serializer(), SpeedLimit.Unlimited
    )
    val deserialized = json.decodeFromString(
      SpeedLimit.serializer(), serialized
    )
    assertEquals(SpeedLimit.Unlimited, deserialized)
    assertTrue(deserialized.isUnlimited)
  }

  @Test
  fun serialization_specificLimit_roundTrips() {
    val json = Json
    val original = SpeedLimit.of(1024)
    val serialized = json.encodeToString(
      SpeedLimit.serializer(), original
    )
    val deserialized = json.decodeFromString(
      SpeedLimit.serializer(), serialized
    )
    assertEquals(original, deserialized)
    assertEquals(1024L, deserialized.bytesPerSecond)
  }

  @Test
  fun serialization_serializesAsLong() {
    val json = Json
    val serialized = json.encodeToString(
      SpeedLimit.serializer(), SpeedLimit.of(42)
    )
    // Value class serializes as its underlying type
    assertEquals("42", serialized)
  }
}
