package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.parseHostList
import com.linroid.ketch.app.state.portError
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.app.util.pairingAddresses
import com.linroid.ketch.config.ServerConfig
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val log = KetchLogger("SharingSettings")

/**
 * Sharing of the embedded [device]: the Pair a device card, which lets a phone, a tablet or a
 * browser control it, then the sharing server's advanced settings.
 *
 * Settings are saved at once but read when sharing starts, so while it runs a change offers a
 * restart. Other devices share from their own Ketch, so they get a note instead.
 */
@Composable
fun SharingSettings(state: AppState, device: InstanceEntry) {
  val manager = state.instanceManager
  when {
    device !is EmbeddedInstance -> {
      SettingsNotice(
        text = "Sharing of ${device.label} is set up on that device, in its own Ketch app or " +
          "with ketch server.",
        tone = NoticeTone.Info,
      )
      return
    }
    !manager.isLocalServerSupported -> {
      SettingsNotice(
        text = "${device.label} can't share its downloads with other devices.",
        tone = NoticeTone.Info,
      )
      return
    }
  }
  val networks = state.settingsFor(device)
  LaunchedEffect(networks) { networks.loadNetworks() }
  val config = state.appSettings.config.server
  val serverState by state.serverState.collectAsState()
  val save = { updated: ServerConfig -> state.appSettings.saveServer(updated) }
  val restart = {
    manager.stopServer()
    manager.startServer()
  }
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.sectionGap)) {
    PairCard(
      name = device.label,
      serverState = serverState,
      addresses = networks.networks?.let { pairingAddresses(it.available) },
      addressesReadable = networks.networks?.supported != false,
      onAllow = {
        save(
          config.copy(
            host = ServerConfig.ANY_HOST,
            apiToken = config.apiToken ?: newToken(),
            mdnsEnabled = true,
          ),
        )
        manager.startServer()
      },
      onStop = { manager.stopServer() },
      onNewCode = {
        save(config.copy(apiToken = newToken()))
        restart()
      },
      onRefresh = { networks.loadNetworks() },
    )
    val running = serverState as? ServerState.Running
    // Starting with Ketch only matters at launch, so it never calls for a restart.
    if (running != null && running.config.copy(autoStart = config.autoStart) != config) {
      SettingsNotice(
        text = "Restart sharing to apply your changes. Connected devices reconnect on their own.",
        tone = NoticeTone.Warning,
        action = {
          KetchButton(
            text = "Restart",
            onClick = restart,
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Retry,
          )
        },
      )
    }
    AdvancedGroup(
      config = config,
      onChange = save,
      onRotate = {
        save(config.copy(apiToken = newToken()))
        if (serverState is ServerState.Running) restart()
      },
    )
  }
}

/**
 * The QR code and pairing link while this device is shared with other devices, or the button
 * that shares it.
 *
 * @param addresses addresses other devices reach this one at, best first; `null` while loading.
 * @param addressesReadable whether the device can tell its addresses at all.
 */
