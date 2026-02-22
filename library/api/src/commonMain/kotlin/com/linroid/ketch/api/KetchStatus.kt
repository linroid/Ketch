package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Comprehensive status snapshot of a Ketch instance, returned
 * by [KetchApi.status].
 *
 * @property name user-configured instance name
 * @property version library version string
 * @property revision build revision (git short hash)
 * @property uptime seconds since the instance started
 * @property config current download configuration (with runtime speed limit)
 * @property system host system and storage information
 */
@Serializable
data class KetchStatus(
  val name: String,
  val version: String,
  val revision: String,
  val uptime: Long,
  val config: DownloadConfig,
  val system: SystemInfo,
)

/**
 * Host system and storage information.
 *
 * @property os operating system name
 * @property arch CPU architecture
 * @property separator file path separator (`/` on Unix, `\` on Windows)
 * @property javaVersion JVM version (or "N/A" on non-JVM platforms)
 * @property availableProcessors number of available CPU cores
 * @property maxMemory max heap / physical memory in bytes
 * @property totalMemory current heap size / physical memory in bytes
 * @property freeMemory free memory in bytes (0 when unavailable)
 * @property downloadDirectory resolved download directory path
 * @property totalSpace total disk space in bytes
 * @property freeSpace free disk space in bytes
 * @property usableSpace usable disk space in bytes
 */
@Serializable
data class SystemInfo(
  val os: String,
  val arch: String,
  val separator: String,
  val javaVersion: String,
  val availableProcessors: Int,
  val maxMemory: Long,
  val totalMemory: Long,
  val freeMemory: Long,
  val downloadDirectory: String,
  val totalSpace: Long,
  val freeSpace: Long,
  val usableSpace: Long,
)
