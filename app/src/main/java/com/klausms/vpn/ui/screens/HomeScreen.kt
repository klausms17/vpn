package com.klausms.vpn.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.data.AppUpdate
import com.klausms.vpn.data.PingGrade
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.pingGrade
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.service.VpnStatus
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.PingResult
import com.klausms.vpn.ui.components.ConnectDisc
import com.klausms.vpn.ui.components.Countries
import com.klausms.vpn.ui.components.GlassIconButton
import com.klausms.vpn.ui.components.HeroBackdrop
import com.klausms.vpn.ui.components.HeroState
import com.klausms.vpn.ui.components.IconTile
import com.klausms.vpn.ui.components.InsetGroup
import com.klausms.vpn.ui.components.PrimaryButton
import com.klausms.vpn.ui.components.ScrollEdge
import com.klausms.vpn.ui.components.SecondaryButton
import com.klausms.vpn.ui.components.mapPoint
import com.klausms.vpn.ui.components.navBarClearance
import com.klausms.vpn.ui.components.rememberSecondsTicker
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.formatDuration

/**
 * The one main screen: the connect button with the session timer on top,
 * the servers below. Settings open from the gear, new servers from "+".
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onPaste: suspend () -> Boolean = { false },
    onScan: (() -> Unit)? = null,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pings by vm.pings.collectAsStateWithLifecycle()
    val whitelist by vm.whitelist.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = remember(vm) {
        ServerActions(
            select = { vm.select(it) },
            ping = { vm.ping(listOf(it)) },
            pingAll = { vm.pingAll() },
            rename = { id, name -> vm.rename(id, name) },
            delete = { vm.delete(it) },
            refreshSubscription = { vm.refreshSubscription(it) },
            deleteSubscription = { vm.deleteSubscription(it) },
        )
    }
    HomeContent(
        status = status,
        profiles = profiles,
        pings = pings,
        whitelist = whitelist,
        actions = actions,
        onToggle = onToggle,
        onAdd = onAdd,
        onOpenSettings = onOpenSettings,
        // Measure when there is no result yet or the last one failed.
        onPing = { id ->
            val last = vm.pings.value[id]
            if (last == null || last is PingResult.Failed) vm.ping(listOf(id))
        },
        update = update,
        onUpdate = { if (!openInBrowser(context, it.apkUrl)) vm.updateNotOpened() },
        onDismissUpdate = { vm.dismissUpdate() },
        onPaste = onPaste,
        onScan = onScan,
    )
}

@Composable
fun HomeContent(
    status: VpnStatus,
    profiles: ProfilesState,
    pings: Map<String, PingResult>,
    whitelist: Map<String, Int>,
    actions: ServerActions,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onPing: (String) -> Unit,
    /** A newer app build to offer, or null. */
    update: AppUpdate? = null,
    onUpdate: (AppUpdate) -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    /** Imports the clipboard; false when it holds no text. */
    onPaste: suspend () -> Boolean = { false },
    /** Opens the QR scanner; null without a camera. */
    onScan: (() -> Unit)? = null,
) {
    val state = status.state
    val active = status.active
    // The running server, else the selected one; a running one that a
    // refresh removed from the list is named by the status.
    val server: StoredProfile? = status.shownServer(profiles)
    val serverName = server?.name ?: status.profileName
    val code = serverName?.let { Countries.codeFrom(it) }
    val hero = when {
        server == null && !active -> HeroState.NO_SERVER
        state == VpnState.CONNECTED -> HeroState.ON
        state == VpnState.CONNECTING || state == VpnState.DISCONNECTING -> HeroState.CONNECTING
        else -> HeroState.OFF
    }

    // Measure the selected server, so its row shows a real latency; again
    // once the tunnel is up, in case the first try found no network.
    LaunchedEffect(server?.id, state == VpnState.CONNECTED) {
        server?.id?.let(onPing)
    }

    var renameTarget by remember { mutableStateOf<StoredProfile?>(null) }

    // The map and glow follow the connect button while it scrolls away and
    // fade out on the way; read only while drawing, so scrolling is cheap.
    val list = rememberLazyListState()
    var heroCenterY by remember { mutableFloatStateOf(Float.NaN) }
    var topHeight by remember { mutableIntStateOf(0) }
    val fadeDistance = with(LocalDensity.current) { 260.dp.toPx() }
    val backdropAlpha: () -> Float = {
        val scrolled = when (list.firstVisibleItemIndex) {
            0 -> list.firstVisibleItemScrollOffset.toFloat()
            1 -> topHeight + list.firstVisibleItemScrollOffset.toFloat()
            else -> fadeDistance
        }
        (1f - scrolled / fadeDistance).coerceIn(0f, 1f)
    }

    Box(Modifier.fillMaxSize().background(kc.page)) {
        val pin = Countries.point(code)?.let { (lon, lat) -> mapPoint(lon, lat) }
        HeroBackdrop(hero, centerY = { heroCenterY }, pin = pin, alpha = backdropAlpha)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(bottom = navBarClearance() + 24.dp),
        ) {
            item(key = "top") {
                // One item with the bar, so the items below keep their indexes.
                Column(Modifier.fillMaxWidth().onSizeChanged { topHeight = it.height }) {
                    TopBar(onOpenSettings = onOpenSettings, onAdd = onAdd)
                    UpdateCard(update, onUpdate = onUpdate, onLater = onDismissUpdate)
                }
            }
            item(key = "hero") {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ConnectDisc(
                        state = hero,
                        label = when {
                            hero == HeroState.NO_SERVER -> "Добавить сервер"
                            state == VpnState.DISCONNECTING -> "Отключение…"
                            hero == HeroState.ON -> "Отключить VPN"
                            hero == HeroState.CONNECTING -> "Отменить подключение"
                            else -> "Подключить VPN"
                        },
                        // Without a server the big button opens "add server".
                        onClick = if (hero == HeroState.NO_SERVER) onAdd else onToggle,
                        enabled = state != VpnState.DISCONNECTING,
                        // Unclipped position: boundsInRoot() stops at the list's edge.
                        modifier = Modifier.onGloballyPositioned { heroCenterY = it.positionInRoot().y + it.size.height / 2f },
                    )
                    StatusBlock(
                        state = state,
                        hero = hero,
                        since = status.connectedSince,
                        message = status.message,
                        place = Countries.name(code) ?: serverName?.let { Countries.stripFlags(it) },
                    )
                }
            }
            serverSections(
                profiles = profiles,
                pings = pings,
                whitelist = whitelist,
                actions = actions,
                onRename = { renameTarget = it },
                onAdd = onAdd,
                onPaste = onPaste,
                onScan = onScan,
            )
        }
        // Rows scrolled under the status bar fade out; once the connect
        // button is gone, a small "Серверы" title shows where you are.
        val listShown by remember { derivedStateOf { list.firstVisibleItemIndex >= 2 } }
        ScrollEdge("Серверы", listShown)
    }

    renameTarget?.let { target ->
        // The whole name, flags included: the flag sets the country and the map pin.
        RenameDialog(target.name, onDismiss = { renameTarget = null }) { name ->
            actions.rename(target.id, name)
            renameTarget = null
        }
    }
}

