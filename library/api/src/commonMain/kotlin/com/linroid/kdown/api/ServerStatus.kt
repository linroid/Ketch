package com.linroid.kdown.api

import com.linroid.kdown.api.config.DownloadConfig
import kotlinx.serialization.Serializable

/**
 * Comprehensive status snapshot of a KDown instance.
 *
 * Both [KDown][com.linroid.kdown.core.KDown] (embedded) and
 * [RemoteKDown][com.linroid.kdown.remote.RemoteKDown] (HTTP)
 * return this same type from [KDownApi.status].
 *
 * @property version library version string
 * @property revision build revision (git short hash)
 * @property uptime seconds since the instance started
 * @property tasks task count breakdown by state
 * @property config current download configuration (with runtime speed limit)
 * @property server server network configuration (null for embedded-only)
 * @property system host system information
 * @property storage download directory storage information
 */
@Serializable
data class ServerStatus(
  val version: String,
  val revision: String,
  val uptime: Long,
  val tasks: TaskStats,
  val config: DownloadConfig,
  val server: ServerConfig? = null,
  val system: SystemInfo,
  val storage: StorageInfo,
)

/**
 * Task count breakdown by state.
 */
@Serializable
data class TaskStats(
  val total: Int,
  val active: Int,
  val downloading: Int,
  val paused: Int,
  val queued: Int,
  val pending: Int,
  val scheduled: Int,
  val completed: Int,
  val failed: Int,
  val canceled: Int,
)

/**
 * Server network configuration (sanitized — no secrets).
 *
 * @property host bind address
 * @property port listen port
 * @property authEnabled whether bearer-token auth is active
 * @property corsAllowedHosts allowed CORS origins
 * @property mdnsEnabled whether mDNS/DNS-SD registration is active
 */
@Serializable
data class ServerConfig(
  val host: String,
  val port: Int,
  val authEnabled: Boolean,
  val corsAllowedHosts: List<String>,
  val mdnsEnabled: Boolean,
)

/**
 * Host system information.
 *
 * @property os operating system name
 * @property arch CPU architecture
 * @property javaVersion JVM version (or "N/A" on non-JVM platforms)
 * @property availableProcessors number of available CPU cores
 * @property maxMemory max heap / physical memory in bytes
 * @property totalMemory current heap size / physical memory in bytes
 * @property freeMemory free memory in bytes (0 when unavailable)
 */
@Serializable
data class SystemInfo(
  val os: String,
  val arch: String,
  val javaVersion: String,
  val availableProcessors: Int,
  val maxMemory: Long,
  val totalMemory: Long,
  val freeMemory: Long,
)

/**
 * Download directory storage information.
 *
 * @property downloadDirectory resolved download directory path
 * @property totalSpace total disk space in bytes
 * @property freeSpace free disk space in bytes
 * @property usableSpace usable disk space in bytes
 */
@Serializable
data class StorageInfo(
  val downloadDirectory: String,
  val totalSpace: Long,
  val freeSpace: Long,
  val usableSpace: Long,
)
