package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.feedback_downloads_unreadable
import ketch.app.shared.generated.resources.feedback_settings_unreadable
import ketch.app.shared.generated.resources.feedback_unreadable_kept
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A file Ketch could not read and moved aside, keeping it, so that it could start without it.
 *
 * @property kind what the file held, which picks what the app says.
 * @property movedTo where the file is now, or `null` when it could not be kept.
 */
data class UnreadableFile(val kind: Kind, val movedTo: String?) {
  enum class Kind {
    /** `config.toml`: the app started with the default settings. */
    Settings,

    /** The embedded device's download database: it started with an empty download list. */
    Downloads,
  }
}

/**
 * Files Ketch could not read and moved aside, such as a `config.toml` that does not parse. Hosts
 * [report] them as they find them, even before the UI exists, and the app tells the user about
 * each one once, with a warning that stays until dismissed.
 */
class UnreadableFiles {
  private val channel = Channel<UnreadableFile>(Channel.UNLIMITED)

  /** Files reported and not shown yet, each delivered once. Collect from one place only. */
  val reported: Flow<UnreadableFile> = channel.receiveAsFlow()

  /** Reports [file], from any thread. */
  fun report(file: UnreadableFile) {
    channel.trySend(file)
  }
}

/**
 * Tells the user that Ketch moved [file] aside. The detail names the file it became, beside the
 * original; a whole path would not fit a phone's toast, and the logs have it.
 */
internal fun MessageCenter.postUnreadable(file: UnreadableFile): AppMessage = post(
  level = MessageLevel.Warning,
  title = when (file.kind) {
    UnreadableFile.Kind.Settings -> Res.string.feedback_settings_unreadable.text()
    UnreadableFile.Kind.Downloads -> Res.string.feedback_downloads_unreadable.text()
  },
  detail = file.movedTo?.let { path ->
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    Res.string.feedback_unreadable_kept.text(verbatim(name))
  },
  deviceId = LOCAL_DEVICE_ID.takeIf { file.kind == UnreadableFile.Kind.Downloads },
  toast = ToastMode.Sticky,
)
