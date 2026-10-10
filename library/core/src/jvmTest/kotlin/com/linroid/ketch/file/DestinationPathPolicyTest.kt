package com.linroid.ketch.file

import com.linroid.ketch.api.Destination
import com.linroid.ketch.core.file.DestinationPathPolicy
import com.linroid.ketch.core.file.PathRejectedException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Paths are resolved against a real file system, including symbolic links, so this runs on the
// JVM.
class DestinationPathPolicyTest {
  private val temp = Files.createTempDirectory("ketch-policy").toFile().canonicalFile
  private val root = File(temp, "downloads").apply { mkdirs() }
  private val outside = File(temp, "outside").apply { mkdirs() }
  private val policy = DestinationPathPolicy(listOf(root.path))

  @AfterTest
  fun cleanup() {
    temp.deleteRecursively()
  }

  @Test
  fun contains_rootAndPathsBelowIt_true() {
    assertTrue(policy.contains(root.path))
    assertTrue(policy.contains("${root.path}/sub/file.zip"))
  }

  @Test
  fun contains_siblingSharingTheRootsPrefix_false() {
    assertFalse(policy.contains("${root.path}2/file.zip"))
  }

  @Test
  fun contains_dotDotLeavingTheRoot_false() {
    assertFalse(policy.contains("${root.path}/../outside/file.zip"))
  }

  @Test
  fun contains_rootGivenThroughALink_followsTheLink() {
    val link = File(temp, "link-to-downloads")
    Files.createSymbolicLink(link.toPath(), root.toPath())
    val throughLink = DestinationPathPolicy(listOf(link.path))

    assertTrue(throughLink.contains("${root.path}/file.zip"))
  }

  @Test
  fun confinesTo_theFreeNameConfineGaveItEarlier_true() {
    File(root, "file.zip").writeText("existing")
    val given = Destination("${root.path}/file.zip")
    val earlier = policy.confine(given, root.path)
    File(root, "file (1).zip").writeText("downloading")

    assertEquals(Destination(File(root, "file (1).zip").path), earlier)
    assertTrue(policy.confinesTo(given, root.path, earlier))
    assertTrue(policy.confinesTo(Destination("file.zip"), root.path, Destination("file.zip")))
  }

  @Test
  fun confinesTo_anotherFileOrFolder_false() {
    val given = Destination("${root.path}/file.zip")

    assertFalse(policy.confinesTo(given, root.path, Destination("${root.path}/other (1).zip")))
    assertFalse(policy.confinesTo(given, root.path, Destination("${root.path}/sub/file (1).zip")))
    assertFalse(policy.confinesTo(given, root.path, Destination("${root.path}/file (x).zip")))
    assertFalse(policy.confinesTo(Destination("${root.path}/a/"), root.path, Destination("b/")))
  }

  @Test
  fun confinesTo_outsideTheRoots_rejected() {
    assertFailsWith<PathRejectedException> {
      policy.confinesTo(
        Destination("${outside.path}/file.zip"),
        root.path,
        Destination("${outside.path}/file.zip"),
      )
    }
  }

  @Test
  fun confine_folderOutsideTheRoots_rejected() {
    assertFailsWith<PathRejectedException> {
      policy.confine(Destination("${outside.path}/"), root.path)
    }
  }

  @Test
  fun confine_fileOutsideTheRoots_rejected() {
    assertFailsWith<PathRejectedException> {
      policy.confine(Destination("${outside.path}/evil.sh"), root.path)
    }
  }

  @Test
  fun confine_dotDotOutOfTheBaseDirectory_rejected() {
    assertFailsWith<PathRejectedException> {
      policy.confine(Destination("../outside/evil.sh"), root.path)
    }
  }

  @Test
  fun confine_relativePath_resolvedAgainstTheBaseDirectory() {
    val confined = policy.confine(Destination("sub/./file.zip"), root.path)

    assertEquals(File(root, "sub/file.zip").path, confined.value)
  }

  @Test
  fun confine_folder_keepsTheTrailingSeparator() {
    val confined = policy.confine(Destination("${root.path}/sub/../movies/"), root.path)

    assertEquals("${File(root, "movies").path}/", confined.value)
  }

  @Test
  fun confine_pathOfAnExistingFolder_takenAsThatFolder() {
    File(root, "movies").mkdirs()

    val confined = policy.confine(Destination("${root.path}/movies"), root.path)

    assertEquals("${File(root, "movies").path}/", confined.value)
  }

  @Test
  fun confine_existingFile_getsAFreeName() {
    File(root, "file.zip").writeText("keep")

    val confined = policy.confine(Destination("${root.path}/file.zip"), root.path)

    assertEquals(File(root, "file (1).zip").path, confined.value)
  }

  @Test
  fun confine_fileName_sanitized() {
    val confined = policy.confine(Destination("${root.path}/a:b?.zip"), root.path)

    assertEquals(File(root, "a_b_.zip").path, confined.value)
  }

  @Test
  fun confine_bareName_sanitizedAndKeptForTheEngine() {
    assertEquals(Destination("download"), policy.confine(Destination(".."), root.path))
    assertEquals(Destination("a_b.zip"), policy.confine(Destination("a|b.zip"), root.path))
  }

  @Test
  fun confine_bareNameWhenTheBaseDirectoryIsOutside_rejected() {
    assertFailsWith<PathRejectedException> {
      policy.confine(Destination("file.zip"), outside.path)
    }
  }

  @Test
  fun confine_linkInsideTheRootPointingOutside_rejected() {
    val link = File(root, "elsewhere")
    Files.createSymbolicLink(link.toPath(), outside.toPath())

    assertFailsWith<PathRejectedException> {
      policy.confine(Destination("${link.path}/evil.sh"), root.path)
    }
  }

  @Test
  fun confine_throughALinkWhoseTargetIsMissing_rejected() {
    val link = File(root, "dangling")
    Files.createSymbolicLink(link.toPath(), File(outside, "missing").toPath())

    assertFailsWith<PathRejectedException> {
      policy.confine(Destination("${link.path}/evil.sh"), root.path)
    }
  }

  @Test
  fun confine_nulCharacter_rejected() {
    assertFailsWith<PathRejectedException> {
      policy.confine(Destination("${root.path}/a\u0000.zip"), root.path)
    }
  }

  @Test
  fun confine_uriBelowARootUri_keptAsItIs() {
    val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    val uris = DestinationPathPolicy(listOf(tree))
    val document = "$tree/document/primary%3ADownload%2Ffile.zip"

    assertEquals(Destination(tree), uris.confine(Destination(tree), tree))
    assertEquals(Destination(document), uris.confine(Destination(document), tree))
    assertFailsWith<PathRejectedException> {
      uris.confine(Destination("${tree}2"), tree)
    }
  }

  @Test
  fun confineFile_fileInsideTheRoots_normalized() {
    assertEquals(
      File(root, "file.zip").path,
      policy.confineFile("${root.path}/sub/../file.zip"),
    )
  }

  @Test
  fun confineFile_relativePath_rejected() {
    assertFailsWith<PathRejectedException> { policy.confineFile("file.zip") }
  }

  @Test
  fun confineFile_outsideTheRoots_rejected() {
    assertFailsWith<PathRejectedException> { policy.confineFile("${outside.path}/file.zip") }
  }

  @Test
  fun confineFile_folder_rejected() {
    assertFailsWith<PathRejectedException> { policy.confineFile(root.path) }
  }
}
