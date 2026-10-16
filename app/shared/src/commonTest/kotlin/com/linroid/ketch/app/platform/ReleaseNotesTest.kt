package com.linroid.ketch.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {
  @Test
  fun releaseChanges_readsGitHubsGeneratedNotesAndSkipsTheRest() {
    val markdown = """
      ## Get Ketch

      | Your device | Download | Package |
      | --- | --- | --- |
      | **macOS** | [Apple silicon][download-1] | DMG |

      * Not a pull request

      ---

      ## What's Changed
      * fix(ai): call versioned endpoints by @linroid in https://x.test/pull/433
      * feat(app): pause and resume with a double-click by @linroid in https://x.test/pull/431
      * perf: shrink the releases by @linroid in https://x.test/pull/386

      ## New Contributors
      * @someone made their first contribution in https://x.test/pull/440

      **Full Changelog**: https://github.com/linroid/Ketch/compare/v0.3.0...v0.3.1
    """.trimIndent()

    assertEquals(
      listOf(
        ReleaseChange(ReleaseChangeKind.Fix, "Call versioned endpoints", "ai", pull(433)),
        ReleaseChange(
          ReleaseChangeKind.Feature,
          "Pause and resume with a double-click",
          "app",
          pull(431),
        ),
        ReleaseChange(ReleaseChangeKind.Improvement, "Shrink the releases", link = pull(386)),
      ),
      releaseChanges(markdown),
    )
  }

  @Test
  fun releaseChanges_leavesOutChangesOnlyDevelopersNotice() {
    val markdown = listOf(
      "chore: bump the version",
      "refactor(app): remove unused ProgressSection",
      "test: cover the parser",
      "fix(deps): update dependency io.ktor to v3.5.2",
      "chore(deps): update actions/checkout action to v6",
      "fix(release): mark the Windows ARM64 MSI as Arm64",
    ).joinToString("\n") { "* $it by @renovate[bot] in https://x.test/pull/1" }

    val summaries = releaseChanges(markdown).map { it.summary }

    assertEquals(listOf("Mark the Windows ARM64 MSI as Arm64"), summaries)
  }

  @Test
  fun releaseChanges_titleWithoutAType_guessesFromItsFirstWord() {
    val markdown = listOf(
      "Fix opening downloaded executables on Windows",
      "Add a page resource picker",
      "Download finite HLS and DASH media streams",
    ).joinToString("\n") { "* $it by @linroid in https://x.test/pull/2" }

    assertEquals(
      listOf(ReleaseChangeKind.Fix, ReleaseChangeKind.Feature, ReleaseChangeKind.Improvement),
      releaseChanges(markdown).map { it.kind },
    )
  }

  @Test
  fun releaseChanges_breakingChange_keepsItsType() {
    val markdown = "- feat(api)!: rename the task store by @linroid in https://x.test/pull/3"

    assertEquals(
      listOf(ReleaseChange(ReleaseChangeKind.Feature, "Rename the task store", "api", pull(3))),
      releaseChanges(markdown),
    )
  }

  @Test
  fun releaseChanges_handwrittenNotes_listNothing() {
    assertEquals(emptyList(), releaseChanges("Fixes a crash on start.\n\n- Faster downloads"))
  }

  @Test
  fun withoutRepeats_dropsChangesANewerReleaseListsAndReleasesLeftEmpty() {
    val fix = ReleaseChange(ReleaseChangeKind.Fix, "Fix it", link = pull(1))
    val feature = ReleaseChange(ReleaseChangeKind.Feature, "Add it", link = pull(2))
    val notes = listOf(
      ReleaseNotes("0.4.0", "page", changes = listOf(fix, feature)),
      ReleaseNotes("0.4.0-rc2", "page", changes = listOf(feature)),
      ReleaseNotes("0.4.0-rc1", "page", changes = listOf(fix, feature.copy(link = pull(3)))),
      ReleaseNotes("0.3.2", "page"),
    )

    assertEquals(
      listOf(
        ReleaseNotes("0.4.0", "page", changes = listOf(fix, feature)),
        ReleaseNotes("0.4.0-rc1", "page", changes = listOf(feature.copy(link = pull(3)))),
        ReleaseNotes("0.3.2", "page"),
      ),
      notes.withoutRepeats(),
    )
  }

  private fun pull(number: Int) = "https://x.test/pull/$number"
}