/** Settings on the left, "add server" on the right, as in Happ. */
@Composable
private fun TopBar(onOpenSettings: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(R.drawable.ic_tab_settings, "Настройки", onClick = onOpenSettings, iconSize = 22.dp)
        Spacer(Modifier.weight(1f))
        GlassIconButton(R.drawable.ic_plus_ios, "Добавить сервер", onClick = onAdd, fill = kc.cta, iconSize = 18.dp)
    }
}

/**
 * A newer build on the owner's panel. «Обновить» downloads it in the
 * browser and Android's installer takes over; «Позже» hides it until an
 * even newer one. It slides in and out, so the screen does not jump.
 */
@Composable
private fun UpdateCard(update: AppUpdate?, onUpdate: (AppUpdate) -> Unit, onLater: () -> Unit) {
    // The one shown last, for the way out after «Позже».
    val last = remember { mutableStateOf(update) }
    SideEffect { if (update != null) last.value = update }
    AnimatedVisibility(
        visible = update != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        (update ?: last.value)?.let { shown ->
            InsetGroup(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(R.drawable.ic_refresh_ios, kc.green)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Доступна новая версия ${shown.versionName}",
                            style = IosType.headline,
                            color = kc.label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Файл скачается в браузере — откройте его, чтобы установить",
                            style = IosType.footnote,
                            color = kc.secondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton("Позже", onClick = onLater, modifier = Modifier.weight(1f))
                    PrimaryButton("Обновить", onClick = { onUpdate(shown) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Opens [url] in the browser; false when it cannot, usually because the phone has none. */
private fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (e: Exception) {
    // The class only: the exception's text repeats the intent, with the panel's host.
    AppLog.w("the update link did not open: ${e.javaClass.simpleName}")
    false
}

@Composable
private fun StatusBlock(state: VpnState, hero: HeroState, since: Long, message: String?, place: String?) {
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
    // The timer's own line height, which grows with the system font size:
    // a fixed height would cut the digits on phones with larger text.
    val timerFull = with(LocalDensity.current) { IosType.timer.lineHeight.toDp() } + 2.dp
    val timerHeight by animateDpAsState(if (showTimer) timerFull else 0.dp, tween(450), label = "timer")
    Column(
        // At least the connected height, so the list does not jump between
        // states; taller with big system fonts instead of cutting text.
        Modifier.padding(top = 14.dp).heightIn(min = 108.dp).fillMaxWidth().padding(horizontal = 24.dp),
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
            if (showTimer) SessionTimer(since)
        }
        // While connected the message is a notice, e.g. that the server was switched.
        val notice = message?.takeIf { state == VpnState.CONNECTED && it.isNotBlank() }
        val subline = when {
            state == VpnState.ERROR && !message.isNullOrBlank() -> message
            notice != null -> notice
            // Switched off by the system or another VPN app, not by the user.
            state == VpnState.DISCONNECTED && !message.isNullOrBlank() && hero != HeroState.NO_SERVER -> message
            hero == HeroState.NO_SERVER -> "Добавьте сервер, чтобы подключиться"
            state == VpnState.CONNECTED -> listOfNotNull(place, "соединение защищено").joinToString(" · ")
            state == VpnState.CONNECTING -> "Устанавливаем защищённое соединение…"
            state == VpnState.DISCONNECTING -> "Отключаем…"
            else -> "Нажмите, чтобы подключиться"
        }
        Text(
            subline,
            style = IosType.subhead,
            color = when {
                state == VpnState.ERROR -> kc.red
                notice != null -> kc.orange
                else -> kc.secondary
            },
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The time connected, ticking once a second. Its own composable, so each
 * tick redraws only this text, not the whole screen and the server list.
 */
@Composable
private fun SessionTimer(since: Long) {
    val now = rememberSecondsTicker(active = true)
    Text(
        formatDuration(((now - since) / 1000).coerceAtLeast(0)),
        style = IosType.timer,
        color = kc.label,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Lit bars (of four) and their colour for a delay of [ms]; the widget draws the same grades its own way. */
@Composable
fun pingLevel(ms: Long) = when (pingGrade(ms)) {
    PingGrade.GREAT -> 4 to kc.green
    PingGrade.GOOD -> 3 to kc.green
    PingGrade.FAIR -> 2 to kc.orange
    PingGrade.POOR -> 1 to kc.red
}
