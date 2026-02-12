package com.linroid.kdown.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.SpeedLimit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private data class SpeedOption(
  val label: String,
  val limit: SpeedLimit,
)

private val presetSpeedOptions = listOf(
  SpeedOption("Unlimited", SpeedLimit.Unlimited),
  SpeedOption("256 KB/s", SpeedLimit.kbps(256)),
  SpeedOption("512 KB/s", SpeedLimit.kbps(512)),
  SpeedOption("1 MB/s", SpeedLimit.mbps(1)),
  SpeedOption("2 MB/s", SpeedLimit.mbps(2)),
  SpeedOption("5 MB/s", SpeedLimit.mbps(5)),
  SpeedOption("10 MB/s", SpeedLimit.mbps(10)),
  SpeedOption("20 MB/s", SpeedLimit.mbps(20)),
  SpeedOption("50 MB/s", SpeedLimit.mbps(50))
)

@Composable
fun SpeedLimitIcon(
  active: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  IconButton(
    onClick = onClick,
    modifier = modifier.size(28.dp),
    colors = IconButtonDefaults.iconButtonColors(
      contentColor = if (active || selected) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      }
    )
  ) {
    Icon(
      Icons.Filled.Speed,
      contentDescription = "Speed limit",
      modifier = Modifier.size(16.dp),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedLimitSelector(
  value: SpeedLimit,
  onValueChange: (SpeedLimit) -> Unit,
  modifier: Modifier = Modifier,
) {
  val matchesPreset =
    presetSpeedOptions.any { it.limit == value }
  var isCustom by remember {
    mutableStateOf(!matchesPreset)
  }
  var customText by remember {
    val initial = if (!matchesPreset &&
      !value.isUnlimited
    ) {
      val bps = value.bytesPerSecond
      val mbps = bps / (1024L * 1024)
      if (mbps > 0 && mbps * 1024 * 1024 == bps) {
        mbps.toString()
      } else {
        (bps / 1024).toString()
      }
    } else ""
    mutableStateOf(initial)
  }
  var customUnit by remember {
    val initial = if (!matchesPreset &&
      !value.isUnlimited
    ) {
      val bps = value.bytesPerSecond
      val mbps = bps / (1024L * 1024)
      if (mbps > 0 && mbps * 1024 * 1024 == bps) {
        "MB/s"
      } else "KB/s"
    } else "MB/s"
    mutableStateOf(initial)
  }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FlowRow(
      horizontalArrangement =
        Arrangement.spacedBy(8.dp),
      verticalArrangement =
        Arrangement.spacedBy(4.dp)
    ) {
      presetSpeedOptions.forEach { option ->
        FilterChip(
          selected = !isCustom &&
            value == option.limit,
          onClick = {
            isCustom = false
            onValueChange(option.limit)
          },
          label = { Text(option.label) },
        )
      }
      FilterChip(
        selected = isCustom,
        onClick = { isCustom = true },
        label = { Text("Custom") },
      )
    }
    if (isCustom) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
          Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = customText,
          onValueChange = { text ->
            customText = text.filter { it.isDigit() }
            val number = customText.toLongOrNull()
            if (number != null && number > 0) {
              val limit = if (customUnit == "KB/s") {
                SpeedLimit.kbps(number)
              } else {
                SpeedLimit.mbps(number)
              }
              onValueChange(limit)
            }
          },
          modifier = Modifier.width(100.dp),
          singleLine = true,
          placeholder = { Text("Speed") },
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
          )
        )
        FilterChip(
          selected = customUnit == "KB/s",
          onClick = {
            customUnit = "KB/s"
            val number = customText.toLongOrNull()
            if (number != null && number > 0) {
              onValueChange(SpeedLimit.kbps(number))
            }
          },
          label = { Text("KB/s") },
        )
        FilterChip(
          selected = customUnit == "MB/s",
          onClick = {
            customUnit = "MB/s"
            val number = customText.toLongOrNull()
            if (number != null && number > 0) {
              onValueChange(SpeedLimit.mbps(number))
            }
          },
          label = { Text("MB/s") },
        )
      }
    }
  }
}

@Composable
fun SpeedLimitPanel(
  task: DownloadTask,
  scope: CoroutineScope,
  modifier: Modifier = Modifier,
) {
  SpeedLimitSelector(
    value = task.request.speedLimit,
    onValueChange = { limit ->
      scope.launch { task.setSpeedLimit(limit) }
    },
    modifier = modifier,
  )
}
