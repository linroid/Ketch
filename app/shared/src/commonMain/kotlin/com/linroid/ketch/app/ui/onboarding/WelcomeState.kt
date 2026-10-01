package com.linroid.ketch.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.linroid.ketch.config.UiPreferences

/** Version of the [WelcomeFlow]; `UiPreferences.onboardingVersion` below it shows the flow. */
internal const val WELCOME_VERSION: Int = 1

/** Whether the app greets the user with the [WelcomeFlow] on [platform] before anything else. */
internal fun needsWelcome(platform: WelcomePlatform?, ui: UiPreferences): Boolean =
  platform != null && ui.onboardingVersion < WELCOME_VERSION

/** A screen of the [WelcomeFlow], in order. */
internal enum class WelcomeStep {
  /** Where downloads go. */
  Folder,

  /** Downloading on this device or controlling a computer. */
  Use,

  /** How links reach Ketch from other apps, and when it asks to notify. */
  Intake,
}

/** How the user means to use Ketch on this device, chosen on [WelcomeStep.Use]. */
internal enum class WelcomeUse {
  /** Downloads run on this device. */
  Download,

  /** This device adds downloads to Ketch on a computer. */
  Control,
}

/**
 * Where the [WelcomeFlow] is: its step, the folder chosen on the first one and why choosing it
 * failed, if it did.
 *
 * @param step the step to start on.
 * @param folder the download folder already chosen, such as a `content://` tree URI.
 */
@Stable
internal class WelcomeState(
  step: WelcomeStep = WelcomeStep.Folder,
  folder: String? = null,
) {
  /** The step shown. */
  var step: WelcomeStep by mutableStateOf(step)
    private set

  /** The download folder chosen on [WelcomeStep.Folder]; `null` until one is. */
  var folder: String? by mutableStateOf(folder)

  /** Why the folder could not be used; `null` unless choosing it failed. */
  var folderError: String? by mutableStateOf(null)

  /** Whether the folder picker is open or the folder is being applied. */
  var choosingFolder: Boolean by mutableStateOf(false)

  /** Position of [step] among the [WelcomeStep]s, from 0. */
  val index: Int get() = step.ordinal

  /** Whether there is a step before this one to go back to. */
  val canGoBack: Boolean get() = index > 0

  /** Shows the next step; returns `false` on the last one, where the flow ends instead. */
  fun next(): Boolean {
    val next = WelcomeStep.entries.getOrNull(index + 1) ?: return false
    step = next
    return true
  }

  /** Shows the step before; returns `false` on the first one. */
  fun back(): Boolean {
    if (!canGoBack) return false
    step = WelcomeStep.entries[index - 1]
    return true
  }

  /**
   * Moves on after the user chose [use] on [WelcomeStep.Use]: to the ways of sending links when
   * downloading here.
   *
   * @return `true` when the flow goes on, `false` when it ends, as it does for controlling a
   *   computer, whose pairing takes over.
   */
  fun choose(use: WelcomeUse): Boolean = when (use) {
    WelcomeUse.Download -> next()
    WelcomeUse.Control -> false
  }

  companion object {
    /** Keeps the step and the chosen folder, as across Android's activity recreation. */
    val Saver: Saver<WelcomeState, Any> = listSaver(
      save = { listOf(it.step.ordinal, it.folder) },
      restore = { saved ->
        WelcomeState(WelcomeStep.entries[saved[0] as Int], saved[1] as String?)
      },
    )
  }
}

/** A [WelcomeState] on the first step that survives the activity being recreated. */
@Composable
internal fun rememberWelcomeState(): WelcomeState =
  rememberSaveable(saver = WelcomeState.Saver) { WelcomeState() }
