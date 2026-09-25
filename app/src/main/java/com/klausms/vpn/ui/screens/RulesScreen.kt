package com.klausms.vpn.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.ui.MainViewModel

enum class RulesKind(val title: String, val description: String) {
    DIRECT("Всегда напрямую", "Эти сайты и адреса всегда открываются без VPN."),
    PROXY("Всегда через VPN", "Эти сайты и адреса всегда идут через VPN, даже если они российские."),
    BLOCK("Блокировать", "Соединения с этими сайтами и адресами блокируются."),
}

private fun AppSettings.rules(kind: RulesKind) = when (kind) {
    RulesKind.DIRECT -> directRules
    RulesKind.PROXY -> proxyRules
    RulesKind.BLOCK -> blockRules
}

@Composable
fun RulesScreen(vm: MainViewModel, kind: RulesKind, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(settings.rules(kind).joinToString("\n")) }

    fun save() {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(2000)
        vm.updateSettings {
            when (kind) {
                RulesKind.DIRECT -> it.copy(directRules = lines)
                RulesKind.PROXY -> it.copy(proxyRules = lines)
                RulesKind.BLOCK -> it.copy(blockRules = lines)
            }
        }
        onBack()
    }

    Scaffold(
        topBar = {
            BackTopBar(kind.title, onBack) { TextButton(onClick = ::save) { Text("Сохранить") } }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(kind.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "По одному на строке:\n" +
                    "example.com — сайт со всеми поддоменами\n" +
                    "full:www.example.com — только этот адрес\n" +
                    "keyword:bank — все домены со словом\n" +
                    "1.2.3.4 или 10.0.0.0/8 — IP-адреса и сети",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 10,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
