package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial
import com.kingzcheung.xime.ui.theme.liquidGlassCap

internal const val KEY_GLOW_DURATION_MS = 500
internal const val KEY_CAP_DURATION_MS = 235
/** Background tint kept while a key is held after the press animation, and its release fade. */
internal const val KEY_GLOW_HOLD_ALPHA = 0.4f
internal const val KEY_GLOW_RELEASE_MS = 180

private data class GlowSquare(val x: Float, val y: Float, val side: Float, val angle: Float, val spin: Float)

/** Transform only the cap/shadow, inside the unchanged key hit target. */
internal fun Modifier.keyGlow(
    cap: Modifier, animateCap: Boolean = true,
    materialLevel: com.kingzcheung.xime.ui.theme.MaterialLevel = com.kingzcheung.xime.ui.theme.MaterialLevel.BASE,
    particleSize: androidx.compose.ui.unit.DpSize? = null,
    particleOffset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero,
    applyMaterial: Boolean = true,
    // 1 = full glow; drops toward 0 while a semantic flick colour (clear/undo) takes over the key.
    glowVisibility: Float = 1f,
    /** 液态玻璃只给有键帽的按键；表情格、编辑页的无底色键不画。 */
    liquid: Boolean = true,
    /** 液态玻璃（iOS 样式）里键原来的底色：玻璃底色按它从键盘底色反解（Nekogram solveSrcColor）。 */
    glassColor: Color = Color.Unspecified,
): Modifier = composed {
    val visibility by rememberUpdatedState(glowVisibility.coerceIn(0f, 1f))
    val preferences = LocalKeyboardInputPreferences.current
    // 液态玻璃（玻璃开着才生效）代替按键上的视觉样式；蜂窝布局的六边形键不画（高光按圆角矩形画）。
    val context = androidx.compose.ui.platform.LocalContext.current
    val liquidCap = applyMaterial && liquid && preferences.frostedGlass.enabled && !LocalHoneycombKeys.current &&
        com.kingzcheung.xime.ui.theme.LiquidGlass.enabled(context)
    // iOS 透镜自带按压弹簧（鼓起、跟手、回弹），原来的键帽缩放动画（先缩到 0.67）会和它打架，透镜开着时不做。
    val lensPress = liquidCap && com.kingzcheung.xime.ui.theme.LiquidGlass.rich(context)
    val paintedCap = when {
        liquidCap -> Modifier.liquidGlassCap(cap, LocalKeyCornerRadius.current, glassColor)
        applyMaterial -> cap.visualMaterial(VisualStyles.current, LocalKeyCornerRadius.current, materialLevel)
        else -> cap
    }
    val glowEnabled = preferences.keyGlowEnabled
    val motionEnabled = preferences.keyAnimationEnabled && animateCap && !lensPress
    if (!glowEnabled && !motionEnabled) return@composed this.then(paintedCap)
    // One coherent colour per press, as in the reference; overlapping squares
    // vary in brightness instead of mixing three unrelated theme colours.
    // 光色跟随主题强调色（相邻几种色相）；单色主题用带一点冷暖的中性光，不再是固定的七彩。
    val themeAccent = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.accent
    val glowColors = remember(themeAccent) { themeAccent?.let(::themedGlowPalette) ?: glowPalette }
    var color by remember(glowEnabled, motionEnabled) { mutableStateOf(glowColors.first()) }
    val elapsed = remember(glowEnabled, motionEnabled) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var animation by remember(glowEnabled, motionEnabled) { mutableStateOf<Job?>(null) }
    var particles by remember(glowEnabled, motionEnabled) { mutableStateOf(emptyList<GlowSquare>()) }
    // While the finger stays down (e.g. a repeating arrow key) the colour stays lit after the
    // squares finish, and only fades once the key is released.
    val held = remember(glowEnabled, motionEnabled) { Animatable(0f) }
    var release by remember(glowEnabled, motionEnabled) { mutableStateOf<Job?>(null) }
    DisposableEffect(glowEnabled, motionEnabled) {
        onDispose { animation?.cancel() }
    }
    val observed = this.pointerInput(glowEnabled, motionEnabled) {
        awaitEachGesture {
            // 只旁观 DOWN，因此同一帧内按下/松开也不会丢失动画；不干扰拂动和长按。
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            animation?.cancel()
            release?.cancel()
            if (glowEnabled) scope.launch { held.snapTo(KEY_GLOW_HOLD_ALPHA) }
            if (glowEnabled) {
                color = glowColors[Random.nextInt(glowColors.size)]
                particles = List(4) { index ->
                    GlowSquare(0.2f + (index % 2) * 0.6f + (Random.nextFloat() - 0.5f) * 0.25f,
                        0.15f + (index / 2) * 0.7f + (Random.nextFloat() - 0.5f) * 0.25f,
                        0.85f + Random.nextFloat() * 0.50f,
                        Random.nextFloat() * 60f - 30f, Random.nextFloat() * 100f - 50f)
                }
            }
            animation = scope.launch {
                elapsed.snapTo(0f)
                // This is only the clock. Scale, travel and opacity each have their own
                // continuous nonlinear curve below; none waits at an intermediate state.
                val duration = if (glowEnabled) KEY_GLOW_DURATION_MS else KEY_CAP_DURATION_MS
                // Keep the original cap curve; motion-only stops its clock after cap recovery.
                elapsed.animateTo(duration.toFloat() / KEY_GLOW_DURATION_MS, tween(duration, easing = LinearEasing))
            }
            // Watch (never consume) until every pointer is up, then let the held colour fade.
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            if (glowEnabled) release = scope.launch { held.animateTo(0f, tween(KEY_GLOW_RELEASE_MS)) }
        }
    }
    val animatedCap = if (motionEnabled) Modifier.graphicsLayer {
        val scale = keyGlowScale(elapsed.value)
        scaleX = scale
        scaleY = scale
    } else Modifier
    val painted = observed.then(animatedCap).then(paintedCap)
    if (!glowEnabled) return@composed painted
    painted.drawWithCache {
        // A direction sector owns a disc-sized hit target, but its particles use one key's size.
        val lightSize = particleSize?.let { Size(it.width.toPx(), it.height.toPx()) } ?: size
        val lightOrigin = Offset((size.width - lightSize.width) / 2 + particleOffset.x.toPx(),
            (size.height - lightSize.height) / 2 + particleOffset.y.toPx())
        // Only the halo uses a gradient. The four particles must remain squares.
        val glowBrush = Brush.radialGradient(
            listOf(color.copy(alpha = 0.25f), Color.Transparent),
            center = Offset.Zero, radius = lightSize.maxDimension.coerceAtLeast(1f) * 0.8f,
        )
        onDrawWithContent {
            // The glow sits above the key fill, so it must yield to the clear/undo warning colour.
            val shown = visibility
            if (shown <= 0f) { drawContent(); return@onDrawWithContent }
            val t = elapsed.value
            val hold = held.value * shown
            if (t >= 1f && hold > 0f && particles.isNotEmpty()) drawRect(color, alpha = hold)
            if (t < 1f && particles.isNotEmpty()) {
                val remaining = keyGlowRemaining(t)
                // The first rendered DOWN frame is already lit in the reference.
                val fade = remaining * shown
                val travel = keyGlowTravel(t)
                drawRect(color, alpha = maxOf(fade * 0.75f, hold))
                val glowCenter = lightOrigin + Offset(lightSize.width * 0.5f, lightSize.height * 0.55f)
                translate(glowCenter.x, glowCenter.y) {
                    drawRect(glowBrush, topLeft = -glowCenter, size = size, alpha = fade,
                        blendMode = BlendMode.Screen)
                }
                particles.forEach { square ->
                    val side = lightSize.minDimension * square.side * keyGlowSquareSize(t)
                    val center = Offset(
                        lightOrigin.x + lightSize.width * square.x,
                        lightOrigin.y + lightSize.height * (square.y - travel * 0.06f),
                    )
                    rotate(square.angle + square.spin * travel, center) {
                        drawKeyGlowSquare(color, center, side, fade)
                    }
                }
            }
            drawContent() // Keep glyphs sharp, above the light.
        }
    }
}

