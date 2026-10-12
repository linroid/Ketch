package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * A Ketch instance's status, from [KetchApi.status].
 *
 * @property revision the build's git short hash
 * @property uptime seconds since the instance started
 * @property features the optional behaviors it supports ([KetchFeatures]); empty from older
 *   versions
 */
@Serializable
data class KetchStatus(
  val name: String,
  val version: String,
  val revision: String,
  val uptime: Long,
  val config: DownloadConfig,
  val system: SystemInfo,
  val features: Set<String> = emptySet(),
)

/**
 * The instance's host system and storage. Sizes are in bytes.
 *
 * @property separator the file path separator
 * @property javaVersion the JVM version, or "N/A"
 * @property maxMemory the heap limit, or physical memory
 * @property totalMemory the heap size, or physical memory
 * @property freeMemory free memory; 0 when unknown
 * @property downloadDirectory where downloads are saved: [DownloadConfig.defaultDirectory], else
 *   [defaultDownloadDirectory]
 * @property defaultDownloadDirectory the platform's downloads folder; `null` from older servers
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
  val defaultDownloadDirectory: String? = null,
  val totalSpace: Long,
  val freeSpace: Long,
  val usableSpace: Long,
)
