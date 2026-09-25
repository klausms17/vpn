package com.klausms.vpn.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.ui.screens.AppsScreen
import com.klausms.vpn.ui.screens.HomeScreen
import com.klausms.vpn.ui.screens.LogsScreen
import com.klausms.vpn.ui.screens.SettingsScreen
import com.klausms.vpn.ui.theme.KlausTheme

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
        enableEdgeToEdge()
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
                var route by rememberSaveable { mutableStateOf("home") }
                BackHandler(enabled = route != "home") {
                    route = if (route == "settings") "home" else "settings"
                }
                when {
                    route == "home" -> HomeScreen(
                        vm = vm,
                        onToggle = ::toggleVpn,
                        onOpenSettings = { route = "settings" },
                    )
                    route == "settings" -> SettingsScreen(vm = vm, onBack = { route = "home" }, onNavigate = { route = it })
                    route.startsWith("apps:") -> AppsScreen(
                        vm = vm,
                        includeMode = route == "apps:include",
                        onBack = { route = "settings" },
                    )
                    route == "logs" -> LogsScreen(vm = vm, onBack = { route = "settings" })
                    else -> route = "home"
                }
                // Shared keys are confirmed on whatever screen is open.
                sharedText?.let { text ->
                    AlertDialog(
                        onDismissRequest = { sharedText = null },
                        title = { Text("Добавить из «Поделиться»?") },
                        text = { Text("Приложение получило текст. Если в нём есть ключи или ссылка на подписку, они будут добавлены.") },
                        confirmButton = {
                            TextButton(onClick = { vm.import(text); sharedText = null }) { Text("Добавить") }
                        },
                        dismissButton = { TextButton(onClick = { sharedText = null }) { Text("Отмена") } },
                    )
                }
            }
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
