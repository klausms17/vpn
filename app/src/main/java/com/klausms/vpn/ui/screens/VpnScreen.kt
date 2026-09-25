package com.klausms.vpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.service.VpnStatus
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.PingResult
import com.klausms.vpn.ui.components.CircleFlag
import com.klausms.vpn.ui.components.ConnectDisc
import com.klausms.vpn.ui.components.Countries
import com.klausms.vpn.ui.components.HeroBackdrop
import com.klausms.vpn.ui.components.HeroState
import com.klausms.vpn.ui.components.IosIcon
import com.klausms.vpn.ui.components.LargeTitle
import com.klausms.vpn.ui.components.PrimaryButton
import com.klausms.vpn.ui.components.SignalBars
import com.klausms.vpn.ui.components.TabBarSpace
import com.klausms.vpn.ui.components.mapPoint
import com.klausms.vpn.ui.components.pressScale
import com.klausms.vpn.ui.components.rememberSecondsTicker
import com.klausms.vpn.ui.components.tap
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.formatDuration

/**
 * The VPN tab: one big connect control, the session timer and the current
 * server. No technical settings here by design.
 */
@Composable
fun VpnScreen(
    vm: MainViewModel,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit,
    onAddServer: () -> Unit,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pings by vm.pings.collectAsStateWithLifecycle()
    VpnContent(
        status = status,
        profiles = profiles,
        pings = pings,
        onToggle = onToggle,
        onOpenServers = onOpenServers,
        onAddServer = onAddServer,
        onPing = { id -> if (vm.pings.value[id] == null) vm.ping(listOf(id)) },
    )
}

@Composable
fun VpnContent(
    status: VpnStatus,
    profiles: ProfilesState,
    pings: Map<String, PingResult>,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit,
    onAddServer: () -> Unit,
    onPing: (String) -> Unit,
) {
    val state = status.state
    val active = state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.DISCONNECTING
    // The server the tunnel runs on, else the selected one.
    val server: StoredProfile? = (if (active) profiles.profiles.firstOrNull { it.id == status.profileId } else null)
        ?: profiles.selected
    val code = server?.let { Countries.codeFrom(it.name) }
    val hero = when {
        server == null && !active -> HeroState.NO_SERVER
        state == VpnState.CONNECTED -> HeroState.ON
        state == VpnState.CONNECTING || state == VpnState.DISCONNECTING -> HeroState.CONNECTING
        else -> HeroState.OFF
    }

    // Measure the selected server once, so its card shows a real latency.
    LaunchedEffect(server?.id) {
        server?.id?.let(onPing)
    }

    var heroCenterY by remember { mutableFloatStateOf(0f) }
    val now = rememberSecondsTicker(state == VpnState.CONNECTED && status.connectedSince > 0)

    Box(Modifier.fillMaxSize().background(kc.page)) {
        if (heroCenterY > 0f) {
            val pin = Countries.point(code)?.let { (lon, lat) -> mapPoint(lon, lat) }
            HeroBackdrop(hero, heroCenterY, pin)
        }
        Column(Modifier.fillMaxSize()) {
            LargeTitle("VPN")
            Spacer(Modifier.weight(1f).heightIn(min = 16.dp))

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ConnectDisc(
                    state = hero,
                    label = when (hero) {
                        HeroState.NO_SERVER -> "Сначала добавьте сервер"
                        HeroState.ON -> "Отключить VPN"
                        HeroState.CONNECTING -> "Отменить подключение"
                        HeroState.OFF -> "Подключить VPN"
                    },
                    onClick = onToggle,
                    modifier = Modifier.onGloballyPositioned { heroCenterY = it.boundsInRoot().center.y },
                )
                StatusBlock(
                    state = state,
                    hero = hero,
                    since = status.connectedSince,
                    now = now,
                    error = status.message,
                    place = Countries.name(code) ?: server?.let { Countries.stripFlags(it.name) },
                )
            }

            Spacer(Modifier.weight(1.2f).heightIn(min = 24.dp))

            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "Сервер",
                    style = IosType.footnote,
                    color = kc.secondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp),
                )
                if (server != null) {
                    ServerCard(server, code, pings[server.id], onOpenServers)
                } else {
                    PrimaryButton("Добавить сервер", onClick = onAddServer, icon = R.drawable.ic_plus_ios)
                }
            }
            Spacer(Modifier.height(TabBarSpace))
        }
    }
}