/** Narrow feather around a square, preserving straight sides and four corners. */
internal fun DrawScope.drawKeyGlowSquare(color: Color, center: Offset, side: Float, alpha: Float) {
    for (layer in 2 downTo 0) {
        val edge = side * layer * 0.015f
        val extent = side + edge * 2f
        drawRoundRect(
            color = color,
            topLeft = center - Offset(extent / 2f, extent / 2f),
            size = Size(extent, extent),
            cornerRadius = CornerRadius(side * 0.09f + edge),
            alpha = alpha * if (layer == 0) 0.22f else 0.035f,
            blendMode = BlendMode.Screen,
        )
    }
}

/**
 * 强调色两侧 ±40° 的五个色相，统一成柔和高亮（Screen 混合下不刺眼，也不压过强提示色）。
 * 强调色接近灰（iOS 渐变/玻璃、黑白主题）时没有色相可跟，返回 null 用原来的七彩光，不再退成白光。
 */
internal fun themedGlowPalette(accent: Color): List<Color>? {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    if (hsv[1] < .15f) return null
    val saturation = hsv[1].coerceIn(.35f, .55f)
    return listOf(0f, -20f, 20f, -40f, 40f).map { shift ->
        Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + shift + 360f) % 360f, saturation, .96f)))
    }
}

