package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.api.KetchFeatures
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_downloads_remote
import ketch.app.shared.generated.resources.settings_downloads_remote_saved
import ketch.app.shared.generated.resources.settings_speed_remote
import ketch.app.shared.generated.resources.settings_speed_remote_saved
import org.jetbrains.compose.resources.StringResource

/**
 * What the Downloads page says of a device that is not this one, given its [features]: that it
 * saves the changes, when its server lists [KetchFeatures.CONFIG_PERSISTED], else that it keeps
 * them until it restarts, as older servers do and as any is taken to do until it reports.
 */
internal fun downloadsRemoteNotice(features: Set<String>): StringResource =
  if (KetchFeatures.CONFIG_PERSISTED in features) {
    Res.string.settings_downloads_remote_saved
  } else {
    Res.string.settings_downloads_remote
  }

/** [downloadsRemoteNotice] for the Speed page, which also says the device has no speed mode. */
internal fun speedRemoteNotice(features: Set<String>): StringResource =
  if (KetchFeatures.CONFIG_PERSISTED in features) {
    Res.string.settings_speed_remote_saved
  } else {
    Res.string.settings_speed_remote
  }
