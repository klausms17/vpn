package com.klausms.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klausms.vpn.R
import com.klausms.vpn.ui.components.GlassIconButton
import com.klausms.vpn.ui.components.PrimaryButton
import com.klausms.vpn.ui.components.SecondaryButton
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc

/** Sheet for pasting keys or a subscription link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKeySheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Not saved in instance state: pasted text can be large.
    var text by remember { mutableStateOf("") }
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
                    GlassIconButton(R.drawable.ic_close_ios, "Закрыть", onClick = onDismiss, iconSize = 16.dp)
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
                    Box {
                        if (text.isEmpty()) {
                            Text("vless://… или ссылка на подписку", style = IosType.subhead, color = kc.tertiary)
                        }
                        inner()
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
            if (text.isBlank()) {
                PrimaryButton(
                    "Вставить из буфера",
                    onClick = {
                        val clip = readClipboard(context)
                        if (clip == null) clipboardEmpty = true else { text = clip; clipboardEmpty = false }
                    },
                    icon = R.drawable.ic_clipboard_ios,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Добавить", onClick = {}, enabled = false)
            } else {
                PrimaryButton("Добавить", onClick = { onAdd(text) })
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Очистить", onClick = { text = "" })
            }
        }
    }
}
