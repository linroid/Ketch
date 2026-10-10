package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ProxyAddress
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ProxyMode
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_proxy
import ketch.app.shared.generated.resources.settings_proxy_address
import ketch.app.shared.generated.resources.settings_proxy_address_hint
import ketch.app.shared.generated.resources.settings_proxy_address_invalid
import ketch.app.shared.generated.resources.settings_proxy_bypass
import ketch.app.shared.generated.resources.settings_proxy_bypass_hint
import ketch.app.shared.generated.resources.settings_proxy_bypass_invalid
import ketch.app.shared.generated.resources.settings_proxy_direct
import ketch.app.shared.generated.resources.settings_proxy_footer
import ketch.app.shared.generated.resources.settings_proxy_manual
import ketch.app.shared.generated.resources.settings_proxy_mode
import ketch.app.shared.generated.resources.settings_proxy_optional
import ketch.app.shared.generated.resources.settings_proxy_password
import ketch.app.shared.generated.resources.settings_proxy_system
import ketch.app.shared.generated.resources.settings_proxy_system_hint
import ketch.app.shared.generated.resources.settings_proxy_unsupported
import ketch.app.shared.generated.resources.settings_proxy_username
import org.jetbrains.compose.resources.stringResource

/**
 * How [device]'s HTTP downloads reach servers: the system's proxy, none, or one typed here, with
 * its credentials and the hosts reached directly. Changes apply as downloads start or resume,
 * and are saved with the download settings.
 */
@Composable
internal fun ProxySettings(state: AppState, device: InstanceEntry) {
  val controller = state.settingsFor(device)
  LaunchedEffect(controller) { controller.loadDownload() }
  val features by remember(device) { state.featuresFlow(device) }.collectAsState(emptySet())
  val config = controller.download ?: return
  if (KetchFeatures.PROXY !in features) {
    SettingsNotice(
      text = stringResource(Res.string.settings_proxy_unsupported, device.label),
      tone = NoticeTone.Info,
    )
    return
  }
  val proxy = config.proxy
  val onChange = { updated: ProxyConfig -> controller.updateDownload(config.copy(proxy = updated)) }
  // Manual mode needs an address, so it is only applied once one is typed.
  var choosingManual by rememberSaveable(device.deviceId) { mutableStateOf(false) }
  LaunchedEffect(proxy.mode) { if (proxy.mode == ProxyMode.MANUAL) choosingManual = false }
  val mode = if (choosingManual) ProxyMode.MANUAL else proxy.mode

  SettingsGroup(
    title = stringResource(Res.string.settings_proxy),
    footer = stringResource(Res.string.settings_proxy_footer),
  ) {
    SettingsSelectRow(
      title = stringResource(Res.string.settings_proxy_mode),
      value = mode,
      options = ProxyMode.entries,
      label = ::modeLabel,
      onSelect = { selected ->
        when {
          selected != ProxyMode.MANUAL -> {
            choosingManual = false
            if (selected != proxy.mode) onChange(proxy.copy(mode = selected))
          }
          proxy.url != null -> onChange(proxy.copy(mode = ProxyMode.MANUAL))
          else -> choosingManual = true
        }
      },
      description = if (mode == ProxyMode.SYSTEM) {
        stringResource(Res.string.settings_proxy_system_hint, device.label)
      } else {
        null
      },
    )
    if (mode != ProxyMode.MANUAL) return@SettingsGroup
    SettingsRow(
      title = stringResource(Res.string.settings_proxy_address),
      description = stringResource(Res.string.settings_proxy_address_hint),
    ) {
      SettingsTextInput(
        value = proxy.url.orEmpty(),
        onCommit = { typed -> if (typed.isNotEmpty()) onChange(withAddress(proxy, typed)) },
        placeholder = "socks5://127.0.0.1:1080",
        validate = { typed ->
          if (typed.isBlank() || ProxyAddress.parse(typed) != null) {
            null
          } else {
            Res.string.settings_proxy_address_invalid.text()
          }
        },
        mono = true,
      )
    }
    SettingsRow(title = stringResource(Res.string.settings_proxy_username)) {
      SettingsTextInput(
        value = proxy.username.orEmpty(),
        onCommit = { name ->
          val username = name.ifEmpty { null }
          // A password belongs to its username.
          val password = proxy.password.takeIf { username != null }
          onChange(proxy.copy(username = username, password = password))
        },
        placeholder = stringResource(Res.string.settings_proxy_optional),
      )
    }
    SettingsRow(
      title = stringResource(Res.string.settings_proxy_password),
      enabled = proxy.username != null,
    ) {
      SettingsTextInput(
        value = proxy.password.orEmpty(),
        onCommit = { onChange(proxy.copy(password = it.ifEmpty { null })) },
        placeholder = stringResource(Res.string.settings_proxy_optional),
        // Spaces can be part of a password.
        normalize = { it },
        secret = true,
        enabled = proxy.username != null,
      )
    }
    SettingsRow(
      title = stringResource(Res.string.settings_proxy_bypass),
      description = stringResource(Res.string.settings_proxy_bypass_hint),
    ) {
      SettingsTextInput(
        value = proxy.bypass.joinToString(", "),
        onCommit = { typed -> onChange(proxy.copy(bypass = bypassEntries(typed))) },
        placeholder = "*.lan, 10.0.0.0/8",
        normalize = { bypassEntries(it).joinToString(", ") },
        validate = { typed ->
          bypassEntries(typed).firstOrNull { !ProxyConfig.isValidBypass(it) }
            ?.let { Res.string.settings_proxy_bypass_invalid.text(it) }
        },
        mono = true,
      )
    }
  }
}

private fun modeLabel(mode: ProxyMode): UiText = when (mode) {
  ProxyMode.SYSTEM -> Res.string.settings_proxy_system.text()
  ProxyMode.DIRECT -> Res.string.settings_proxy_direct.text()
  ProxyMode.MANUAL -> Res.string.settings_proxy_manual.text()
}

/**
 * [proxy] in manual mode at the proxy [typed], whose credentials, if it carries any, replace
 * the saved ones.
 */
internal fun withAddress(proxy: ProxyConfig, typed: String): ProxyConfig {
  val parsed = ProxyConfig.manual(typed, proxy.bypass)
  if (parsed.username != null) return parsed
  return parsed.copy(username = proxy.username, password = proxy.password)
}

/** The entries of a comma-separated bypass list. */
internal fun bypassEntries(text: String): List<String> =
  text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
