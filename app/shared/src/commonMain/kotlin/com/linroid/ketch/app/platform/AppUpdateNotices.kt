package com.linroid.ketch.app.platform

import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_about_update_available
import ketch.app.shared.generated.resources.settings_about_update_install
import ketch.app.shared.generated.resources.settings_about_update_open_installer
import ketch.app.shared.generated.resources.settings_about_update_ready

/** Automatic discoveries and finished downloads stay visible until the user acts or dismisses. */
fun postAppUpdateNotice(state: AppUpdateState, messages: MessageCenter, updates: AppUpdates): Unit {
  when (state) {
    is AppUpdateState.Available -> messages.post(
      level = MessageLevel.Info,
      title = Res.string.settings_about_update_available.text(state.version),
      actions = listOf(
        MessageAction(Res.string.settings_about_update_install.text(), updates::download),
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
