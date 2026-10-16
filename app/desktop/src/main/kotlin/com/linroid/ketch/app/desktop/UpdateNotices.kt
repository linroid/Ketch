package com.linroid.ketch.app.desktop

import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.platform.ReleaseNotesRequest
import ketch.app.desktop.generated.resources.Res
import ketch.app.desktop.generated.resources.message_open_installer
import ketch.app.desktop.generated.resources.message_restart
import ketch.app.desktop.generated.resources.message_update
import ketch.app.desktop.generated.resources.message_update_available
import ketch.app.desktop.generated.resources.message_update_failed
import ketch.app.desktop.generated.resources.message_update_failed_hint
import ketch.app.desktop.generated.resources.message_update_installed
import ketch.app.desktop.generated.resources.message_update_ready
import ketch.app.desktop.generated.resources.message_whats_new

/**
 * Tells the user what [event] reports as a toast, with buttons that take [updates] to its next
 * step and What's new, which asks [showNotes] for the notes of the releases it concerns. The
 * toasts that ask for a step stay until dismissed.
 */
internal fun postUpdateNotice(
  event: UpdateEvent,
  messages: MessageCenter,
  updates: AppUpdates,
  showNotes: (ReleaseNotesRequest) -> Unit,
) {
  when (event) {
    is UpdateEvent.Found -> {
      val update = event.update
      messages.post(
        level = MessageLevel.Info,
        title = Res.string.message_update_available.text(update.version),
        actions = listOfNotNull(
          MessageAction(Res.string.message_update.text(), updates::download)
            .takeIf { update.installable },
          MessageAction(Res.string.message_whats_new.text()) {
            showNotes(ReleaseNotesRequest(update.version, since = updates.currentVersion))
          },
        ),
        toast = ToastMode.Sticky,
      )
    }
    is UpdateEvent.Ready -> {
      val label = if (event.update.restarts) {
        Res.string.message_restart
      } else {
        Res.string.message_open_installer
      }
      messages.post(
        level = MessageLevel.Success,
        title = Res.string.message_update_ready.text(event.update.version),
        actions = listOf(MessageAction(label.text(), updates::install)),
        toast = ToastMode.Sticky,
      )
    }
    is UpdateEvent.Installed -> messages.post(
      level = MessageLevel.Success,
      title = Res.string.message_update_installed.text(event.version),
      actions = listOf(
        MessageAction(Res.string.message_whats_new.text()) {
          showNotes(ReleaseNotesRequest(event.version, event.since))
        },
      ),
    )
    is UpdateEvent.InstallFailed -> messages.post(
      level = MessageLevel.Error,
      title = Res.string.message_update_failed.text(event.version),
      detail = Res.string.message_update_failed_hint.text(),
      toast = ToastMode.Sticky,
    )
  }
}
