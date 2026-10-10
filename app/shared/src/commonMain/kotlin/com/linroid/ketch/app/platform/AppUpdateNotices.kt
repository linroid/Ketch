package com.linroid.ketch.app.platform

import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_about_update_available
import ketch.app.shared.generated.resources.settings_about_update_install
import ketch.app.shared.generated.resources.settings_about_update_installed
import ketch.app.shared.generated.resources.settings_about_update_open_installer
import ketch.app.shared.generated.resources.settings_about_update_ready
import ketch.app.shared.generated.resources.settings_about_update_whats_new

/**
 * Automatic discoveries and finished downloads stay visible until the user acts or dismisses.
 * What's new on a discovery asks [showNotes] for the notes of the releases it brings.
 */
fun postAppUpdateNotice(
  state: AppUpdateState,
  messages: MessageCenter,
  updates: AppUpdates,
  showNotes: (ReleaseNotesRequest) -> Unit,
): Unit {
  when (state) {
    is AppUpdateState.Available -> messages.post(
      level = MessageLevel.Info,
      title = Res.string.settings_about_update_available.text(state.version),
      actions = listOf(
        MessageAction(Res.string.settings_about_update_install.text(), updates::download),
        MessageAction(Res.string.settings_about_update_whats_new.text()) {
          showNotes(ReleaseNotesRequest(state.version, since = updates.currentVersion))
        },
      ),
      toast = ToastMode.Sticky,
    )
    is AppUpdateState.Ready -> messages.post(
      level = MessageLevel.Success,
      title = Res.string.settings_about_update_ready.text(state.version),
      actions = listOf(
        MessageAction(Res.string.settings_about_update_open_installer.text(), updates::install),
      ),
      toast = ToastMode.Sticky,
    )
    else -> Unit
  }
}

/**
 * Tells that the app now runs [version], updated from [since] (`null` when unknown), with
 * What's new, which asks [showNotes] for the notes of the releases in between.
 */
fun postAppUpdatedNotice(
  version: String,
  since: String?,
  messages: MessageCenter,
  showNotes: (ReleaseNotesRequest) -> Unit,
): Unit {
  messages.post(
    level = MessageLevel.Success,
    title = Res.string.settings_about_update_installed.text(version),
    actions = listOf(
      MessageAction(Res.string.settings_about_update_whats_new.text()) {
        showNotes(ReleaseNotesRequest(version, since))
      },
    ),
  )
}
