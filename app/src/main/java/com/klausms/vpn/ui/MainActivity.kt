package com.klausms.vpn.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.ui.components.BusyPill
import com.klausms.vpn.ui.components.GlassToast
import com.klausms.vpn.ui.screens.AddKeySheet
import com.klausms.vpn.ui.screens.AppsScreen
import com.klausms.vpn.ui.screens.HomeScreen
import com.klausms.vpn.ui.screens.LicensesScreen
import com.klausms.vpn.ui.screens.LogsScreen
import com.klausms.vpn.ui.screens.ScanScreen
import com.klausms.vpn.ui.screens.SettingsScreen
import com.klausms.vpn.ui.screens.readClipboard
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.PhoneSettings
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.KlausTheme
import com.klausms.vpn.ui.theme.kc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    companion object {
        /**
         * Sent by the Quick Settings tile and the widget when the app is
         * needed to connect (VPN permission, no server yet). Only connects,
         * never disconnects.
         */
        const val ACTION_CONNECT = "com.klausms.vpn.ui.CONNECT"

        private const val KEY_SHARED_TEXT = "shared_text"

        /** How long a tap waits for the VPN process's own state after the app comes back. */
        private const val STATUS_WAIT_MS = 2_000L
    }

    private val vm: MainViewModel by viewModels()

    /** Text shared into the app, waiting for the user's confirmation. */
    private var sharedText by mutableStateOf<String?>(null)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) vm.startVpn()
        else vm.startVpnDenied()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The VPN works either way; the notification is only a status display.
        connectWithPermission()
    }

    /** The scanner is to open (the camera may be used). */
    private var scanRequested by mutableStateOf(false)

    /** The camera was refused: say how to allow it. */
    private var cameraRefused by mutableStateOf(false)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanRequested = true else cameraRefused = true
    }

    private val hasCamera by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on a clear bar always: the app is always dark, even
        // when the phone uses the light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Talk to the VPN process only while visible: no background work.
            override fun onStart(owner: LifecycleOwner) {
                vm.vpn.bind()
                vm.onAppVisible()
            }

            override fun onStop(owner: LifecycleOwner) {
                vm.onAppHidden()
                vm.vpn.unbind()
            }
        })
        sharedText = savedInstanceState?.getString(KEY_SHARED_TEXT)
        // Handle the launch intent once: not again after a rotation or when
        // reopened from Recents, which re-deliver the original intent.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)

        setContent {
            KlausTheme {
                AppShell()
            }
        }
    }

    @Composable
    private fun AppShell() {
        // Screens pushed over the main one, e.g. "settings/apps"; "" is home.
        var route by rememberSaveable { mutableStateOf("") }
        val top = route.substringAfterLast('/')
        val push: (String) -> Unit = { screen -> route = if (route.isEmpty()) screen else "$route/$screen" }
        val back: () -> Unit = { route = route.substringBeforeLast('/', "") }
        var showAdd by rememberSaveable { mutableStateOf(false) }
        val busy by vm.busy.collectAsStateWithLifecycle()
        val toasts = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { vm.messages.collect { toasts.showSnackbar(it) } }

        BackHandler(enabled = route.isNotEmpty(), onBack = back)
        LaunchedEffect(scanRequested) {
            if (scanRequested) {
                scanRequested = false
                if (top != "scan") push("scan")
            }
        }

        Box(Modifier.fillMaxSize().background(kc.page)) {
            when (top) {
                "settings" -> SettingsScreen(vm = vm, onBack = back, onNavigate = push)
                "apps" -> AppsScreen(vm = vm, includeMode = false, onBack = back)
                "logs" -> LogsScreen(onBack = back)
                "licenses" -> {
                    val coreVersion by vm.coreVersion.collectAsStateWithLifecycle()
                    LicensesScreen(coreVersion, onBack = back)
                }
                "scan" -> ScanScreen(
                    onBack = back,
                    onFound = { text -> back(); vm.import(text) },
                    // Reopened after the camera was revoked in Settings: ask again.
                    onNoPermission = { back(); openScanner() },
                )
                else -> HomeScreen(
                    vm = vm,
                    onToggle = ::toggleVpn,
                    onAdd = { showAdd = true },
                    onOpenSettings = { push("settings") },
                    onPaste = ::pasteClipboard,
                    onScan = if (hasCamera) ::openScanner else null,
                )
            }
            Column(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BusyPill(busy)
                SnackbarHost(toasts) { data -> GlassToast(data.visuals.message) }
            }
        }

        if (showAdd) {
            AddKeySheet(
                onDismiss = { showAdd = false },
                onAdd = { text -> showAdd = false; vm.import(text) },
                onScan = if (hasCamera) { { showAdd = false; openScanner() } } else null,
            )
        }
        val backgroundTip by vm.backgroundTip.collectAsStateWithLifecycle()
        if (backgroundTip) {
            BackgroundTip(
                onSetUp = {
                    vm.backgroundTipDone()
                    if (top != "settings") push("settings")
                },
                onLater = vm::backgroundTipDone,
            )
        }
        if (cameraRefused) {
            AlertDialog(
                onDismissRequest = { cameraRefused = false },
                containerColor = kc.card,
                title = { Text("Нет доступа к камере", style = IosType.headline, color = kc.label) },
                text = {
                    Text(
                        "Чтобы сканировать QR-коды, разрешите приложению камеру в настройках. Или скопируйте ключ и нажмите «Вставить из буфера».",
                        style = IosType.subhead,
                        color = kc.secondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { cameraRefused = false; openAppSettings() }) { Text("Настройки", color = kc.green) }
                },
                dismissButton = { TextButton(onClick = { cameraRefused = false }) { Text("Отмена", color = kc.green) } },
            )
        }
        // Shared keys and "add" links are confirmed on whatever screen is open.
        sharedText?.let { text ->
            // Any web page can open a link: say where the subscription comes from.
            val host = ImportText.subscriptionUrl(text)?.let(DeepLink::urlHost)
            AlertDialog(
                onDismissRequest = { sharedText = null },
                containerColor = kc.card,
                title = { Text("Добавить ключи или подписку?", style = IosType.headline, color = kc.label) },
                text = {
                    Text(
                        if (host != null) {
                            "Подписка с адреса $host. Добавляйте только ссылки от тех, кому доверяете."
                        } else {
                            "Приложение получило текст. Если в нём есть ключи или ссылка на подписку, они будут добавлены."
                        },
                        style = IosType.subhead,
                        color = kc.secondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.import(text); sharedText = null }) { Text("Добавить", color = kc.green) }
                },
                dismissButton = { TextButton(onClick = { sharedText = null }) { Text("Отмена", color = kc.green) } },
            )
        }
    }

    /**
     * Once, after the first connection, on a phone that may stop the VPN in
     * the background. A brand with its own autostart switch gets the
     * Settings screen, where both switches are; elsewhere the system's own
     * one-tap request opens.
     */
    @Composable
    private fun BackgroundTip(onSetUp: () -> Unit, onLater: () -> Unit) {
        val context = LocalContext.current
        val oem = remember { PhoneSettings.oem() }
        AlertDialog(
            onDismissRequest = onLater,
            containerColor = kc.card,
            title = { Text("Чтобы VPN не выключался", style = IosType.headline, color = kc.label) },
            text = {
                Text(
                    if (oem != null) {
                        "Телефон может закрывать приложения в фоне, чтобы беречь заряд, и VPN выключится. Разрешите Klaus VPN работу в фоне и автозапуск."
                    } else {
                        "Телефон может закрывать приложения в фоне, чтобы беречь заряд, и VPN выключится. Разрешите Klaus VPN работу в фоне."
                    },
                    style = IosType.subhead,
                    color = kc.secondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (oem != null) {
                            onSetUp()
                        } else {
                            onLater()
                            PhoneSettings.openBatterySettings(context)
                        }
                    },
                ) { Text(if (oem != null) "Настроить" else "Разрешить", color = kc.green) }
            },
            dismissButton = { TextButton(onClick = onLater) { Text("Позже", color = kc.green) } },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sharedText?.let { outState.putString(KEY_SHARED_TEXT, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
                if (!text.isNullOrEmpty()) sharedText = text.take(64 * 1024)
            }
            // klausvpn://add/<link> from a subscription page or a messenger.
            Intent.ACTION_VIEW -> DeepLink.payload(intent.dataString)?.let { sharedText = it }
            // Always the connect path, whatever the cached status says: it may
            // be from before the app went to the background (e.g. "connected"
            // while the consent was revoked since). Connecting a running
            // tunnel is a no-op for the service.
            ACTION_CONNECT -> connect()
        }
    }

    /** Adds what was copied (a key, several, or a subscription link); false when the clipboard has no text. */
    private fun pasteClipboard(): Boolean {
        val text = readClipboard(this) ?: return false
        vm.import(text.take(256 * 1024))
        return true
    }

    private fun openScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanRequested = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.w("app settings did not open", e)
        }
    }

    /** A tap waiting for the VPN process's own state (see [toggleVpn]). */
    private var tapWaiting = false

    private fun toggleVpn() {
        val shown = vm.status.value.state
        if (vm.vpn.fresh.value) {
            toggle(shown)
            return
        }
        // Just back from the background: the state on screen may be old. Wait
        // a moment for the real one, then do what the tap meant if it still
        // needs doing (an "off" tap on a tunnel that is already gone does
        // nothing).
        if (tapWaiting || shown == VpnState.DISCONNECTING) return
        tapWaiting = true
        lifecycleScope.launch {
            withTimeoutOrNull(STATUS_WAIT_MS) { vm.vpn.fresh.first { it } }
            tapWaiting = false
            // Left the app meanwhile: no dialogs over another app.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            val now = vm.status.value.state
            if (isOn(now) == isOn(shown)) toggle(now)
        }
    }

    private fun isOn(state: VpnState) = state == VpnState.CONNECTED || state == VpnState.CONNECTING

    private fun toggle(state: VpnState) {
        when (state) {
            VpnState.CONNECTED, VpnState.CONNECTING -> vm.stopVpn()
            VpnState.DISCONNECTING -> Unit
            else -> connect()
        }
    }

    private fun connect() {
        if (vm.profiles.value.selected == null) {
            vm.startVpn() // shows "add a key first"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !vm.notificationPermissionAsked()
        ) {
            vm.markNotificationPermissionAsked()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        connectWithPermission()
    }

    private fun connectWithPermission() {
        val request = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            AppLog.w("vpn prepare", e)
            vm.startVpnDenied()
            return
        }
        if (request == null) {
            vm.startVpn()
            return
        }
        // A stripped-down firmware may lack the system's consent dialog.
        try {
            vpnPermission.launch(request)
        } catch (e: Exception) {
            AppLog.w("vpn consent dialog did not open", e)
            vm.startVpnDenied()
        }
    }
}
