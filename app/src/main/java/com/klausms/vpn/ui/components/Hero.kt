package com.klausms.vpn.ui.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klausms.vpn.R
import com.klausms.vpn.ui.theme.kc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

enum class HeroState { OFF, CONNECTING, ON, NO_SERVER }

// The map artwork is laid out on a 390-wide board, anchored so that the hero
// disc centre sits at (195, 303) and Amsterdam at (120, 191); Mercator at
// 6.84 units per degree (Natural Earth land, public domain).
private const val BOARD_W = 390f
private const val HERO_Y = 303f
private const val MAP_H = 600f
private const val PX_PER_DEG = 6.84
private const val ANCHOR_X = 120.0
private const val ANCHOR_Y = 191.0
private const val ANCHOR_LON = 4.9
private const val ANCHOR_LAT = 52.37

private fun merc(lat: Double) = ln(tan(PI / 4 + min(lat, 75.0) * PI / 360))

/** Board coordinates of a place on the map. */
fun mapPoint(lon: Double, lat: Double): Offset {
    val r = PX_PER_DEG * 180 / PI
    val x = ANCHOR_X + (lon - ANCHOR_LON) * PX_PER_DEG
    val y = ANCHOR_Y - (merc(lat) - merc(ANCHOR_LAT)) * r
    return Offset(x.toFloat(), y.toFloat())
}

private suspend fun loadLand(context: Context): Path = withContext(Dispatchers.Default) {
    val d = context.resources.openRawResource(R.raw.europe_land).bufferedReader().use { it.readText() }
    PathParser().parsePathString(d).toPath().apply { fillType = PathFillType.EvenOdd }
}

/**
 * The atmosphere behind the main screen: a faint map of Europe, a green
 * glow while connected (amber while connecting) and a pin on the server's
 * country. [heroCenterY] is where the connect disc is, in pixels.
 */
@Composable
fun HeroBackdrop(state: HeroState, heroCenterY: Float, pin: Offset?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val land by produceState<Path?>(null) { value = loadLand(context) }
    val on = state == HeroState.ON
    val connecting = state == HeroState.CONNECTING
    val green by animateFloatAsState(if (on) 1f else 0f, tween(700), label = "green")
    val amber by animateFloatAsState(if (connecting) 1f else 0f, tween(500), label = "amber")
    val mapInk by animateColorAsState(if (on) Color(0x1A30D158) else Color(0x0FEBEBF5), tween(700), label = "mapInk")
    val pinInk by animateColorAsState(
        when {
            on -> kc.green
            connecting -> kc.orange
            else -> kc.gray
        },
        tween(500),
        label = "pin",
    )

    Canvas(modifier.fillMaxSize()) {
        val s = size.width / BOARD_W
        val top = heroCenterY - HERO_Y * s
        // Ambient light around the hero.
        drawRect(
            Brush.radialGradient(
                0f to Color(0x29636366),
                0.45f to Color(0x1A2C2C2E),
                0.78f to Color.Transparent,
                center = Offset(size.width / 2, heroCenterY),
                radius = size.height * 0.62f,
            ),
        )
        // Green wash from the top while connected.
        if (green > 0f) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color(0x7309341D).copy(alpha = 0.45f * green),
                    0.4f to Color(0x40052012).copy(alpha = 0.25f * green),
                    0.74f to Color.Transparent,
                    endY = size.height,
                ),
            )
        }
        // Map, fading out towards the lower half.
        land?.let { path ->
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
            withTransform({
                translate(0f, top)
                scale(s, s, pivot = Offset.Zero)
            }) {
                drawPath(path, mapInk)
            }
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    (120f / MAP_H) to Color.Black,
                    (363f / MAP_H) to Color.Black,
                    1f to Color.Transparent,
                    startY = top,
                    endY = top + MAP_H * s,
                ),
                blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
        }
        // Glow around the disc.
        val center = Offset(size.width / 2, heroCenterY)
        if (green > 0f) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0x4230D158).copy(alpha = 0.26f * green),
                    0.36f to Color(0x1F30D158).copy(alpha = 0.12f * green),
                    0.62f to Color(0x0A30D158).copy(alpha = 0.04f * green),
                    1f to Color.Transparent,
                    center = center,
                    radius = 210f * s,
                ),
                radius = 210f * s,
                center = center,
            )
        }
        if (amber > 0f) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0x1AFF9230).copy(alpha = 0.10f * amber),
                    0.55f to Color(0x09FF9230).copy(alpha = 0.035f * amber),
                    1f to Color.Transparent,
                    center = center,
                    radius = 160f * s,
                ),
                radius = 160f * s,
                center = center,
            )
        }
        // Server location.
        if (pin != null && state != HeroState.NO_SERVER) {
            val p = Offset(pin.x * s, top + pin.y * s)
            if (p.x in 0f..size.width && p.y in 0f..heroCenterY) {
                drawCircle(pinInk.copy(alpha = 0.2f), radius = 8f * s, center = p)
                drawCircle(pinInk, radius = 3f * s, center = p)
            }
        }
    }
}

