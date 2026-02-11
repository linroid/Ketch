package com.linroid.kdown.segment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentTest {

  @Test
  fun totalBytes_isEndMinusStartPlusOne() {
    val segment = Segment(index = 0, start = 0, end = 99)
    assertEquals(100, segment.totalBytes)
  }

  @Test
  fun totalBytes_singleByte() {
    val segment = Segment(index = 0, start = 50, end = 50)
    assertEquals(1, segment.totalBytes)
  }

  @Test
  fun currentOffset_withNoProgress() {
    val segment = Segment(index = 0, start = 100, end = 199, downloadedBytes = 0)
    assertEquals(100, segment.currentOffset)
  }

  @Test
  fun currentOffset_withPartialProgress() {
    val segment = Segment(index = 0, start = 100, end = 199, downloadedBytes = 50)
    assertEquals(150, segment.currentOffset)
  }

  @Test
  fun currentOffset_whenComplete() {
    val segment = Segment(index = 0, start = 100, end = 199, downloadedBytes = 100)
    assertEquals(200, segment.currentOffset)
  }

  @Test
  fun isComplete_whenAllBytesDownloaded() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 100)
    assertTrue(segment.isComplete)
  }

  @Test
  fun isComplete_whenMoreThanTotalDownloaded() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 150)
    assertTrue(segment.isComplete)
  }

  @Test
  fun isNotComplete_whenPartial() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 50)
    assertFalse(segment.isComplete)
  }

  @Test
  fun isNotComplete_whenZero() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 0)
    assertFalse(segment.isComplete)
  }

  @Test
  fun remainingBytes_full() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 0)
    assertEquals(100, segment.remainingBytes)
  }

  @Test
  fun remainingBytes_partial() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 30)
    assertEquals(70, segment.remainingBytes)
  }

  @Test
  fun remainingBytes_complete() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 100)
    assertEquals(0, segment.remainingBytes)
  }

  @Test
  fun defaultDownloadedBytes_isZero() {
    val segment = Segment(index = 0, start = 0, end = 99)
    assertEquals(0, segment.downloadedBytes)
  }
}
