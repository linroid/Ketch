package com.linroid.ketch.app.ui.dialog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.ResolveState
import com.linroid.ketch.app.ui.common.PriorityIcon
import com.linroid.ketch.app.ui.common.PrioritySelector
import com.linroid.ketch.app.ui.common.ScheduleIcon
import com.linroid.ketch.app.ui.common.ScheduleSelector
import com.linroid.ketch.app.ui.common.SpeedLimitIcon
import com.linroid.ketch.app.ui.common.SpeedLimitSelector
import com.linroid.ketch.app.util.formatBytes
import kotlinx.coroutines.delay

private enum class DialogPanel {
  None, SpeedLimit, Priority, Schedule
}

@Composable
fun AddDownloadDialog(
  resolveState: ResolveState,
  onResolveUrl: (String) -> Unit,
  onResetResolve: () -> Unit,
  onDismiss: () -> Unit,
  onDownload: (
    url: String,
    fileName: String,
    SpeedLimit,
    DownloadPriority,
    DownloadSchedule,
    ResolvedSource?,
  ) -> Unit,
) {
  var url by remember { mutableStateOf("") }
  var fileName by remember { mutableStateOf("") }
  var fileNameEditedByUser by remember {
    mutableStateOf(false)
  }
  var selectedSpeed by remember {
    mutableStateOf(SpeedLimit.Unlimited)
  }
  var selectedPriority by remember {
    mutableStateOf(DownloadPriority.NORMAL)
  }
  var selectedSchedule by remember {
    mutableStateOf<DownloadSchedule>(
      DownloadSchedule.Immediate
    )
  }
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var expanded by remember {
    mutableStateOf(DialogPanel.None)
  }
  val urlFocusRequester = remember {
    FocusRequester()
  }
  val needsAuth =
    resolveState is ResolveState.Error &&
      resolveState.cause is KetchError.AuthenticationFailed

  // Track the last resolved URL to avoid re-resolving
  var lastResolvedSource by remember {
    mutableStateOf("")
  }

  fun buildResolveUrl(): String {
    val trimmed = url.trim()
    if (username.isBlank()) return trimmed
    return embedCredentials(trimmed, username, password)
  }

  // Debounce: auto-resolve when URL is non-blank
  LaunchedEffect(url) {
    val trimmed = url.trim()
    if (trimmed.isNotBlank() && trimmed != lastResolvedSource) {
      delay(500)
      val resolveUrl = buildResolveUrl()
      lastResolvedSource = resolveUrl
      onResolveUrl(resolveUrl)
    } else if (trimmed.isBlank()) {
      if (lastResolvedSource.isNotEmpty()) {
        lastResolvedSource = ""
        onResetResolve()
      }
    }
  }

  // Pre-fill filename from resolved result
  val resolved = (resolveState as? ResolveState.Resolved)
    ?.result
  LaunchedEffect(resolved) {
    if (resolved != null && !fileNameEditedByUser) {
      val suggested = resolved.suggestedFileName
      if (!suggested.isNullOrBlank()) {
        fileName = suggested
      }
    }
  }

  AlertDialog(
    onDismissRequest = {
      onResetResolve()
      onDismiss()
    },
    title = { Text("Add download") },
    text = {
      Column(
        verticalArrangement =
          Arrangement.spacedBy(12.dp)
      ) {
        LaunchedEffect(Unit) {
          urlFocusRequester.requestFocus()
        }
        OutlinedTextField(
          value = url,
          onValueChange = {
            url = it
            if (!fileNameEditedByUser) {
              // Reset filename so resolve can fill it
              fileName = ""
            }
          },
          modifier = Modifier.fillMaxWidth()
            .focusRequester(urlFocusRequester),
          label = { Text("URL") },
          singleLine = true,
          placeholder = { Text("Enter URL...") },
          isError =
            resolveState is ResolveState.Error,
          trailingIcon = {
            when (resolveState) {
              is ResolveState.Resolving -> {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.dp,
                )
              }
              is ResolveState.Resolved -> {
                Icon(
                  Icons.Filled.CheckCircle,
                  contentDescription = "Resolved",
                  tint =
                    MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
              }
              is ResolveState.Error -> {
                IconButton(
                  onClick = {
                    if (url.isNotBlank()) {
                      val resolveUrl = buildResolveUrl()
                      lastResolvedSource = resolveUrl
                      onResolveUrl(resolveUrl)
                    }
                  }
                ) {
                  Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Retry",
                    tint = MaterialTheme.colorScheme
                      .error,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
              is ResolveState.Idle -> {}
            }
          },
          supportingText = null
        )

        // Resolve result section
        ResolveInfoSection(resolveState)

        // Credential fields shown on authentication failure
        AnimatedVisibility(
          visible = needsAuth,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut(),
        ) {
          CredentialFields(
            username = username,
            password = password,
            onUsernameChange = { username = it },
            onPasswordChange = { password = it },
            onRetry = {
              val resolveUrl = buildResolveUrl()
              lastResolvedSource = resolveUrl
              onResolveUrl(resolveUrl)
            },
          )
        }

        OutlinedTextField(
          value = fileName,
          onValueChange = {
            fileName = it
            fileNameEditedByUser = it.isNotBlank()
          },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Save as") },
          singleLine = true,
          placeholder = {
            if (resolved?.suggestedFileName != null) {
              Text(resolved.suggestedFileName!!)
            } else {
              Text("Auto-detected from server")
            }
          },
          supportingText = if (fileName.isBlank() &&
            url.isNotBlank()
          ) {
            { Text("Will be determined by server") }
          } else {
            null
          }
        )

        // Toggle icon row
        Row(
          horizontalArrangement =
            Arrangement.spacedBy(4.dp),
          verticalAlignment =
            Alignment.CenterVertically
        ) {
          SpeedLimitIcon(
            active =
              !selectedSpeed.isUnlimited,
            selected =
              expanded == DialogPanel.SpeedLimit,
            onClick = {
              expanded = if (expanded ==
                DialogPanel.SpeedLimit
              ) {
                DialogPanel.None
              } else {
                DialogPanel.SpeedLimit
              }
            }
          )
          PriorityIcon(
            active = selectedPriority !=
              DownloadPriority.NORMAL,
            selected =
              expanded == DialogPanel.Priority,
            onClick = {
              expanded = if (expanded ==
                DialogPanel.Priority
              ) {
                DialogPanel.None
              } else {
                DialogPanel.Priority
              }
            }
          )
          ScheduleIcon(
            selected =
              expanded == DialogPanel.Schedule,
            onClick = {
              expanded = if (expanded ==
                DialogPanel.Schedule
              ) {
                DialogPanel.None
              } else {
                DialogPanel.Schedule
              }
            }
          )
        }

        // Expanded panel
        AnimatedContent(
          targetState = expanded,
          transitionSpec = {
            expandVertically() togetherWith
              shrinkVertically()
          }
        ) { panel ->
          when (panel) {
            DialogPanel.SpeedLimit ->
              SpeedLimitSelector(
                value = selectedSpeed,
                onValueChange = {
                  selectedSpeed = it
                }
              )
            DialogPanel.Priority ->
              PrioritySelector(
                value = selectedPriority,
                onValueChange = {
                  selectedPriority = it
                }
              )
            DialogPanel.Schedule ->
              ScheduleSelector(
                value = selectedSchedule,
                onValueChange = {
                  selectedSchedule = it
                }
              )
            DialogPanel.None -> {}
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val downloadUrl = buildResolveUrl()
          if (downloadUrl.isNotEmpty()) {
            onDownload(
              downloadUrl, fileName.trim(),
              selectedSpeed, selectedPriority,
              selectedSchedule, resolved,
            )
          }
        },
        enabled = url.isNotBlank(),
      ) {
        Text("Download")
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          onResetResolve()
          onDismiss()
        }
      ) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun ResolveInfoSection(
  resolveState: ResolveState,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = resolveState is ResolveState.Resolved ||
      resolveState is ResolveState.Error,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
    modifier = modifier,
  ) {
    when (resolveState) {
      is ResolveState.Resolved -> {
        ResolvedInfoCard(resolveState.result)
      }
      is ResolveState.Error -> {
        ResolveErrorCard(resolveState.message)
      }
      else -> {}
    }
  }
}

@Composable
private fun ResolvedInfoCard(
  resolved: ResolvedSource,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme
      .surfaceContainerHigh,
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement =
        Arrangement.spacedBy(4.dp),
    ) {
      // File size
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Size",
          style =
            MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme
            .onSurfaceVariant,
          modifier = Modifier.width(80.dp),
        )
        Text(
          text = if (resolved.totalBytes >= 0) {
            formatBytes(resolved.totalBytes)
          } else {
            "Unknown"
          },
          style =
            MaterialTheme.typography.labelSmall,
          color =
            MaterialTheme.colorScheme.onSurface,
        )
      }

      // Source type
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Source",
          style =
            MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme
            .onSurfaceVariant,
          modifier = Modifier.width(80.dp),
        )
        Text(
          text = resolved.sourceType.uppercase(),
          style =
            MaterialTheme.typography.labelSmall,
          color =
            MaterialTheme.colorScheme.onSurface,
        )
      }

      // Connections / range support
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Connections",
          style =
            MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme
            .onSurfaceVariant,
          modifier = Modifier.width(80.dp),
        )
        Text(
          text = when {
            resolved.maxSegments <= 1 ->
              "Single connection"
            resolved.maxSegments >= 1024 ->
              "Unlimited"
            else ->
              "Up to ${resolved.maxSegments}"
          },
          style =
            MaterialTheme.typography.labelSmall,
          color =
            MaterialTheme.colorScheme.onSurface,
        )
      }

      // Resume warning
      if (!resolved.supportsResume) {
        Spacer(Modifier.height(4.dp))
        Row(
          verticalAlignment =
            Alignment.CenterVertically,
          horizontalArrangement =
            Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme
              .onSurfaceVariant,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = "Resume not supported",
            style =
              MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme
              .onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun ResolveErrorCard(
  message: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme
      .errorContainer,
    shape = RoundedCornerShape(8.dp),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement =
        Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        Icons.Filled.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme
          .onErrorContainer,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = message,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme
          .onErrorContainer,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun CredentialFields(
  username: String,
  password: String,
  onUsernameChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement =
        Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        modifier = Modifier.weight(1f),
        label = { Text("Username") },
        singleLine = true,
      )
      OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = Modifier.weight(1f),
        label = { Text("Password") },
        singleLine = true,
        visualTransformation =
          PasswordVisualTransformation(),
      )
    }
    Button(
      onClick = onRetry,
      enabled = username.isNotBlank(),
      modifier = Modifier.align(Alignment.End),
    ) {
      Text("Retry with credentials")
    }
  }
}

