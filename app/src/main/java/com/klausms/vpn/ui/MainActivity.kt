package com.klausms.vpn.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.ui.components.BusyPill
import com.klausms.vpn.ui.components.GlassToast
import com.klausms.vpn.ui.screens.AddKeySheet
import com.klausms.vpn.ui.screens.AppsScreen
import com.klausms.vpn.ui.screens.HomeScreen
import com.klausms.vpn.ui.screens.LicensesScreen
import com.klausms.vpn.ui.screens.LogsScreen
import com.klausms.vpn.ui.screens.SettingsScreen
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.KlausTheme
import com.klausms.vpn.ui.theme.kc

class MainActivity : ComponentActivity() {
    companion object {
        /**
         * Sent by the Quick Settings tile and the widget when the app is
         * needed to connect (VPN permission, no server yet). Only connects,
         * never disconnects.
         */
        const val ACTION_CONNECT = "com.klausms.vpn.ui.CONNECT"

        private const val KEY_SHARED_TEXT = "shared_text"
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
            override fun onStart(owner: LifecycleOwner) = vm.vpn.bind()
            override fun onStop(owner: LifecycleOwner) = vm.vpn.unbind()
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

        Box(Modifier.fillMaxSize().background(kc.page)) {
            when (top) {
                "settings" -> SettingsScreen(vm = vm, onBack = back, onNavigate = push)
                "apps" -> AppsScreen(vm = vm, includeMode = false, onBack = back)
                "logs" -> LogsScreen(vm = vm, onBack = back)
                "licenses" -> {
                    val coreVersion by vm.coreVersion.collectAsStateWithLifecycle()
                    LicensesScreen(coreVersion, onBack = back)
                }
                else -> HomeScreen(
                    vm = vm,
                    onToggle = ::toggleVpn,
                    onAdd = { showAdd = true },
                    onOpenSettings = { push("settings") },
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
            AddKeySheet(onDismiss = { showAdd = false }, onAdd = { text -> showAdd = false; vm.import(text) })
        }
        // Shared keys are confirmed on whatever screen is open.
        sharedText?.let { text ->
            AlertDialog(
                onDismissRequest = { sharedText = null },
                containerColor = kc.card,
                title = { Text("Добавить из «Поделиться»?", style = IosType.headline, color = kc.label) },
                text = {
                    Text(
                        "Приложение получило текст. Если в нём есть ключи или ссылка на подписку, они будут добавлены.",
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
            ACTION_CONNECT -> {
                val state = vm.status.value.state
                if (state != VpnState.CONNECTED && state != VpnState.CONNECTING) toggleVpn()
            }
        }
    }

    private fun toggleVpn() {
        when (vm.status.value.state) {
            VpnState.CONNECTED, VpnState.CONNECTING -> vm.stopVpn()
            VpnState.DISCONNECTING -> Unit
            else -> {
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
        }
    }

    private fun connectWithPermission() {
        val request = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            vm.startVpnDenied()
            return
        }
        if (request != null) vpnPermission.launch(request) else vm.startVpn()
    }
}
