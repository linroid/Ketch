package com.linroid.ketch.app.android

import com.linroid.ketch.updater.ReleaseVersion
import com.linroid.ketch.updater.UpdateException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class UpdateApkTest {
  @Test
  fun validate_matchingReleaseWithHigherCode_accepts() {
    validate()
  }

  @Test
  fun validate_wrongPackage_rejects() {
    assertFailsWith<UpdateException> { validate(archivePackage = "another.app") }
  }

  @Test
  fun validate_wrongOrMissingVersion_rejects() {
    for (name in listOf("0.0.1", "invalid", null)) {
      assertFailsWith<UpdateException> { validate(archiveVersion = name) }
    }
  }

  @Test
  fun validate_sameOrOlderAndroidCode_rejectsEvenWithNewerVersionName() {
    for (code in listOf(10L, 9L)) {
      assertFailsWith<UpdateException> { validate(archiveCode = code) }
    }
  }

  private fun validate(
    archivePackage: String = "com.linroid.ketch.app",
    archiveVersion: String? = "0.0.2",
    archiveCode: Long = 11,
  ) = requireUpdateApk(
    packageName = "com.linroid.ketch.app",
    installedCode = 10,
    archivePackage = archivePackage,
    archiveCode = archiveCode,
    archiveVersion = archiveVersion,
    version = ReleaseVersion(0, 0, 2),
  )
}
