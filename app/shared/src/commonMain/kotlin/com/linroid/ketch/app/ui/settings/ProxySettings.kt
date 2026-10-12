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
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ProxyAddress
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
import ketch.app.shared.generated.resources.settings_proxy_address_placeholder
import ketch.app.shared.generated.resources.settings_proxy_bypass
import ketch.app.shared.generated.resources.settings_proxy_bypass_hint
import ketch.app.shared.generated.resources.settings_proxy_bypass_invalid
import ketch.app.shared.generated.resources.settings_proxy_bypass_placeholder
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
 * and are saved with the download settings; a manual proxy left for another choice is kept only
 * while the page is open.
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
  val onChange = { updated: Proxy -> controller.updateDownload(config.copy(proxy = updated)) }
  val manual = proxy as? Proxy.Manual
  // Kept while the page is open, so choosing another proxy and Manual again brings it back.
  var lastManual by remember(device.deviceId) { mutableStateOf(manual) }
  // Manual needs an address, so choosing it shows the fields until one is typed.
  var choosingManual by rememberSaveable(device.deviceId) { mutableStateOf(false) }
  LaunchedEffect(proxy) {
    if (manual != null) {
      lastManual = manual
      choosingManual = false
    }
  }
  val choice = if (choosingManual) ProxyChoice.Manual else ProxyChoice.of(proxy)

  SettingsGroup(
    title = stringResource(Res.string.settings_proxy),
    footer = stringResource(Res.string.settings_proxy_footer),
  ) {
    SettingsSelectRow(
      title = stringResource(Res.string.settings_proxy_mode),
      value = choice,
      options = ProxyChoice.entries,
      label = ::choiceLabel,
      onSelect = { selected ->
        val chosen = when (selected) {
          ProxyChoice.System -> Proxy.System
          ProxyChoice.Direct -> Proxy.Direct
          ProxyChoice.Manual -> lastManual
        }
        choosingManual = chosen == null
        if (chosen != null && chosen != proxy) onChange(chosen)
      },
      description = if (choice == ProxyChoice.System) {
        stringResource(Res.string.settings_proxy_system_hint, device.label)
      } else {
        null
      },
    )
    if (choice != ProxyChoice.Manual) return@SettingsGroup
    val address = manual?.address
    SettingsRow(
      title = stringResource(Res.string.settings_proxy_address),
      description = stringResource(Res.string.settings_proxy_address_hint),
    ) {
      SettingsTextInput(
        value = address?.url.orEmpty(),
        onCommit = { typed -> if (typed.isNotEmpty()) onChange(withAddress(manual, typed)) },
        placeholder = stringResource(Res.string.settings_proxy_address_placeholder),
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
    SettingsRow(
      title = stringResource(Res.string.settings_proxy_username),
      enabled = manual != null,
    ) {
      SettingsTextInput(
        value = address?.username.orEmpty(),
        onCommit = { name ->
          val username = name.ifEmpty { null }
          // A password belongs to its username.
          val password = address?.password.takeIf { username != null }
          manual?.let { onChange(it.withCredentials(username, password)) }
        },
        placeholder = stringResource(Res.string.settings_proxy_optional),
        enabled = manual != null,
      )
    }
    SettingsRow(
      title = stringResource(Res.string.settings_proxy_password),
      enabled = address?.username != null,
    ) {
      SettingsTextInput(
        value = address?.password.orEmpty(),
        onCommit = { typed ->
          manual?.let { onChange(it.withCredentials(it.address.username, typed.ifEmpty { null })) }
        },
        placeholder = stringResource(Res.string.settings_proxy_optional),
        // Spaces can be part of a password.
        normalize = { it },
        secret = true,
        enabled = address?.username != null,
      )
    }
    SettingsRow(
      title = stringResource(Res.string.settings_proxy_bypass),
      description = stringResource(Res.string.settings_proxy_bypass_hint),
      enabled = manual != null,
    ) {
      SettingsTextInput(
        value = manual?.bypass.orEmpty().joinToString(", "),
        onCommit = { typed -> manual?.let { onChange(it.copy(bypass = bypassEntries(typed))) } },
        placeholder = stringResource(Res.string.settings_proxy_bypass_placeholder),
        normalize = { bypassEntries(it).joinToString(", ") },
        validate = { typed ->
          bypassEntries(typed).firstOrNull { !Proxy.Manual.isValidBypass(it) }
            ?.let { Res.string.settings_proxy_bypass_invalid.text(it) }
        },
        mono = true,
        enabled = manual != null,
      )
    }
  }
}

/** The choices of the proxy row. */
private enum class ProxyChoice {
  System,
  Direct,
  Manual;

  companion object {
    fun of(proxy: Proxy): ProxyChoice = when (proxy) {
      Proxy.System -> System
      Proxy.Direct -> Direct
      is Proxy.Manual -> Manual
    }
  }
}

private fun choiceLabel(choice: ProxyChoice): UiText = when (choice) {
  ProxyChoice.System -> Res.string.settings_proxy_system.text()
  ProxyChoice.Direct -> Res.string.settings_proxy_direct.text()
  ProxyChoice.Manual -> Res.string.settings_proxy_manual.text()
}

/**
 * The proxy at [typed], keeping [current]'s bypass list and, unless [typed] carries its own,
 * its credentials.
 */
internal fun withAddress(current: Proxy.Manual?, typed: String): Proxy.Manual {
  val parsed = Proxy.Manual(typed, current?.bypass.orEmpty())
  if (current == null || parsed.address.username != null) return parsed
  return parsed.withCredentials(current.address.username, current.address.password)
}

/** This proxy with [username] and [password]. */
internal fun Proxy.Manual.withCredentials(username: String?, password: String?): Proxy.Manual =
  copy(address = address.copy(username = username, password = password))

/** The entries of a comma-separated bypass list. */
internal fun bypassEntries(text: String): List<String> =
  text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
