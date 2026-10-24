package com.linroid.ketch.torrent

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.torrent.api.TorrentEncryptionSetting
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import com.linroid.ketch.torrent.api.TorrentUploadSetting

/**
 * This config with [settings] in place of its upload policy and limit, encryption, uTP, port
 * mapping, local discovery, extra trackers and tracker lists.
 */
fun TorrentConfig.withSettings(settings: TorrentEngineSettings): TorrentConfig = copy(
  uploadPolicy = settings.upload.toPolicy(),
  uploadRateLimit = settings.uploadLimit.bytesPerSecond,
  encryption = settings.encryption.toEncryption(),
  utpEnabled = settings.utp,
  portMapping = settings.portMapping,
  localDiscovery = settings.localDiscovery,
  additionalTrackers = settings.trackers,
  trackerListUrls = settings.trackerListUrls,
  trackerListsEnabled = settings.trackerList,
)

/** The settings this config runs torrents with; what [withSettings] sets. */
internal fun TorrentConfig.engineSettings(): TorrentEngineSettings = TorrentEngineSettings(
  upload = effectiveUploadPolicy.toSetting(),
  uploadLimit = speedLimitOf(uploadRateLimit),
  encryption = encryption.toSetting(),
  utp = utpEnabled,
  portMapping = portMapping,
  localDiscovery = localDiscovery,
  trackers = additionalTrackers,
  trackerList = trackerListsEnabled,
  trackerListUrls = trackerListUrls,
)

/** The tracker lists subscribed to: none while [TorrentEngineSettings.trackerList] is off. */
internal val TorrentEngineSettings.subscribedTrackerLists: List<String>
  get() = if (trackerList) trackerListUrls else emptyList()

internal fun TorrentUploadSetting.toPolicy(): TorrentUploadPolicy = when (this) {
  TorrentUploadSetting.OFF -> TorrentUploadPolicy.DISABLED
  TorrentUploadSetting.WHILE_DOWNLOADING -> TorrentUploadPolicy.WHILE_DOWNLOADING
  TorrentUploadSetting.SEED -> TorrentUploadPolicy.SEED_AFTER_COMPLETION
}

internal fun TorrentUploadPolicy.toSetting(): TorrentUploadSetting = when (this) {
  TorrentUploadPolicy.DISABLED -> TorrentUploadSetting.OFF
  TorrentUploadPolicy.WHILE_DOWNLOADING -> TorrentUploadSetting.WHILE_DOWNLOADING
  TorrentUploadPolicy.SEED_AFTER_COMPLETION -> TorrentUploadSetting.SEED
}

internal fun TorrentEncryptionSetting.toEncryption(): TorrentEncryption = when (this) {
  TorrentEncryptionSetting.PREFERRED -> TorrentEncryption.PREFERRED
  TorrentEncryptionSetting.REQUIRED -> TorrentEncryption.REQUIRED
  TorrentEncryptionSetting.OFF -> TorrentEncryption.DISABLED
}

internal fun TorrentEncryption.toSetting(): TorrentEncryptionSetting = when (this) {
  TorrentEncryption.PREFERRED -> TorrentEncryptionSetting.PREFERRED
  TorrentEncryption.REQUIRED -> TorrentEncryptionSetting.REQUIRED
  TorrentEncryption.DISABLED -> TorrentEncryptionSetting.OFF
}

/** [bytesPerSecond] as a speed limit; zero is unlimited. */
internal fun speedLimitOf(bytesPerSecond: Long): SpeedLimit =
  if (bytesPerSecond == 0L) SpeedLimit.Unlimited else SpeedLimit.of(bytesPerSecond)
