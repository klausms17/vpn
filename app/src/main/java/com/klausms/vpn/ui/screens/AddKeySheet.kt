package com.klausms.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.klausms.vpn.R
import com.klausms.vpn.ui.components.GlassIconButton
import com.klausms.vpn.ui.components.IosIcon
import com.klausms.vpn.ui.components.PrimaryButton
import com.klausms.vpn.ui.components.SecondaryButton
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import kotlinx.coroutines.launch

/** Sheet for pasting keys or a subscription link, or scanning a QR code ([onScan], null without a camera). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKeySheet(onDismiss: () -> Unit, onAdd: (String) -> Unit, onScan: (() -> Unit)? = null) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Slide the sheet away first, then leave (as a swipe down does). Once:
    // a double tap on "Добавить" must not add the keys twice.
    var closing by remember { mutableStateOf(false) }
    val close: (then: () -> Unit) -> Unit = { then ->
        if (!closing) {
            closing = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
        }
    }
    // Survives a rotation; a huge paste is not kept (instance state is small).
    var text by rememberSaveable(stateSaver = CappedText) { mutableStateOf("") }
    var clipboardEmpty by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = kc.card,
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 6.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(kc.tertiary),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    GlassIconButton(R.drawable.ic_close_ios, "Закрыть", onClick = { close(onDismiss) }, iconSize = 16.dp)
                }
                Text("Добавить сервер", style = IosType.headline, color = kc.label, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(256 * 1024) },
                textStyle = IosType.subhead.copy(color = kc.label),
                cursorBrush = SolidColor(kc.green),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp, max = 240.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(kc.fill)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    Row {
                        Box(Modifier.weight(1f)) {
                            if (text.isEmpty()) {
                                Text("vless://… или ссылка на подписку", style = IosType.subhead, color = kc.tertiary)
                            }
                            inner()
                        }
                        if (text.isNotEmpty()) {
                            // iOS clear button.
                            Box(
                                Modifier
                                    .padding(start = 8.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(kc.tertiary)
                                    .clickable(onClickLabel = "Очистить", role = Role.Button) { text = "" }
                                    .semantics { contentDescription = "Очистить" },
                                contentAlignment = Alignment.Center,
                            ) {
                                IosIcon(R.drawable.ic_close_ios, kc.card, Modifier.size(9.dp))
                            }
                        }
                    }
                },
            )
            Text(
                if (clipboardEmpty) "В буфере обмена нет текста" else "Можно вставить несколько ключей, каждый с новой строки",
                style = IosType.footnote,
                color = if (clipboardEmpty) kc.orange else kc.secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            // The buttons never swap places: a quick second tap on "paste"
            // must not add the keys unseen.
            val paste: () -> Unit = {
                val clip = readClipboard(context)
                if (clip == null) clipboardEmpty = true else { text = clip; clipboardEmpty = false }
            }
            if (text.isBlank()) {
                PrimaryButton("Вставить из буфера", onClick = paste, icon = R.drawable.ic_clipboard_ios)
            } else {
                SecondaryButton("Вставить из буфера", onClick = paste, icon = R.drawable.ic_clipboard_ios)
            }
            if (onScan != null) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Сканировать QR-код", onClick = { close(onScan) }, icon = R.drawable.ic_qr_ios)
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                "Добавить",
                onClick = {
                    val keys = text
                    close { onAdd(keys) }
                },
                enabled = text.isNotBlank(),
            )
        }
    }
}

private val CappedText = Saver<String, String>(
    save = { if (it.length <= 16 * 1024) it else "" },
    restore = { it },
)