/**
 * Embeds [username] and [password] into a URL's authority section.
 *
 * For example, `ftp://host/path` becomes `ftp://user:pass@host/path`.
 * Any existing credentials in the URL are replaced.
 */
private fun embedCredentials(
  url: String,
  username: String,
  password: String,
): String {
  val schemeEnd = url.indexOf("://")
  if (schemeEnd < 0) return url

  val afterScheme = url.substring(schemeEnd + 3)

  // Strip any existing credentials
  val atIndex = afterScheme.indexOf('@')
  val hostAndPath = if (atIndex >= 0) {
    afterScheme.substring(atIndex + 1)
  } else {
    afterScheme
  }

  val scheme = url.substring(0, schemeEnd + 3)
  val encodedUser = percentEncode(username)
  val encodedPass = percentEncode(password)
  return "$scheme$encodedUser:$encodedPass@$hostAndPath"
}

private fun percentEncode(value: String): String {
  return buildString {
    for (c in value) {
      when (c) {
        in 'a'..'z', in 'A'..'Z', in '0'..'9',
        '-', '.', '_', '~' -> append(c)
        else -> {
          val bytes = c.toString().encodeToByteArray()
          for (b in bytes) {
            append('%')
            append(
              b.toInt().and(0xFF)
                .toString(16).uppercase().padStart(2, '0')
            )
          }
        }
      }
    }
  }
}
