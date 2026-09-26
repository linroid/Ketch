package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme

private const val PROJECT_URL = "https://github.com/linroid/Ketch"

/** Version information and project links. */
@Composable
fun AboutSettings() {
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
    }
  }
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