private val glowPalette = listOf(
    Color(0xFF83E8AC), Color(0xFFFFD58F), Color(0xFF9C9AEC),
    Color(0xFFB19BCC), Color(0xFFEFACD2), Color(0xFFD9ED8B), Color(0xFF89E9D0),
)

// Measured cap widths from the user's 2026-09-24 recording, relative to rest.
// Cubic Hermite interpolation keeps velocity continuous between captured frames.
private val capTimes = floatArrayOf(0f, 17f, 33f, 50f, 67f, 83f, 100f, 117f, 133f, 150f, 167f, 183f, 200f, 217f, 235f)
private val capScales = floatArrayOf(1f, .952f, .852f, .752f, .686f, .667f, .743f, .867f, .967f, 1.029f, 1.052f, 1.052f, 1.033f, 1.014f, 1f)

internal fun keyGlowScale(t: Float): Float {
    val ms = t.coerceIn(0f, 1f) * 500f
    if (ms >= capTimes.last()) return 1f
    val i = (0 until capTimes.lastIndex).first { ms < capTimes[it + 1] }
    val span = capTimes[i + 1] - capTimes[i]
    val x = (ms - capTimes[i]) / span
    fun slope(j: Int): Float = if (j == 0 || j == capTimes.lastIndex) 0f else
        (capScales[j + 1] - capScales[j - 1]) / (capTimes[j + 1] - capTimes[j - 1])
    val x2 = x * x
    val x3 = x2 * x
    return (2f * x3 - 3f * x2 + 1f) * capScales[i] +
        (x3 - 2f * x2 + x) * span * slope(i) +
        (-2f * x3 + 3f * x2) * capScales[i + 1] +
        (x3 - x2) * span * slope(i + 1)
}

/** Keep visible square faces during the tail, instead of collapsing to dots. */
internal fun keyGlowSquareSize(t: Float): Float = 0.3f + 0.7f * (1f - t.coerceIn(0f, 1f))

/** Opacity has an independent smooth tail across the complete 500 ms. */
internal fun keyGlowRemaining(t: Float): Float {
    val remaining = 1f - t.coerceIn(0f, 1f)
    return remaining * remaining * (3f - 2f * remaining)
}

/** Rotation and outward travel start fast and decelerate continuously. */
internal fun keyGlowTravel(t: Float): Float {
    val remaining = 1f - t.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining * remaining
}
