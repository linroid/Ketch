package com.linroid.ketch.app.android

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.platform.postAppUpdateNotice
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.updater.GitHubReleases
import com.linroid.ketch.updater.ReleaseDownloader
import com.linroid.ketch.updater.ReleaseVersion
import com.linroid.ketch.updater.UpdateException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The Play source set supplies a null provider instead, with no updater dependency. */
internal fun createAppUpdates(
  scope: CoroutineScope,
  app: KetchApplication,
  messages: MessageCenter,
): AppUpdates {
  val downloader = ReleaseDownloader(
    httpEngine = { KtorHttpEngine() },
    logger = Logger.combine(Logger.console(LogLevel.DEBUG), app.fileLogger),
  )
  lateinit var updater: AndroidUpdater
  updater = AndroidUpdater(
    scope = scope,
    feed = GitHubReleases(httpEngine = { KtorHttpEngine() }),
    current = ReleaseVersion.parse(BuildConfig.VERSION_NAME),
    workDir = File(app.cacheDir, UPDATES_DIR),
    download = downloader::download,
    validate = { file, version ->
      withContext(Dispatchers.IO) {
        validateUpdateApk(app.packageManager, app.packageName, file, version)
      }
    },
    openInstaller = {
      app.startActivity(
        Intent(app, UpdateInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    },
    automaticChecks = !BuildConfig.DEBUG,
    onNotice = { state -> postAppUpdateNotice(state, messages, updater) },
  )
  updater.setCheckAutomatically(app.configStore.load().desktop.checkForUpdates)
  return updater
}

/** Android itself verifies the signing certificate when replacing the installed package. */
@Suppress("DEPRECATION")
internal fun validateUpdateApk(
  manager: PackageManager,
  packageName: String,
  file: File,
  version: ReleaseVersion,
) {
  val archive = manager.getPackageArchiveInfo(file.path, 0)
    ?: throw UpdateException("The downloaded APK could not be read")
  val installed = manager.getPackageInfo(packageName, 0)
  requireUpdateApk(
    packageName = packageName,
    installedCode = PackageInfoCompat.getLongVersionCode(installed),
    archivePackage = archive.packageName,
    archiveCode = PackageInfoCompat.getLongVersionCode(archive),
    archiveVersion = archive.versionName,
    version = version,
  )
}

/** Requires the requested release of this package and an Android upgrade, never a downgrade. */
internal fun requireUpdateApk(
  packageName: String,
  installedCode: Long,
  archivePackage: String,
  archiveCode: Long,
  archiveVersion: String?,
  version: ReleaseVersion,
) {
  if (archivePackage != packageName || archiveVersion?.let(ReleaseVersion::parse) != version) {
    throw UpdateException("The downloaded APK does not match this app and release")
  }
  if (archiveCode <= installedCode) {
    throw UpdateException("The downloaded APK is not newer than the installed build")
  }
}