@Composable
private fun PairCard(
  name: String,
  serverState: ServerState,
  addresses: List<String>?,
  addressesReadable: Boolean,
  onAllow: () -> Unit,
  onStop: () -> Unit,
  onNewCode: () -> Unit,
  onRefresh: () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val running = serverState as? ServerState.Running
  val token = running?.config?.apiToken
  val shared = running != null && !running.config.isLoopbackOnly && token != null
  val link = if (shared) {
    addresses?.firstOrNull()?.let { PairingLink(it, running.port, token, name) }
  } else {
    null
  }
  SettingsGroup(
    title = "Pair a device",
    action = if (running != null) {
      {
        KetchButton(
          text = "Stop sharing",
          onClick = onStop,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Stop,
        )
      }
    } else {
      null
    },
  ) {
    BoxWithConstraints(
      Modifier.fillMaxWidth()
        .background(KetchTheme.colors.surface)
        .padding(spacing.s5),
    ) {
      val details = @Composable {
        PairDetails(
          name = name,
          serverState = serverState,
          shared = shared,
          link = link,
          addresses = addresses,
          addressesReadable = addressesReadable,
          onAllow = onAllow,
          onNewCode = onNewCode,
          onRefresh = onRefresh,
        )
      }
      if (maxWidth < StackedBelow) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
          QrTile(link, Modifier.align(Alignment.CenterHorizontally))
          details()
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s5)) {
          QrTile(link)
          Box(Modifier.weight(1f)) { details() }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairDetails(
  name: String,
  serverState: ServerState,
  shared: Boolean,
  link: PairingLink?,
  addresses: List<String>?,
  addressesReadable: Boolean,
  onAllow: () -> Unit,
  onNewCode: () -> Unit,
  onRefresh: () -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  // "this Mac" in the middle of a sentence; the host name follows the address.
  val noun = localDeviceNoun().replaceFirstChar { it.lowercase() }
  val port = (serverState as? ServerState.Running)?.port
  val clipboard = rememberSystemClipboard()
  val uriHandler = LocalUriHandler.current
  val scope = rememberCoroutineScope()
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_MILLIS)
      copied = false
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    Text(
      text = "Control $noun from your phone, tablet or another browser.",
      style = type.titleM,
      color = colors.textPrimary,
    )
    when {
      serverState is ServerState.Failed -> {
        SettingsNotice(
          text = "Couldn't start sharing: ${serverState.message}",
          tone = NoticeTone.Error,
        )
        KetchButton(text = "Try again", onClick = onAllow, leadingIcon = KetchIcon.Retry)
      }
      !shared -> {
        Text(
          text = if (serverState is ServerState.Running) {
            "Only apps on $noun can connect now. Allowing another device shares it on your " +
              "network, protected by a code."
          } else {
            "Ketch shares $noun on your network, protected by a code that only the devices " +
              "you pair receive."
          },
          style = type.bodyS,
          color = colors.textSecondary,
        )
        KetchButton(
          text = "Allow another device",
          onClick = onAllow,
          leadingIcon = KetchIcon.QrCode,
        )
      }
      link == null -> {
        Text(
          text = when {
            addresses == null -> "Looking for the network address of $noun…"
            !addressesReadable -> "Ketch can't read the network address of $noun. Open " +
              "http://<its address>:$port on the other device, then enter the access code " +
              "from Advanced below."
            else ->
              "${localDeviceNoun()} has no network address right now. Connect it to Wi-Fi " +
                "or Ethernet, then refresh."
          },
          style = type.bodyS,
          color = colors.textSecondary,
        )
        KetchButton(
          text = "Refresh",
          onClick = onRefresh,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Retry,
        )
      }
      else -> {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
          Text(
            text = "Scan the code with your phone's camera, or open",
            style = type.bodyS,
            color = colors.textSecondary,
          )
          Text(
            text = link.webAppUrl().substringBefore('#').removeSuffix("/"),
            style = type.mono,
            color = colors.accentText,
          )
        }
        Text(
          text = buildAnnotatedString {
            append("$name · ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) {
              append(link.host)
            }
            val others = addresses.orEmpty().drop(1)
            if (others.isNotEmpty()) append(" · also ${others.joinToString(", ")}")
          },
          style = type.caption,
          color = colors.textSecondary,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
          KetchButton(
            text = if (copied) "Copied" else "Copy pairing link",
            onClick = {
              scope.launch {
                try {
                  clipboard.writeText(link.toUri())
                  copied = true
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  log.w { "Couldn't copy the pairing link: ${e.describeCauses()}" }
                }
              }
            },
            variant = KetchButtonVariant.Tonal,
            size = KetchButtonSize.Small,
            leadingIcon = if (copied) KetchIcon.Check else KetchIcon.Copy,
          )
          KetchButton(
            text = "Open web app",
            onClick = {
              try {
                uriHandler.openUri(link.webAppUrl())
              } catch (e: Exception) {
                log.w { "Couldn't open the web app: ${e.describeCauses()}" }
              }
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Open,
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
          Text(
            text = "Anyone with this code can control $noun.",
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f, fill = false),
          )
          KetchButton(
            text = "New code",
            onClick = onNewCode,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
            tooltip = "Disconnects the devices paired so far",
          )
        }
      }
    }
  }
}

/**
 * The QR code of [link] on a light tile, which phone cameras read in both themes, or a
 * placeholder while there is nothing to scan.
 */
@Composable
private fun QrTile(link: PairingLink?, modifier: Modifier = Modifier) {
  val palette = KetchTheme.colors
  val shape = KetchTheme.shapes.md
  val fill = if (palette.isDark) palette.inverseSurface else palette.surface
  val ink = if (palette.isDark) palette.inverseOnSurface else palette.textPrimary
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(QrSize + KetchTheme.spacing.s3 * 2)
      .background(if (link == null) palette.surfaceSunken else fill, shape)
      .border(HairlineWidth, palette.hairline, shape),
  ) {
    if (link == null) {
      KetchIconImage(
        icon = KetchIcon.QrCode,
        size = QrPlaceholderGlyph,
        tint = palette.textDisabled,
      )
    } else {
      val painter = rememberQrCodePainter(link.toUri(), ink) {
        colors { dark = QrBrush.solid(ink) }
      }
      Image(
        painter = painter,
        contentDescription = "Pairing code for ${link.name ?: link.host}",
        modifier = Modifier.size(QrSize),
      )
    }
  }
}

