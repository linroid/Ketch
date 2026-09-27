package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res

private const val PROJECT_URL = "https://github.com/linroid/Ketch"

/** Version information and project links. */
@Composable
fun AboutSettings() {
  var showLicenses by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    SettingsGroup {
      SettingsRow(title = "Version", trailing = { MonoValue(KetchApi.VERSION) })
      SettingsRow(title = "Build", trailing = { MonoValue(KetchApi.REVISION) })
    }
    SettingsGroup(title = "Project") {
      LinkRow(
        title = "Source code",
        description = "github.com/linroid/Ketch",
        url = PROJECT_URL,
      )
      LinkRow(
        title = "Report a problem",
        description = "Open an issue on GitHub",
        url = "$PROJECT_URL/issues",
      )
      SettingsRow(
        title = "Open-source licenses",
        description = "Ketch and third-party notices",
        modifier = Modifier.clickable(role = Role.Button) { showLicenses = true },
      )
    }
  }
  if (showLicenses) {
    LicenseDialog(onDismiss = { showLicenses = false })
  }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
  var licenseText by remember { mutableStateOf("Loading licenses…") }
  LaunchedEffect(Unit) {
    licenseText = listOf("LICENSE.txt", "THIRD-PARTY-NOTICES.txt").map {
      Res.readBytes("files/licenses/$it").decodeToString()
    }.joinToString("\n\n")
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Open-source licenses") },
    text = {
      SelectionContainer {
        Text(
          text = licenseText,
          modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}

@Composable
private fun MonoValue(text: String) {
  Text(
    text = text,
    style = KetchTheme.typography.monoSmall,
    color = KetchTheme.colors.onSurfaceVariant,
  )
}

@Composable
private fun LinkRow(title: String, description: String, url: String) {
  val uriHandler = LocalUriHandler.current
  SettingsRow(
    title = title,
    description = description,
    modifier = Modifier.clickable(role = Role.Button) {
      runCatching { uriHandler.openUri(url) }
    },
    trailing = {
      KetchIconImage(KetchIcon.Chevron, size = 14.dp, tint = KetchTheme.colors.onSurfaceDim)
    },
  )
}
