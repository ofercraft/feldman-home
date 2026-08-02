package com.feldman.ha.ui.cards

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.data.HAEntity
import com.feldman.ha.ui.navigation.LocalAppState
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import com.feldman.motion.MotionLevel
import com.feldman.motion.ThemeRepository
import com.feldman.motion.rememberSymbolPainter
import java.util.concurrent.ConcurrentHashMap

/**
 * Logical remote buttons whose `remote.send_command` string differs per integration. Only
 * navigation lives here — power/transport/volume use standard `media_player` services that
 * work regardless of integration. Command names are taken from the HA integration docs:
 *  - Android TV Remote (androidtv_remote): https://www.home-assistant.io/integrations/androidtv_remote/
 *  - Apple TV (apple_tv): https://www.home-assistant.io/integrations/apple_tv/
 */
private val NAV_COMMANDS_ANDROIDTV: Map<String, String> = mapOf(
    "up" to "DPAD_UP", "down" to "DPAD_DOWN", "left" to "DPAD_LEFT", "right" to "DPAD_RIGHT",
    "select" to "DPAD_CENTER", "back" to "BACK", "home" to "HOME", "menu" to "MENU",
)
private val NAV_COMMANDS_APPLETV: Map<String, String> = mapOf(
    // Apple TV commands are lowercase; "menu" acts as Back, "top_menu" is the contextual menu.
    "up" to "up", "down" to "down", "left" to "left", "right" to "right",
    "select" to "select", "back" to "menu", "home" to "home", "menu" to "top_menu",
)

/** Navigation command map for a remote's integration (falls back to Android TV names). */
fun navCommandsForIntegration(integration: String): Map<String, String> = when (integration) {
    "apple_tv" -> NAV_COMMANDS_APPLETV
    "androidtv_remote" -> NAV_COMMANDS_ANDROIDTV
    else -> NAV_COMMANDS_ANDROIDTV
}

/** A resolved remote plus the integration it belongs to (so we send the right commands). */
data class ResolvedRemote(val entityId: String, val integration: String)

/**
 * Resolves which `remote` entity a media_player card should control, and which integration
 * it belongs to. Uses the manual override from config if set, otherwise asks HA (via the
 * template API) for the entities on the media player's *device* — the same device, then its
 * parent (via_device) device — and picks the first `remote.*` among them. The match is by
 * device membership, never by entity-id name. Only successful resolutions are cached.
 */
object MediaRemoteResolver {
    private const val TAG = "MediaRemote"
    private val cache = ConcurrentHashMap<String, ResolvedRemote>()

    suspend fun resolveRemote(
        api: HomeAssistantApi,
        mediaPlayerId: String,
        override: String?,
    ): ResolvedRemote? {
        val cacheKey = if (!override.isNullOrBlank()) "override:$override" else mediaPlayerId
        cache[cacheKey]?.let { return it }

        val resolved = if (!override.isNullOrBlank()) {
            ResolvedRemote(override, integrationOf(api, override))
        } else {
            lookupOnDevice(api, mediaPlayerId)
        }
        Log.d(TAG, "remote lookup for $mediaPlayerId (override=$override) -> $resolved")
        if (resolved != null) cache[cacheKey] = resolved
        return resolved
    }

    /** Finds the remote on the player's device (or parent device) and its integration. */
    private suspend fun lookupOnDevice(api: HomeAssistantApi, mediaPlayerId: String): ResolvedRemote? {
        // Resolve the remote with a minimal, robust template: list the player's device
        // entities (plus its parent device's) and let Kotlin pick the remote. Kept simple on
        // purpose, so integration-detection quirks can never stop the remote from resolving.
        val entitiesTemplate =
            "{% set dev = device_id('$mediaPlayerId') %}" +
            "{% set parent = device_attr(dev, 'via_device_id') if dev else none %}" +
            "{% set ents = device_entities(dev) if dev else [] %}" +
            "{% set pents = device_entities(parent) if parent else [] %}" +
            "{{ (ents + pents) | join(',') }}"
        val entities = render(api, entitiesTemplate)
        Log.d(TAG, "device entities for $mediaPlayerId -> '$entities'")
        val remote = entities.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("remote.") }
            ?: return null
        return ResolvedRemote(remote, integrationOf(api, remote))
    }

    /**
     * Best-effort integration detection via `integration_entities` membership (robust — no
     * tuple indexing that can error). Returns "" for integrations we don't have a command map
     * for, which falls back to the Android TV command set.
     */
    private suspend fun integrationOf(api: HomeAssistantApi, remoteId: String): String {
        val template =
            "{{ 'apple_tv' if '$remoteId' in integration_entities('apple_tv') " +
            "else ('androidtv_remote' if '$remoteId' in integration_entities('androidtv_remote') else '') }}"
        return render(api, template)
    }

    private suspend fun render(api: HomeAssistantApi, template: String): String =
        runCatching {
            api.renderTemplate(mapOf("template" to template)).string()
        }.getOrNull().orEmpty().trim()
}

/**
 * Card row that opens the media player's remote panel. Resolves the remote (same/parent
 * device, or the config override) on first composition and renders a full-width tappable
 * row matching the card's other rows. Renders nothing until/unless a remote is found.
 */