/** Port, code, discovery, allowed websites and starting with Ketch, collapsed at first. */
@Composable
private fun AdvancedGroup(
  config: ServerConfig,
  onChange: (ServerConfig) -> Unit,
  onRotate: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  var open by rememberSaveable { mutableStateOf(false) }
  val lanOpen = !config.isLoopbackOnly
  val token = config.apiToken.orEmpty()
  SettingsGroup {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier.fillMaxWidth()
        .clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") {
          open = !open
        }
        .background(colors.surface)
        .padding(horizontal = spacing.s4, vertical = spacing.s3),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text("Advanced", style = KetchTheme.typography.body, color = colors.textPrimary)
        Text(
          text = "Port, access code, discovery and websites",
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
        )
      }
      KetchIconImage(
        icon = if (open) KetchIcon.ChevronUp else KetchIcon.ChevronDown,
        size = KetchTheme.density.controlGlyph,
        tint = colors.textTertiary,
      )
    }
    if (!open) return@SettingsGroup
    SettingsSwitchRow(
      title = "Reachable from other devices",
      description = if (lanOpen) {
        "Devices that can reach this one over the network can connect with the code."
      } else {
        "Only apps on this device can connect."
      },
      checked = lanOpen,
      onCheckedChange = { allow ->
        val host = if (allow) ServerConfig.ANY_HOST else ServerConfig.LOOPBACK_HOST
        onChange(config.copy(host = host))
      },
    )
    SettingsRow(
      title = "Port",
      description = "Other devices connect to this port.",
      trailing = {
        SettingsTextInput(
          value = config.port.toString(),
          onCommit = { onChange(config.copy(port = it.toInt())) },
          normalize = { it.trim().toIntOrNull()?.toString() ?: it.trim() },
          validate = ::portError,
          numeric = true,
          mono = true,
          width = PortFieldWidth,
        )
      },
    )
    SettingsRow(
      title = "Access code",
      description = when {
        token.isNotEmpty() -> "Paired devices and the web app send this code with every request."
        lanOpen -> "Without a code, anyone on your network can control your downloads, and " +
          "the web app can't connect."
        else -> "Needed for the web app. Optional while only this device can connect."
      },
      descriptionColor = if (token.isEmpty() && lanOpen) {
        colors.status.paused.color
      } else {
        colors.textSecondary
      },
    ) {
      SettingsTextInput(
        value = token,
        onCommit = { onChange(config.copy(apiToken = it.ifBlank { null })) },
        placeholder = "No code",
        secret = true,
        mono = true,
        actions = {
          KetchButton(
            text = if (token.isEmpty()) "Create" else "Rotate",
            onClick = onRotate,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        },
      )
    }
    SettingsSwitchRow(
      title = "Discoverable on the local network",
      description = "Lets Ketch on other devices find this one without typing an address.",
      checked = config.mdnsEnabled && lanOpen,
      enabled = lanOpen,
      onCheckedChange = { onChange(config.copy(mdnsEnabled = it)) },
    )
    SettingsRow(
      title = "Websites allowed to connect",
      description = if (token.isEmpty()) {
        "Needs an access code. Without one, only Ketch's own web app and browser extension " +
          "can connect."
      } else {
        "Other websites that may use this device, such as app.example.com. Separate them " +
          "with commas; * allows any."
      },
      enabled = token.isNotEmpty(),
    ) {
      SettingsTextInput(
        value = config.corsAllowedHosts.joinToString(", "),
        onCommit = { onChange(config.copy(corsAllowedHosts = parseHostList(it))) },
        normalize = { parseHostList(it).joinToString(", ") },
        placeholder = "None",
        mono = true,
        enabled = token.isNotEmpty(),
      )
    }
    SettingsSwitchRow(
      title = "Start sharing when Ketch opens",
      checked = config.autoStart,
      onCheckedChange = { onChange(config.copy(autoStart = it)) },
    )
  }
}

/** A random, unguessable access code. */
@OptIn(ExperimentalUuidApi::class)
private fun newToken(): String = Uuid.random().toHexString()

private const val COPIED_MILLIS = 2_000L
private val QrSize = 180.dp
private val QrPlaceholderGlyph = 48.dp
private val PortFieldWidth = 96.dp
private val StackedBelow = 480.dp
