package com.linroid.ketch.app.ui.dialog

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.state.ListFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoveTasksDialogTest {
  private val gib = 1L shl 30
  private val mib = 1L shl 20
  private val file = RemovalItem("ubuntu.iso", hasFile = true, bytes = 2 * gib, trashable = true)

  @Test
  fun removeDialogCopy_unchecked_offersAPlainRemove() {
    val copy = removeDialogCopy(RemovalPlan(listOf(file), trash = true), "This Mac", false)

    assertEquals("Remove “ubuntu.iso”?", copy.title)
    assertEquals("From the list on This Mac.", copy.subtitle)
    assertEquals("Also move the file to the Trash · 2.00 GB", copy.checkbox)
    assertNull(copy.note)
    assertEquals("Remove", copy.confirm)
    assertFalse(copy.danger)
  }

  @Test
  fun removeDialogCopy_checkedWithTrash_trashesWithADangerButton() {
    val copy = removeDialogCopy(RemovalPlan(listOf(file), trash = true), "This Mac", true)

    assertEquals("Remove and trash 2.00 GB", copy.confirm)
    assertTrue(copy.danger)
    // The Trash keeps the space until it is emptied.
    assertNull(copy.note)
  }

  @Test
  fun removeDialogCopy_checkedWithoutTrash_deletesPermanentlyAndSaysWhatItFrees() {
    val copy = removeDialogCopy(
      plan = RemovalPlan(listOf(file), trash = false),
      deviceName = "NAS",
      checked = true,
      freeBytes = 48 * gib,
    )

    assertEquals("Also delete the file permanently · 2.00 GB", copy.checkbox)
    assertEquals("Frees 2.00 GB · 48.00 GB free on NAS", copy.note)
    assertEquals("Remove and delete 2.00 GB", copy.confirm)
    assertTrue(copy.danger)
  }

  @Test
  fun removeDialogCopy_partialFile_namesHowMuchOfItExists() {
    val partial = RemovalItem(
      name = "blender.dmg",
      hasFile = true,
      bytes = 812 * mib,
      totalBytes = 2 * gib,
      partial = true,
    )

    val copy = removeDialogCopy(RemovalPlan(listOf(partial), trash = false), "This Mac", true)

    assertEquals("Also delete the partial file permanently · 812.0 MB of 2.00 GB", copy.checkbox)
    assertEquals("Remove and delete 812.0 MB", copy.confirm)
  }

  @Test
  fun removeDialogCopy_multiFileTorrent_countsItsFiles() {
    val torrent = RemovalItem("archlinux", hasFile = true, bytes = 3 * gib, fileCount = 14)

    val copy = removeDialogCopy(RemovalPlan(listOf(torrent), trash = false), "This Mac", false)

    assertEquals("Also delete all 14 files permanently · 3.00 GB", copy.checkbox)
  }

  @Test
  fun removeDialogCopy_severalDownloads_sumsTheirFiles() {
    val items = listOf(file, file.copy(name = "debian.iso"), RemovalItem("q.pdf", hasFile = false))

    val copy = removeDialogCopy(RemovalPlan(items, trash = true), "This Mac", true)

    assertEquals("Remove 3 downloads?", copy.title)
    assertEquals("Also move the files to the Trash · 4.00 GB", copy.checkbox)
    assertEquals("Remove and trash 4.00 GB", copy.confirm)
  }

  @Test
  fun removeDialogCopy_noFiles_hasNoBox() {
    val queued = RemovalItem("q.pdf", hasFile = false)

    val copy = removeDialogCopy(RemovalPlan(listOf(queued), trash = true), "This Mac", true)

    assertNull(copy.checkbox)
    assertEquals("Remove", copy.confirm)
    assertFalse(copy.danger)
  }

  @Test
  fun initiallyChecked_shiftOpensItChecked() {
    val plan = RemovalPlan(listOf(file), trash = true)

    assertFalse(initiallyChecked(plan, withFiles = false))
    assertTrue(initiallyChecked(plan, withFiles = true))
    assertFalse(initiallyChecked(RemovalPlan(emptyList(), trash = true), withFiles = true))
  }

  @Test
  fun removeDialogCopy_finishedAndPartialWithTrash_saysThePartialFileIsDeleted() {
    val partial = RemovalItem("b.dmg", hasFile = true, bytes = 812 * mib, partial = true)

    val copy = removeDialogCopy(
      plan = RemovalPlan(listOf(file, partial), trash = true),
      deviceName = "This Mac",
      checked = true,
      freeBytes = 48 * gib,
    )

    assertEquals("Also move the files to the Trash · 2.79 GB", copy.checkbox)
    assertEquals("The partial file is deleted permanently · frees 812.0 MB", copy.note)
    assertEquals("Remove and trash 2.79 GB", copy.confirm)
  }

  @Test
  fun removalPlanOf_partialFile_isNotTrashedEvenWhereThereIsATrash() {
    val request = DownloadRequest("https://example.com/a.iso")
    val done = ListFixtures.row("a", DownloadState.Completed("/d/a.iso", 100), request = request)
    val paused = ListFixtures.row("b", DownloadState.Paused(DownloadProgress(40, 100)))

    assertTrue(RemovalPlan.of(listOf(done), canTrash = true).trash)
    assertFalse(RemovalPlan.of(listOf(paused), canTrash = true).trash)
    val mixed = RemovalPlan.of(listOf(done, paused), canTrash = true)
    assertTrue(mixed.trash)
    assertEquals(listOf(paused.name), mixed.deleted.map { it.name })
    assertEquals(140, mixed.bytes)
    assertFalse(RemovalPlan.of(listOf(done), canTrash = false).trash)
  }

  @Test
  fun removalPlanOf_torrentWithoutSelection_countsEveryFileOfTheSource() {
    val files = List(14) { SourceFile(id = "$it", name = "part$it.bin") }
    val source = ResolvedSource(
      url = "magnet:?xt=urn:btih:abc",
      sourceType = "torrent",
      totalBytes = 3 * gib,
      supportsResume = true,
      suggestedFileName = "archlinux",
      maxSegments = 1,
      files = files,
    )
    val request = DownloadRequest("magnet:?xt=urn:btih:abc", resolvedSource = source)
    val done = DownloadState.Completed("/d/archlinux", 3 * gib)

    val all = RemovalPlan.of(listOf(ListFixtures.row("a", done, request = request)), false)
    val some = request.copy(selectedFileIds = setOf("1", "2"))
    val picked = RemovalPlan.of(listOf(ListFixtures.row("b", done, request = some)), false)

    assertEquals(14, all.items.single().fileCount)
    assertEquals(2, picked.items.single().fileCount)
  }

  @Test
  fun removalPlanOf_canceledAndQueuedRows_haveNoFiles() {
    val canceled = ListFixtures.row("a", DownloadState.Canceled)
    val queued = ListFixtures.row("b", DownloadState.Queued)

    assertFalse(RemovalPlan.of(listOf(canceled, queued), canTrash = true).hasFiles)
  }
}