/**
 * The connect button: a 168 pt glass disc, green when connected, with a
 * spinning arc while connecting. Animations run only while connecting.
 */
@Composable
fun ConnectDisc(state: HeroState, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source, pressed = 1.08f)
    val on = state == HeroState.ON
    val connecting = state == HeroState.CONNECTING
    val glyph by animateColorAsState(
        when (state) {
            HeroState.ON, HeroState.OFF -> Color.White
            else -> kc.secondary
        },
        tween(400),
        label = "glyph",
    )
    Box(modifier.size(188.dp), contentAlignment = Alignment.Center) {
        if (connecting) {
            val transition = rememberInfiniteTransition(label = "spin")
            val angle by transition.animateFloat(
                0f,
                360f,
                infiniteRepeatable(tween(1_000, easing = LinearEasing)),
                label = "angle",
            )
            val track = kc.quaternary.copy(alpha = 0.08f)
            val orange = kc.orange
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawCircle(track, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                rotate(angle) {
                    drawArc(orange, -90f, 84f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        // Read only while drawing (graphicsLayer below): no recomposition
        // every frame while connecting.
        val rim: State<Float> = if (connecting) {
            val breathe = rememberInfiniteTransition(label = "breathe")
            breathe.animateFloat(0.3f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "rim")
        } else {
            remember { mutableFloatStateOf(1f) }
        }
        Box(
            Modifier
                .size(168.dp)
                .scale(scale)
                .shadow(22.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(if (on) Color(0xB30E7024) else Color(0x663A3A3C))
                .background(
                    Brush.radialGradient(
                        if (on) {
                            listOf(Color(0x6630D158), Color(0x7030D158), Color(0xB330D158))
                        } else {
                            listOf(Color(0x08FFFFFF), Color(0x0DFFFFFF), Color(0x21FFFFFF))
                        },
                    ),
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (on) 0.14f else 0.08f),
                        0.34f to Color.Transparent,
                    ),
                )
                .tap(source, enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = rim.value }
                    .border(
                        1.25.dp,
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.78f),
                            0.3f to Color.White.copy(alpha = 0.14f),
                            0.5f to Color.Transparent,
                            0.72f to Color.White.copy(alpha = 0.06f),
                            1f to Color.White.copy(alpha = 0.30f),
                        ),
                        CircleShape,
                    ),
            )
            IosIcon(R.drawable.ic_power_ios, glyph, Modifier.size(56.dp))
        }
    }
}

/** A round flag (the emoji clipped to a circle), or a globe when unknown. */
@Composable
fun CircleFlag(code: String?, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(kc.cardPressed)
            .border(0.5.dp, Color(0x1FFFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val flag = code?.let { Countries.flag(it) }.orEmpty()
        if (flag.isNotEmpty()) {
            androidx.compose.material3.Text(
                flag,
                fontSize = (size.value * 1.05f).sp,
                modifier = Modifier.graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                },
            )
        } else {
            IosIcon(R.drawable.ic_tab_servers, kc.secondary, Modifier.size(size * 0.6f))
        }
    }
}
