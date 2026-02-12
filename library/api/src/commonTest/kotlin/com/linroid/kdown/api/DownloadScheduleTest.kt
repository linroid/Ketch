package com.linroid.kdown.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DownloadScheduleTest {

  @Test
  fun immediate_isDefaultSchedule() {
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      directory = "/tmp",
    )
    assertEquals(DownloadSchedule.Immediate, request.schedule)
  }

  @Test
  fun atTime_storesInstant() {
    val time = Clock.System.now() + 1.hours
    val schedule = DownloadSchedule.AtTime(time)
    assertEquals(time, schedule.startAt)
  }

  @Test
  fun afterDelay_storesDuration() {
    val delay = 30.minutes
    val schedule = DownloadSchedule.AfterDelay(delay)
    assertEquals(delay, schedule.delay)
  }

  @Test
  fun atTime_equality() {
    val time = Clock.System.now() + 1.hours
    val a = DownloadSchedule.AtTime(time)
    val b = DownloadSchedule.AtTime(time)
    assertEquals(a, b)
  }

  @Test
  fun afterDelay_equality() {
    val a = DownloadSchedule.AfterDelay(5.seconds)
    val b = DownloadSchedule.AfterDelay(5.seconds)
    assertEquals(a, b)
  }

  @Test
  fun atTime_differentTimes_notEqual() {
    val a = DownloadSchedule.AtTime(
      Clock.System.now() + 1.hours
    )
    val b = DownloadSchedule.AtTime(
      Clock.System.now() + 2.hours
    )
    assertNotEquals(a, b)
  }

  @Test
  fun afterDelay_differentDurations_notEqual() {
    val a = DownloadSchedule.AfterDelay(5.seconds)
    val b = DownloadSchedule.AfterDelay(10.seconds)
    assertNotEquals(a, b)
  }

  @Test
  fun different_types_are_not_equal() {
    val immediate: DownloadSchedule = DownloadSchedule.Immediate
    val afterDelay: DownloadSchedule =
      DownloadSchedule.AfterDelay(0.seconds)
    assertNotEquals(immediate, afterDelay)
  }

  @Test
  fun sealedClass_subtypes_castCorrectly() {
    val schedules: List<DownloadSchedule> = listOf(
      DownloadSchedule.Immediate,
      DownloadSchedule.AtTime(Clock.System.now()),
      DownloadSchedule.AfterDelay(5.minutes)
    )
    assertIs<DownloadSchedule.Immediate>(schedules[0])
    assertIs<DownloadSchedule.AtTime>(schedules[1])
    assertIs<DownloadSchedule.AfterDelay>(schedules[2])
  }
}
