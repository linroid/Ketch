package com.linroid.ketch.app.state

import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_example_audiobooks
import ketch.app.shared.generated.resources.discover_example_bootable_usb
import ketch.app.shared.generated.resources.discover_example_datasets
import ketch.app.shared.generated.resources.discover_example_films
import ketch.app.shared.generated.resources.discover_example_fonts
import ketch.app.shared.generated.resources.discover_example_footage
import ketch.app.shared.generated.resources.discover_example_games
import ketch.app.shared.generated.resources.discover_example_hdr_test
import ketch.app.shared.generated.resources.discover_example_lectures
import ketch.app.shared.generated.resources.discover_example_local_ai
import ketch.app.shared.generated.resources.discover_example_mars
import ketch.app.shared.generated.resources.discover_example_music
import ketch.app.shared.generated.resources.discover_example_office
import ketch.app.shared.generated.resources.discover_example_old_laptop
import ketch.app.shared.generated.resources.discover_example_paintings
import ketch.app.shared.generated.resources.discover_example_password_manager
import ketch.app.shared.generated.resources.discover_example_photo_editor
import ketch.app.shared.generated.resources.discover_example_planetarium
import ketch.app.shared.generated.resources.discover_example_podcast
import ketch.app.shared.generated.resources.discover_example_raspberry_pi
import ketch.app.shared.generated.resources.discover_example_screen_recorder
import ketch.app.shared.generated.resources.discover_example_sound_effects
import ketch.app.shared.generated.resources.discover_example_speed_test
import ketch.app.shared.generated.resources.discover_example_textures
import ketch.app.shared.generated.resources.discover_example_video_editor
import ketch.app.shared.generated.resources.discover_example_wikipedia
import kotlin.random.Random

/**
 * Searches an empty Discover page offers to try, [EXAMPLE_COUNT] at a time: software, free media
 * and files to test with. None names a platform, as the agent picks builds for the user's device.
 */
internal val ExampleSearches: List<UiText> = listOf(
  Res.string.discover_example_bootable_usb,
  Res.string.discover_example_photo_editor,
  Res.string.discover_example_video_editor,
  Res.string.discover_example_screen_recorder,
  Res.string.discover_example_podcast,
  Res.string.discover_example_password_manager,
  Res.string.discover_example_office,
  Res.string.discover_example_planetarium,
  Res.string.discover_example_games,
  Res.string.discover_example_old_laptop,
  Res.string.discover_example_raspberry_pi,
  Res.string.discover_example_local_ai,
  Res.string.discover_example_footage,
  Res.string.discover_example_music,
  Res.string.discover_example_audiobooks,
  Res.string.discover_example_films,
  Res.string.discover_example_fonts,
  Res.string.discover_example_sound_effects,
  Res.string.discover_example_textures,
  Res.string.discover_example_paintings,
  Res.string.discover_example_mars,
  Res.string.discover_example_lectures,
  Res.string.discover_example_wikipedia,
  Res.string.discover_example_hdr_test,
  Res.string.discover_example_speed_test,
  Res.string.discover_example_datasets,
).map { it.text() }

/** How many [ExampleSearches] an empty Discover page shows. */
internal const val EXAMPLE_COUNT = 3

/**
 * [EXAMPLE_COUNT] of [ExampleSearches] chosen with [random], none of them among [previous], so
 * each new search offers others.
 */
internal fun pickExamples(random: Random, previous: List<UiText> = emptyList()): List<UiText> =
  (ExampleSearches - previous.toSet()).shuffled(random).take(EXAMPLE_COUNT)
