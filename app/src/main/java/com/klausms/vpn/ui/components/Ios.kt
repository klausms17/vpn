package com.klausms.vpn.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ basics

@Composable
fun IosIcon(@DrawableRes id: Int, tint: Color, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(id), description, modifier, tint = tint)
}

/** Scale feedback on press, like iOS controls (no Material ripple). */
@Composable
fun pressScale(source: MutableInteractionSource, pressed: Float = 0.97f): Float {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return scale
}

/** Clickable without a ripple; the caller shows its own pressed state. */
fun Modifier.tap(source: MutableInteractionSource, enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit) =
    clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)

/**
 * The current wall-clock time, updated every second while [active] and the
 * screen is visible. Nothing ticks in the background.
 */
@Composable
fun rememberSecondsTicker(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000 - now % 1_000)
            }
        }
    }
    return now
}

// ------------------------------------------------------------------- glass

/**
 * The Liquid Glass look without a live backdrop blur (works the same on
 * every Android version): a translucent fill, a soft top sheen, a thin
 * specular rim brighter at the top-left and a darkened outer edge.
 */
fun Modifier.glass(shape: Shape, fill: Color = Color(0x8C3A3A3C), elevation: Dp = 12.dp): Modifier = this
    .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .background(fill)
    .background(Brush.verticalGradient(0f to Color(0x12FFFFFF), 0.34f to Color.Transparent))
    .border(
        1.dp,
        Brush.linearGradient(
            0f to Color(0x73FFFFFF),
            0.25f to Color.Transparent,
            0.75f to Color.Transparent,
            1f to Color(0x26FFFFFF),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        shape,
    )

// ------------------------------------------------------------- navigation

/** Large title row: 34 pt bold title with optional trailing controls. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = IosType.largeTitle, color = kc.label, maxLines = 1, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** A 44 pt round glass button with an icon (toolbar buttons). */
@Composable
fun GlassIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, tint: Color = kc.label, iconSize: Dp = 20.dp) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source, 1.08f)
    Box(
        Modifier
            .size(44.dp)
            .scale(scale)
            .glass(CircleShape, elevation = 6.dp)
            .tap(source, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        IosIcon(icon, tint, Modifier.size(iconSize))
    }
}

/** Compact navigation bar for pushed screens: "‹ Назад" and a centred title. */
@Composable
fun NavBar(title: String, onBack: () -> Unit, backLabel: String = "Назад", trailing: @Composable RowScope.() -> Unit = {}) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(44.dp),
    ) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .heightIn(min = 44.dp)
                .tap(source, onClick = onBack)
                .graphicsLayer { alpha = if (pressed) 0.55f else 1f }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IosIcon(R.drawable.ic_back_ios, kc.green, Modifier.size(width = 11.dp, height = 18.dp))
            Spacer(Modifier.width(6.dp))
            Text(backLabel, style = IosType.body, color = kc.green)
        }
        Text(
            title,
            style = IosType.headline,
            color = kc.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

/** A text button in the accent colour (toolbar actions like «Готово»). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = kc.green, enabled: Boolean = true, style: TextStyle = IosType.body) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Text(
        text,
        style = style,
        color = if (enabled) color else kc.tertiary,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .tap(source, enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (pressed) 0.55f else 1f }
            .padding(vertical = 11.dp),
    )
}

// ------------------------------------------------------------ grouped list

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = IosType.footnote,
        color = kc.secondary,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 7.dp),
    )
}

@Composable
fun SectionFooter(text: String) {
    Text(
        text,
        style = IosType.footnote,
        color = kc.secondary,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 7.dp),
    )
}

/** An inset-grouped card (20 pt side margins, 26 pt corners). */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(kc.card),
        content = content,
    )
}

/** Hairline between rows, inset to where the text starts. */
@Composable
fun RowDivider(start: Dp = 16.dp) {
    Box(
        Modifier
            .padding(start = start)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(kc.separator),
    )
}

/**
 * One row of a grouped list. [leading] is typically an icon tile or a flag;
 * [value] is shown in secondary text before the chevron.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = kc.secondary,
    titleColor: Color = kc.label,
    chevron: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    minHeight: Dp = 44.dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed && onClick != null) kc.cardPressed else Color.Transparent, label = "rowPress")
    val clickMod = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .then(clickMod)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = IosType.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = IosType.subhead, color = kc.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(
                value,
                style = IosType.body,
                color = valueColor,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(10.dp))
            IosIcon(R.drawable.ic_chevron_ios, kc.tertiary, Modifier.size(width = 7.dp, height = 12.dp))
        }
    }
}

/** A 29 pt rounded-square icon tile, like the iOS Settings app. */
@Composable
fun IconTile(@DrawableRes icon: Int, color: Color) {
    Box(
        Modifier
            .size(29.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        IosIcon(icon, Color.White, Modifier.size(18.dp))
    }
}

// ----------------------------------------------------------------- controls

/** iOS switch: 51×31 capsule, green when on. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, description: String? = null) {
    val source = remember { MutableInteractionSource() }
    val track by animateColorAsState(if (checked) kc.green else kc.switchOff, label = "switchTrack")
    val x by animateDpAsState(
        if (checked) 22.dp else 2.dp,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "switchThumb",
    )
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** Filled capsule button (primary action). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    color: Color = kc.cta,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(25.dp))
            .background(if (enabled) color else kc.fill)
            .background(Brush.verticalGradient(0f to Color(0x1AFFFFFF), 0.5f to Color.Transparent))
            .tap(source, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IosIcon(icon, if (enabled) Color.White else kc.tertiary, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = IosType.headline, color = if (enabled) Color.White else kc.tertiary)
    }
}

/** Grey capsule button (secondary action). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(25.dp))
            .background(kc.fill)
            .tap(source, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IosIcon(icon, kc.label, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = IosType.headline, color = kc.label)
    }
}

/** Four signal bars; [level] 0..4 bars lit in [color]. */
@Composable
fun SignalBars(level: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(12.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
        val heights = listOf(3.75.dp, 6.5.dp, 9.25.dp, 12.dp)
        for ((i, h) in heights.withIndex()) {
            Box(
                Modifier
                    .size(width = 2.75.dp, height = h)
                    .clip(RoundedCornerShape(0.9.dp))
                    .background(if (i < level) color else kc.quaternary),
            )
        }
    }
}

/** A small spinner with a caption, for long operations. */
@Composable
fun BusyPill(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Row(
        modifier
            .glass(RoundedCornerShape(20.dp), elevation = 8.dp)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = kc.label, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = IosType.subhead, color = kc.label)
    }
}

/** Snackbar-like toast in the glass style. */
@Composable
fun GlassToast(text: String) {
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .glass(RoundedCornerShape(20.dp), fill = Color(0xE62C2C2E), elevation = 10.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text, style = IosType.subhead, color = kc.label)
    }
}

/** Unused-safe placeholder size for the floating tab bar (content padding). */
val TabBarSpace = 98.dp
