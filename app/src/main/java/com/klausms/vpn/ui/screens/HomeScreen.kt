package com.klausms.vpn.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.service.TrafficStats
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.service.VpnStatus
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.PingResult
import com.klausms.vpn.ui.theme.StatusColors
import com.klausms.vpn.util.formatBytes
import com.klausms.vpn.util.formatDuration
import com.klausms.vpn.util.formatSpeed
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val pings by vm.pings.collectAsStateWithLifecycle()
    val whitelist by vm.whitelist.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StoredProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Klaus VPN", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Ic(R.drawable.ic_settings, "Настройки") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Ic(R.drawable.ic_add, "Добавить сервер") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { BusyBar(busy) }
            item {
                ConnectPanel(
                    status = status,
                    traffic = traffic,
                    selectedName = profiles.selected?.name,
                    onToggle = onToggle,
                    onTest = { vm.testConnection() },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Серверы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (profiles.profiles.isNotEmpty()) {
                        TextButton(onClick = { vm.pingAll() }) {
                            Ic(R.drawable.ic_bolt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Проверить все")
                        }
                    }
                }
            }
            if (profiles.profiles.isEmpty()) {
                item { EmptyState(onAdd = { showAdd = true }) }
            }
            val standalone = profiles.profiles.filter { p -> p.subscriptionId == null || profiles.subscriptions.none { it.id == p.subscriptionId } }
            items(standalone, key = { it.id }) { p ->
                ProfileItem(
                    profile = p,
                    selected = p.id == profiles.selectedId,
                    ping = pings[p.id],
                    whitelisted = whitelist[p.address],
                    onSelect = { vm.select(p.id) },
                    onPing = { vm.ping(listOf(p.id)) },
                    onRename = { renameTarget = p },
                    onDelete = { vm.delete(p.id) },
                )
            }
            for (sub in profiles.subscriptions) {
                item(key = "sub-" + sub.id) {
                    SubscriptionHeader(sub, onRefresh = { vm.refreshSubscription(sub.id) }, onDelete = { vm.deleteSubscription(sub.id) })
                }
                items(profiles.profiles.filter { it.subscriptionId == sub.id }, key = { it.id }) { p ->
                    ProfileItem(
                        profile = p,
                        selected = p.id == profiles.selectedId,
                        ping = pings[p.id],
                        whitelisted = whitelist[p.address],
                        onSelect = { vm.select(p.id) },
                        onPing = { vm.ping(listOf(p.id)) },
                        onRename = { renameTarget = p },
                        onDelete = { vm.delete(p.id) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddDialog(onDismiss = { showAdd = false }, onAdd = { text -> showAdd = false; vm.import(text) })
    }
    renameTarget?.let { target ->
        RenameDialog(target.name, onDismiss = { renameTarget = null }, onRename = { vm.rename(target.id, it); renameTarget = null })
    }
}

@Composable
private fun ConnectPanel(
    status: VpnStatus,
    traffic: TrafficStats,
    selectedName: String?,
    onToggle: () -> Unit,
    onTest: () -> Unit,
) {
    val state = status.state
    val color by animateColorAsState(
        when (state) {
            VpnState.CONNECTED -> StatusColors.connected
            VpnState.CONNECTING, VpnState.DISCONNECTING -> StatusColors.connecting
            VpnState.ERROR -> MaterialTheme.colorScheme.error
            VpnState.DISCONNECTED -> StatusColors.idle
        },
        label = "statusColor",
    )
    // Animate only while something is happening: an idle screen must not
    // redraw every frame.
    val pulse = if (state == VpnState.CONNECTING || state == VpnState.DISCONNECTING) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulseScale",
        ).value
    } else {
        1f
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(176.dp)
                .scale(pulse)
                .border(6.dp, color.copy(alpha = 0.25f), CircleShape)
                .padding(10.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(enabled = state != VpnState.DISCONNECTING, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (state == VpnState.CONNECTING || state == VpnState.DISCONNECTING) {
                CircularProgressIndicator(Modifier.size(64.dp), color = Color.White, strokeWidth = 4.dp)
            } else {
                Ic(R.drawable.ic_power, if (state == VpnState.CONNECTED) "Отключить" else "Подключить", Modifier.size(64.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when (state) {
                VpnState.CONNECTED -> "Подключено"
                VpnState.CONNECTING -> "Подключение…"
                VpnState.DISCONNECTING -> "Отключение…"
                VpnState.ERROR -> "Ошибка"
                VpnState.DISCONNECTED -> "Не подключено"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val subtitle = when (state) {
            VpnState.ERROR -> status.message
            VpnState.CONNECTED, VpnState.CONNECTING -> status.profileName ?: selectedName
            else -> selectedName ?: "Сервер не выбран"
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == VpnState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
        if (state == VpnState.CONNECTED) {
            val seconds by produceState(0L, status.connectedSince) {
                while (true) {
                    value = (System.currentTimeMillis() - status.connectedSince) / 1000
                    delay(1000)
                }
            }
            Text(formatDuration(seconds), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Ic(R.drawable.ic_down, "Загрузка", Modifier.size(16.dp))
                Text(" " + formatSpeed(traffic.downRate), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(20.dp))
                Ic(R.drawable.ic_up, "Отдача", Modifier.size(16.dp))
                Text(" " + formatSpeed(traffic.upRate), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "за сессию: " + formatBytes(traffic.downTotal + traffic.upTotal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onTest) { Text("Проверить соединение") }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Пока нет ни одного сервера", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Нажмите «+» и вставьте ключ (vless://, vmess://, trojan://, ss://, hy2://) или ссылку на подписку. " +
                    "Можно также «Поделиться» ключом из мессенджера в это приложение.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAdd) { Text("Добавить ключ") }
        }
    }
}

private fun describe(p: StoredProfile): String {
    val proto = when (p.protocol) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        "hysteria2" -> "Hysteria2"
        else -> p.protocol.uppercase(Locale.ROOT)
    }
    val parts = mutableListOf(proto)
    when (p.security) {
        "reality" -> parts += "REALITY"
        "tls" -> parts += "TLS"
    }
    if (p.network.isNotEmpty() && p.network != "raw" && p.network != "hysteria") parts += p.network.uppercase(Locale.ROOT)
    return parts.joinToString(" · ")
}

@Composable
private fun ProfileItem(
    profile: StoredProfile,
    selected: Boolean,
    ping: PingResult?,
    whitelisted: Int?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(describe(profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (whitelisted == 1) {
                        Spacer(Modifier.width(6.dp))
                        Chip("белый список", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            PingLabel(ping, onPing)
            Box {
                IconButton(onClick = { menu = true }) { Ic(R.drawable.ic_more, "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Проверить") }, leadingIcon = { Ic(R.drawable.ic_bolt) }, onClick = { menu = false; onPing() })
                    DropdownMenuItem(text = { Text("Переименовать") }, leadingIcon = { Ic(R.drawable.ic_edit) }, onClick = { menu = false; onRename() })
                    if (profile.link != null) {
                        DropdownMenuItem(
                            text = { Text("Скопировать ключ") },
                            leadingIcon = { Ic(R.drawable.ic_copy) },
                            onClick = { menu = false; copySensitive(context, profile.link) },
                        )
                    }
                    if (profile.subscriptionId == null) {
                        DropdownMenuItem(text = { Text("Удалить") }, leadingIcon = { Ic(R.drawable.ic_delete) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${profile.name}»?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PingLabel(ping: PingResult?, onPing: () -> Unit) {
    Box(Modifier.width(72.dp).clickable(onClick = onPing), contentAlignment = Alignment.CenterEnd) {
        when (ping) {
            null -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            PingResult.Testing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            is PingResult.Ok -> Text(
                "${ping.ms} мс",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    ping.ms < 300 -> StatusColors.connected
                    ping.ms < 800 -> StatusColors.connecting
                    else -> MaterialTheme.colorScheme.error
                },
            )
            is PingResult.Failed -> Text("нет связи", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SubscriptionHeader(sub: Subscription, onRefresh: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(sub.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val info = describeUserInfo(sub.userInfo)
            val updated = if (sub.updatedAt > 0) "обновлено " + SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(sub.updatedAt)) else null
            Text(
                listOfNotNull(info, updated).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sub.lastError != null) {
                Text(sub.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = onRefresh) { Ic(R.drawable.ic_refresh, "Обновить подписку") }
        Box {
            IconButton(onClick = { menu = true }) { Ic(R.drawable.ic_more, "Ещё") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Удалить подписку") }, leadingIcon = { Ic(R.drawable.ic_delete) }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить подписку «${sub.name}» со всеми серверами?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/** "upload=1; download=2; total=3; expire=4" -> human readable text. */
private fun describeUserInfo(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val values = raw.split(';').mapNotNull { part ->
        val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        k.trim().lowercase(Locale.ROOT) to (v.trim().toLongOrNull() ?: return@mapNotNull null)
    }.toMap()
    val used = (values["upload"] ?: 0) + (values["download"] ?: 0)
    val total = values["total"] ?: 0
    val expire = values["expire"] ?: 0
    val parts = mutableListOf<String>()
    if (total > 0) parts += "${formatBytes(used)} из ${formatBytes(total)}" else if (used > 0) parts += formatBytes(used)
    if (expire > 0) parts += "до " + SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(expire * 1000))
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить сервер") },
        text = {
            Column {
                Text(
                    "Вставьте ключ или ссылку на подписку. Можно сразу несколько ключей, каждый с новой строки.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("vless://… или https://…") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { readClipboard(context)?.let { text = it } }) {
                    Ic(R.drawable.ic_paste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Вставить из буфера")
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text) }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название сервера") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onRename(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Copies a key; Android 13+ hides it from the clipboard preview. */
fun copySensitive(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("key", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
}