@Composable
fun MediaRemoteRow(
    mediaPlayer: HAEntity,
    api: HomeAssistantApi,
    config: Map<String, Any>,
    colors: AppWidgetColors,
) {
    val remoteOverride = (config["remote_entity"] as? String)?.takeIf { it.isNotBlank() }
    var resolved by remember(mediaPlayer.entity_id, remoteOverride) { mutableStateOf<ResolvedRemote?>(null) }
    LaunchedEffect(mediaPlayer.entity_id, remoteOverride) {
        resolved = MediaRemoteResolver.resolveRemote(api, mediaPlayer.entity_id, remoteOverride)
    }
    var showRemote by remember { mutableStateOf(false) }

    val remote = resolved ?: return

    // Render exactly like a custom-feature button row (AppCustomFeatureRow's button): a
    // full-width MotionButton with the same colors, icon size and theme-driven motion.
    val context = LocalContext.current
    val themeRepository = remember(context) { ThemeRepository(context) }
    val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)

    MotionButton(
        text = "Remote",
        icon = "settings_remote",
        onClick = { showRemote = true },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        fontSize = 15.sp,
        iconSize = 20.dp,
        motionLevel = motionLevel,
        defaultState = MotionButtonState(
            backgroundColor = colors.buttonBackground,
            contentColor = colors.onBackground,
            outlineWidth = 0.dp,
            outlineColor = Color.Transparent,
        ),
    )

    if (showRemote) {
        RemotePanel(
            mediaPlayer = mediaPlayer,
            remoteEntityId = remote.entityId,
            integration = remote.integration,
            config = config,
            onDismiss = { showRemote = false },
        )
    }
}

/**
 * Full remote control surface for a media player, shown as a dialog. Navigation buttons
 * send `remote.send_command` to [remoteEntityId] using the command set for [integration]
 * (see [navCommandsForIntegration]), overridable per card via `config["remote_commands"]`;
 * power, transport and volume use `media_player` services on [mediaPlayer].
 */
@Composable
fun RemotePanel(
    mediaPlayer: HAEntity,
    remoteEntityId: String,
    integration: String,
    config: Map<String, Any>,
    onDismiss: () -> Unit,
) {
    val appState = LocalAppState.current
    @Suppress("UNCHECKED_CAST")
    val overrides = (config["remote_commands"] as? Map<String, Any>)
        ?.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }
        ?.toMap()
        .orEmpty()
    val baseCommands = navCommandsForIntegration(integration)

    fun command(key: String): String = overrides[key] ?: baseCommands[key] ?: key

    fun sendCommand(key: String) {
        appState.callServiceOptimistically(
            remoteEntityId,
            "remote",
            "send_command",
            mapOf("entity_id" to remoteEntityId, "command" to command(key)),
        ) { it }
    }

    fun mediaService(service: String, extra: Map<String, Any> = emptyMap()) {
        appState.callServiceOptimistically(
            mediaPlayer.entity_id,
            "media_player",
            service,
            mapOf("entity_id" to mediaPlayer.entity_id) + extra,
        ) { it }
    }

    val title = mediaPlayer.attributes["friendly_name"]?.toString() ?: mediaPlayer.entity_id
    val scheme = MaterialTheme.colorScheme

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = scheme.surfaceContainerLow,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )

                // Power / home / menu
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RemoteKey(
                        icon = "power_settings_new",
                        containerColor = scheme.errorContainer,
                        contentColor = scheme.onErrorContainer,
                    ) { mediaService("toggle") }
                    RemoteKey("home") { sendCommand("home") }
                    RemoteKey("menu") { sendCommand("menu") }
                }

                // Directional pad with a center OK.
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteKey("keyboard_arrow_up") { sendCommand("up") }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteKey("keyboard_arrow_left") { sendCommand("left") }
                        RemoteKey(
                            icon = "radio_button_checked",
                            size = 72.dp,
                            containerColor = scheme.tertiaryContainer,
                            contentColor = scheme.onTertiaryContainer,
                            shape = RoundedCornerShape(24.dp),
                        ) { sendCommand("select") }
                        RemoteKey("keyboard_arrow_right") { sendCommand("right") }
                    }
                    RemoteKey("keyboard_arrow_down") { sendCommand("down") }
                }

                // Back / play-pause
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RemoteKey("arrow_back") { sendCommand("back") }
                    RemoteKey(
                        icon = "play_pause",
                        containerColor = scheme.tertiaryContainer,
                        contentColor = scheme.onTertiaryContainer,
                        shape = RoundedCornerShape(20.dp),
                    ) { mediaService("media_play_pause") }
                }

                // Volume down / mute / up
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteKey("volume_down") { mediaService("volume_down") }
                    RemoteKey("volume_off") {
                        val muted = mediaPlayer.attributes["is_volume_muted"] == true
                        mediaService("volume_mute", mapOf("is_volume_muted" to !muted))
                    }
                    RemoteKey("volume_up") { mediaService("volume_up") }
                }
            }
        }
    }
}

@Composable
private fun RemoteKey(
    icon: String,
    size: Dp = 56.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    shape: Shape = CircleShape,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberSymbolPainter(icon),
            contentDescription = icon,
            tint = contentColor,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}
