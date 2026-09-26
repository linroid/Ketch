package com.linroid.ketch.app.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * Tracks which settings sections hold edits that are not saved yet, so the
 * settings dialog can ask before closing over them.
 *
 * A section keeps its flag while another section is shown: its draft is
 * still there when the user switches back. Remember it with [Saver] so the
 * flags survive state restoration along with the drafts.
 */
@Stable
class SettingsEdits {
  private val unsaved = mutableStateListOf<SettingsSection>()

  /** Sections with unsaved edits, in display order. */
  val unsavedSections: List<SettingsSection>
    get() = SettingsSection.entries.filter { it in unsaved }

  /** Whether the user is being asked to discard unsaved edits. */
  var confirmingDiscard by mutableStateOf(false)
    private set

  fun isUnsaved(section: SettingsSection): Boolean = section in unsaved

  /** Records whether [section]'s form differs from what is saved. */
  fun report(section: SettingsSection, hasUnsavedEdits: Boolean) {
    if (!hasUnsavedEdits) {
      unsaved.remove(section)
    } else if (section !in unsaved) {
      unsaved.add(section)
    }
  }

  /**
   * Asks to close the settings. Returns `true` when nothing is unsaved and
   * the caller may close right away; otherwise starts [confirmingDiscard].
   */
  fun requestClose(): Boolean {
    if (unsaved.isEmpty()) return true
    confirmingDiscard = true
    return false
  }

  /** Cancels the discard confirmation and keeps the edits. */
  fun keepEditing() {
    confirmingDiscard = false
  }

  /** Forgets every unsaved edit; the caller then closes the settings. */
  fun discard() {
    unsaved.clear()
    confirmingDiscard = false
  }

  companion object {
    /**
     * Saves the unsaved sections, so a section that is not on screen when
     * the state is restored is still guarded.
     */
    val Saver: Saver<SettingsEdits, Any> = listSaver(
      save = { edits -> edits.unsavedSections.map { it.name } },
      restore = { names ->
        SettingsEdits().apply {
          names.forEach { report(SettingsSection.valueOf(it), true) }
        }
      },
    )
  }
}