@Composable
private fun StatusBlock(state: VpnState, hero: HeroState, since: Long, now: Long, error: String?, place: String?) {
    val statusColor by animateColorAsState(
        when (state) {
            VpnState.CONNECTED -> kc.green
            VpnState.CONNECTING, VpnState.DISCONNECTING -> kc.orange
            VpnState.ERROR -> kc.red
            VpnState.DISCONNECTED -> kc.gray
        },
        tween(400),
        label = "status",
    )
    val showTimer = state == VpnState.CONNECTED && since > 0
    val timerHeight by animateDpAsState(if (showTimer) 54.dp else 0.dp, tween(450), label = "timer")
    Column(
        Modifier.padding(top = 4.dp).height(118.dp).fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (state) {
                VpnState.CONNECTED -> "Подключено"
                VpnState.CONNECTING -> "Подключение…"
                VpnState.DISCONNECTING -> "Отключение…"
                VpnState.ERROR -> "Не удалось подключиться"
                VpnState.DISCONNECTED -> "Отключено"
            },
            style = IosType.subheadStrong,
            color = statusColor,
        )
        Box(Modifier.height(timerHeight)) {
            if (showTimer) {
                Text(
                    formatDuration(((now - since) / 1000).coerceAtLeast(0)),
                    style = IosType.timer,
                    color = kc.label,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        val subline = when {
            state == VpnState.ERROR && !error.isNullOrBlank() -> error
            hero == HeroState.NO_SERVER -> "Добавьте сервер, чтобы подключиться"
            state == VpnState.CONNECTED -> listOfNotNull(place, "соединение защищено").joinToString(" · ")
            state == VpnState.CONNECTING -> "Устанавливаем защищённое соединение…"
            state == VpnState.DISCONNECTING -> "Отключаем…"
            else -> "Нажмите, чтобы подключиться"
        }
        Text(
            subline,
            style = IosType.subhead,
            color = if (state == VpnState.ERROR) kc.red else kc.secondary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ServerCard(server: StoredProfile, code: String?, ping: PingResult?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = pressScale(source)
    val bg by animateColorAsState(if (pressed) kc.cardPressed else kc.card, label = "card")
    val title = Countries.stripFlags(server.name)
    val country = Countries.name(code)
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .scale(scale)
            .clip(RoundedCornerShape(26.dp))
            .background(bg)
            .tap(source, onClick = onClick)
            .semantics { contentDescription = "Сервер: $title. Выбрать другой" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleFlag(code)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = IosType.headline, color = kc.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (country != null && country != title) {
                    Text(country, style = IosType.subhead, color = kc.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("·", style = IosType.subhead, color = kc.secondary)
                }
                PingLine(ping)
            }
        }
        Spacer(Modifier.width(10.dp))
        IosIcon(R.drawable.ic_chevron_ios, kc.tertiary, Modifier.size(width = 7.dp, height = 12.dp))
    }
}

/** Signal bars plus "48 мс", or the state of the check. */
@Composable
fun PingLine(ping: PingResult?) {
    when (ping) {
        is PingResult.Ok -> {
            val (level, color) = pingLevel(ping.ms)
            SignalBars(level, color)
            Text("${ping.ms} мс", style = IosType.subhead.copy(fontFeatureSettings = "tnum"), color = kc.secondary, maxLines = 1)
        }
        is PingResult.Failed -> {
            SignalBars(1, kc.red)
            Text("нет связи", style = IosType.subhead, color = kc.red, maxLines = 1)
        }
        PingResult.Testing -> Text("проверка…", style = IosType.subhead, color = kc.secondary, maxLines = 1)
        null -> Text("—", style = IosType.subhead, color = kc.secondary)
    }
}

@Composable
fun pingLevel(ms: Long) = when {
    ms < 150 -> 4 to kc.green
    ms < 400 -> 3 to kc.green
    ms < 1000 -> 2 to kc.orange
    else -> 1 to kc.red
}
