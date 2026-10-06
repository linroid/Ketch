package com.linroid.ketch.app.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import java.io.File

/**
 * Private handoff to Android's installer. Activity result registration survives rotation and
 * process recreation while the user grants permission; denial never opens the installer.
 */
internal class UpdateInstallActivity : ComponentActivity() {
  private val permission = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    if (packageManager.canRequestPackageInstalls()) openInstaller() else finish()
  }
  private val installer = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { finish() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) return
    attempt {
      if (packageManager.canRequestPackageInstalls()) {
        openInstaller()
      } else {
        permission.launch(
          Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
        )
      }
    }
  }

  private fun openInstaller() = attempt {
    val file = File(cacheDir, "$UPDATES_DIR/$UPDATE_APK")
    check(file.isFile) { "The downloaded APK is no longer in the cache" }
    val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
    installer.launch(
      Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
  }

  private fun attempt(action: () -> Unit) {
    try {
      action()
    } catch (e: Exception) {
      KetchLogger("UpdateInstaller").w { "Couldn't open the installer: ${e.describeCauses()}" }
      Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show()
      finish()
    }
  }
}

/** Only the direct build's private update cache is exposed, with temporary read grants. */
internal class UpdateFileProvider : FileProvider()
