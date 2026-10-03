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
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.platform.localDeviceNounInSentence
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
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.settings_action_hide
import ketch.app.shared.generated.resources.settings_action_refresh
import ketch.app.shared.generated.resources.settings_sharing_access_code
import ketch.app.shared.generated.resources.settings_sharing_advanced
import ketch.app.shared.generated.resources.settings_sharing_advanced_hint
import ketch.app.shared.generated.resources.settings_sharing_allow
import ketch.app.shared.generated.resources.settings_sharing_also
import ketch.app.shared.generated.resources.settings_sharing_anyone
import ketch.app.shared.generated.resources.settings_sharing_auto_start
import ketch.app.shared.generated.resources.settings_sharing_code_create
import ketch.app.shared.generated.resources.settings_sharing_code_open
import ketch.app.shared.generated.resources.settings_sharing_code_rotate
import ketch.app.shared.generated.resources.settings_sharing_code_set
import ketch.app.shared.generated.resources.settings_sharing_code_web
import ketch.app.shared.generated.resources.settings_sharing_control_from_computer
import ketch.app.shared.generated.resources.settings_sharing_control_from_phone
import ketch.app.shared.generated.resources.settings_sharing_copied
import ketch.app.shared.generated.resources.settings_sharing_copy_link
import ketch.app.shared.generated.resources.settings_sharing_discoverable
import ketch.app.shared.generated.resources.settings_sharing_discoverable_hint
import ketch.app.shared.generated.resources.settings_sharing_failed
import ketch.app.shared.generated.resources.settings_sharing_local_only
import ketch.app.shared.generated.resources.settings_sharing_looking
import ketch.app.shared.generated.resources.settings_sharing_new_code
import ketch.app.shared.generated.resources.settings_sharing_new_code_hint
import ketch.app.shared.generated.resources.settings_sharing_no_address
import ketch.app.shared.generated.resources.settings_sharing_no_code
import ketch.app.shared.generated.resources.settings_sharing_off_hint
import ketch.app.shared.generated.resources.settings_sharing_open_no_code
import ketch.app.shared.generated.resources.settings_sharing_open_web
import ketch.app.shared.generated.resources.settings_sharing_pair
import ketch.app.shared.generated.resources.settings_sharing_port
import ketch.app.shared.generated.resources.settings_sharing_qr
import ketch.app.shared.generated.resources.settings_sharing_reachable
import ketch.app.shared.generated.resources.settings_sharing_reachable_off
import ketch.app.shared.generated.resources.settings_sharing_reachable_on
import ketch.app.shared.generated.resources.settings_sharing_remote
import ketch.app.shared.generated.resources.settings_sharing_restart
import ketch.app.shared.generated.resources.settings_sharing_restart_notice
import ketch.app.shared.generated.resources.settings_sharing_scan_other
import ketch.app.shared.generated.resources.settings_sharing_scan_phone
import ketch.app.shared.generated.resources.settings_sharing_stop
import ketch.app.shared.generated.resources.settings_sharing_unreadable_address
import ketch.app.shared.generated.resources.settings_sharing_unsupported
import ketch.app.shared.generated.resources.settings_sharing_websites
import ketch.app.shared.generated.resources.settings_sharing_websites_hint
import ketch.app.shared.generated.resources.settings_sharing_websites_needs_code
import ketch.app.shared.generated.resources.settings_sharing_websites_none
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
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
        text = stringResource(Res.string.settings_sharing_remote, device.label),
        tone = NoticeTone.Info,
      )
      return
    }
    !manager.isLocalServerSupported -> {
      SettingsNotice(
        text = stringResource(Res.string.settings_sharing_unsupported, device.label),
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
      text = stringResource(Res.string.settings_sharing_restart_notice),
      tone = NoticeTone.Warning,
      action = {
        KetchButton(
          text = stringResource(Res.string.settings_sharing_restart),
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
    title = stringResource(Res.string.settings_sharing_pair),
    action = if (running != null) {
      {
        KetchButton(
          text = stringResource(Res.string.settings_sharing_stop),
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
  val noun = localDeviceNounInSentence().resolve()
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
      text = if (isMobilePlatform) {
        stringResource(Res.string.settings_sharing_control_from_computer, noun)
      } else {
        stringResource(Res.string.settings_sharing_control_from_phone, noun)
      },
      style = type.titleM,
      color = colors.textPrimary,
    )
    when {
      serverState is ServerState.Failed -> {
        SettingsNotice(
          text = stringResource(Res.string.settings_sharing_failed, serverState.reason.resolve()),
          tone = NoticeTone.Error,
        )
        KetchButton(
          text = stringResource(Res.string.action_try_again),
          onClick = onAllow,
          leadingIcon = KetchIcon.Retry,
        )
      }
      !shared -> {
        if (serverState is ServerState.Running && !serverState.config.isLoopbackOnly) {
          // Reachable from the network, but without an access code.
          SettingsNotice(
            text = stringResource(Res.string.settings_sharing_open_no_code, noun),
            tone = NoticeTone.Warning,
          )
        } else {
          Text(
            text = if (serverState is ServerState.Running) {
              stringResource(Res.string.settings_sharing_local_only, noun)
            } else {
              stringResource(Res.string.settings_sharing_off_hint, noun)
            },
            style = type.bodyS,
            color = colors.textSecondary,
          )
        }
        KetchButton(
          text = stringResource(Res.string.settings_sharing_allow),
          onClick = onAllow,
          leadingIcon = KetchIcon.QrCode,
        )
      }
      link == null -> {
        Text(
          text = when {
            addresses == null -> stringResource(Res.string.settings_sharing_looking, noun)
            !addressesReadable -> stringResource(
              Res.string.settings_sharing_unreadable_address,
              noun,
              port?.toString().orEmpty(),
            )
            else ->
              stringResource(Res.string.settings_sharing_no_address, localDeviceNoun().resolve())
          },
          style = type.bodyS,
          color = colors.textSecondary,
        )
        KetchButton(
          text = stringResource(Res.string.settings_action_refresh),
          onClick = onRefresh,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Retry,
        )
      }
      else -> {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
          Text(
            text = if (isMobilePlatform) {
              stringResource(Res.string.settings_sharing_scan_other)
            } else {
              stringResource(Res.string.settings_sharing_scan_phone)
            },
            style = type.bodyS,
            color = colors.textSecondary,
          )
          Text(
            text = link.webAppUrl().substringBefore('#').removeSuffix("/"),
            style = type.mono,
            color = colors.accentText,
          )
        }
        val others = addresses.orEmpty().drop(1)
        val also = stringResource(Res.string.settings_sharing_also, others.joinToString(", "))
        Text(
          text = buildAnnotatedString {
            append(name + SEPARATOR)
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) {
              append(link.host)
            }
            if (others.isNotEmpty()) append(SEPARATOR + also)
          },
          style = type.caption,
          color = colors.textSecondary,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
          KetchButton(
            text = if (copied) {
              stringResource(Res.string.settings_sharing_copied)
            } else {
              stringResource(Res.string.settings_sharing_copy_link)
            },
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
            text = stringResource(Res.string.settings_sharing_open_web),
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
            text = stringResource(Res.string.settings_sharing_anyone, noun),
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f, fill = false),
          )
          KetchButton(
            text = stringResource(Res.string.settings_sharing_new_code),
            onClick = onNewCode,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
            tooltip = stringResource(Res.string.settings_sharing_new_code_hint),
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
      // The light margin around the code is the quiet zone cameras need to find it.
      .size(QrSize + KetchTheme.spacing.s4 * 2)
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
        contentDescription = stringResource(Res.string.settings_sharing_qr, link.name ?: link.host),
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
  // A search result for one of the rows inside opens the section, so the jump finds it.
  val asked = LocalSettingsJump.current?.requested.orEmpty()
  val rows = AdvancedRows.map { stringResource(it) }
  LaunchedEffect(asked) {
    if (asked.any { it in rows }) open = true
  }
  val lanOpen = !config.isLoopbackOnly
  val token = config.apiToken.orEmpty()
  SettingsGroup {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier.fillMaxWidth()
        .clickable(
          role = Role.Button,
          onClickLabel = if (open) {
            stringResource(Res.string.settings_action_hide)
          } else {
            stringResource(Res.string.action_show)
          },
        ) {
          open = !open
        }
        .background(colors.surface)
        .padding(horizontal = spacing.s4, vertical = spacing.s3),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text(
          text = stringResource(Res.string.settings_sharing_advanced),
          style = KetchTheme.typography.body,
          color = colors.textPrimary,
        )
        Text(
          text = stringResource(Res.string.settings_sharing_advanced_hint),
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
      title = stringResource(Res.string.settings_sharing_reachable),
      description = if (lanOpen) {
        stringResource(Res.string.settings_sharing_reachable_on)
      } else {
        stringResource(Res.string.settings_sharing_reachable_off)
      },
      checked = lanOpen,
      onCheckedChange = { allow ->
        val host = if (allow) ServerConfig.ANY_HOST else ServerConfig.LOOPBACK_HOST
        onChange(config.copy(host = host))
      },
    )
    SettingsRow(
      title = stringResource(Res.string.settings_sharing_port),
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
      title = stringResource(Res.string.settings_sharing_access_code),
      description = stringResource(
        when {
          token.isNotEmpty() -> Res.string.settings_sharing_code_set
          lanOpen -> Res.string.settings_sharing_code_open
          else -> Res.string.settings_sharing_code_web
        },
      ),
      descriptionColor = if (token.isEmpty() && lanOpen) {
        colors.status.paused.color
      } else {
        colors.textSecondary
      },
    ) {
      SettingsTextInput(
        value = token,
        onCommit = { onChange(config.copy(apiToken = it.ifBlank { null })) },
        placeholder = stringResource(Res.string.settings_sharing_no_code),
        secret = true,
        mono = true,
        actions = {
          KetchButton(
            text = if (token.isEmpty()) {
              stringResource(Res.string.settings_sharing_code_create)
            } else {
              stringResource(Res.string.settings_sharing_code_rotate)
            },
            onClick = onRotate,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        },
      )
    }
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_sharing_discoverable),
      description = stringResource(Res.string.settings_sharing_discoverable_hint),
      checked = config.mdnsEnabled && lanOpen,
      enabled = lanOpen,
      onCheckedChange = { onChange(config.copy(mdnsEnabled = it)) },
    )
    SettingsRow(
      title = stringResource(Res.string.settings_sharing_websites),
      description = if (token.isEmpty()) {
        stringResource(Res.string.settings_sharing_websites_needs_code)
      } else {
        stringResource(Res.string.settings_sharing_websites_hint)
      },
      enabled = token.isNotEmpty(),
    ) {
      SettingsTextInput(
        value = config.corsAllowedHosts.joinToString(", "),
        onCommit = { onChange(config.copy(corsAllowedHosts = parseHostList(it))) },
        normalize = { parseHostList(it).joinToString(", ") },
        placeholder = stringResource(Res.string.settings_sharing_websites_none),
        mono = true,
        enabled = token.isNotEmpty(),
      )
    }
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_sharing_auto_start),
      checked = config.autoStart,
      onCheckedChange = { onChange(config.copy(autoStart = it)) },
    )
  }
}

/** A random, unguessable access code. */
@OptIn(ExperimentalUuidApi::class)
private fun newToken(): String = Uuid.random().toHexString()

private const val COPIED_MILLIS = 2_000L

/** Titles of the rows the Advanced section holds. */
internal val AdvancedRows = listOf(
  Res.string.settings_sharing_reachable,
  Res.string.settings_sharing_port,
  Res.string.settings_sharing_access_code,
  Res.string.settings_sharing_discoverable,
  Res.string.settings_sharing_websites,
  Res.string.settings_sharing_auto_start,
)
private val QrSize = 180.dp
private val QrPlaceholderGlyph = 48.dp
private val PortFieldWidth = 96.dp
private val StackedBelow = 480.dp
