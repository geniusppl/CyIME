package com.kingzcheung.xime.ui.theme

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

/*
 * Nekogram 顶部文件夹标签栏的玻璃和果冻，1:1 移植（用户 11-11：每个键、功能键和工具栏滑块都要和
 * 自己那版 Nekogram 顶部标签栏的玻璃材质、果冻效果一模一样）。
 * 来源：tw.nekomimi.nekogram.glass（Nekogram，GPL-3.0，与 CyIME 同许可）：
 * - [NekoJelly] = JellyGlassIndicator（弹簧、_wobble、_dropLift、_lensRect，常数照抄 prism_ui 的 NavigationDock）；
 * - [drawNekoLensGlass] / [drawNekoLensContent] / [drawNekoLensShadow] = FlClashLens 的两遍着色和 _lensShadows；
 * - [drawNekoRestGlass] = NekoGlass.Painter + setOpticsUniforms（KunBox 光学，roseforljh/KunBox，MIT）
 *   + BlurredBackgroundDrawable 的上下描边和阴影；[drawNekoPill] = NekoGlass.drawPill / drawSheen / drawRim；
 * - [nekoSolveSrcColor] = BlurredBackgroundProviderImpl.solveSrcColor，[nekoFillAlpha] = NekoGlass.fillAlpha（透明度固定 50%）。
 * 着色器逐字见 NekoGlassShaders.kt。需要 Android 13（RuntimeShader）；以下只画底色、描边和阴影。
 */

// ---------------------------------------------------------------- 颜色

/** BlurredBackgroundProviderImpl.solveSrcColor：求 src，使 src 按 [alpha] 盖在 [bg] 上正好是 [out]。 */
internal fun nekoSolveSrcColor(bg: Int, out: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0f) return 0
    if (a >= 1f) return out or (0xFF shl 24)
    val inv = 1f - a
    fun ch(o: Int, b: Int) = round((o - b * inv) / a).toInt().coerceIn(0, 255)
    val r = ch((out shr 16) and 0xFF, (bg shr 16) and 0xFF)
    val g = ch((out shr 8) and 0xFF, (bg shr 8) and 0xFF)
    val b = ch(out and 0xFF, bg and 0xFF)
    val a8 = round(a * 255f).toInt().coerceIn(0, 255)
    return (a8 shl 24) or (r shl 16) or (g shl 8) or b
}

/** NekoGlass.fillAlpha，「玻璃透明度」固定在默认的 50%：设计值原样返回。 */
internal fun nekoFillAlpha(base: Float): Float = base.coerceIn(0f, 1f)

/** NekoGlass.isDark：感知亮度低于 0.721 算深色。 */
internal fun nekoIsDark(color: Color): Boolean {
    val argb = color.copy(alpha = 1f).toArgb()
    val l = (0.2126f * ((argb shr 16) and 0xFF) + 0.7152f * ((argb shr 8) and 0xFF) + 0.0722f * (argb and 0xFF)) / 255f
    return l < 0.721f
}

/** 静止玻璃的底色：按 .85 从 [surface] 反解出 [target]，不透明度封顶 fillAlpha(0x73)。 */
internal fun nekoGlassTint(surface: Color, target: Color): Int {
    val solved = nekoSolveSrcColor(surface.copy(alpha = 1f).toArgb(), target.copy(alpha = 1f).toArgb(), .85f)
    val a = min(((solved ushr 24) and 0xFF) / 255f, nekoFillAlpha(0x73 / 255f))
    return (solved and 0xFFFFFF) or (round(a * 255f).toInt().coerceIn(0, 255) shl 24)
}

// ---------------------------------------------------------------- JellyGlassIndicator

/**
 * JellyGlassIndicator 的 Kotlin 版：x / 宽度跟随目标（TRAVEL / FOLLOW / SETTLE 三套弹簧），速度拉长、刹车压扁（_wobble），
 * 按下抬起（lift），松手等抬满且到位后才放下（_dropLift）。帧循环用 withFrameNanos（[run]），绘制里读 [subscribe] 跟着重画。
 * 工具栏另加一个淡入淡出 [show]（Nekogram 由 listView 的透明度做）。
 */
@Stable
internal class NekoJelly(private val density: Float) {
    companion object {
        const val JELLY_SPEED = 3f
        const val JELLY_STRETCH = 0.2f
        const val JELLY_BRAKE = 25f
        const val JELLY_SQUASH = 0.15f
        const val JELLY_ASPECT = 1.4f
        const val OVERDRAG = 0.35f
        const val LIFT_SNAP = 0.975f
        const val LIFT_RELEASE = 0.04f
        private const val FOLLOW_K = 1024f
        private const val FOLLOW_C = 2f * 0.9f * 32f
        private const val TRAVEL_K = 246.74f
        private const val TRAVEL_C = 2f * 0.85f * 15.708f
        private const val SETTLE_K = 157.91f
        private const val SETTLE_C = 2f * 0.68f * 12.566f
        private const val JELLY_K = 100f
        private const val JELLY_C = 2f * 0.4f * 10f
        private const val PRESS_K = 311f
        private const val PRESS_C = 2f * 0.593f * 17.635f
        private const val LIFT_K = 631.65f
        private const val LIFT_C = 2f * 25.133f
        private const val TRACK_K = 2741.6f
        private const val TRACK_C = 2f * 52.36f
        const val MODE_TRAVEL = 0
        const val MODE_FOLLOW = 1
        const val MODE_SETTLE = 2
        /** FilterTabsView / MainTabsLayout：跟手结束后 700ms 内用 SETTLE 弹簧。 */
        private const val SETTLE_WINDOW_NS = 700_000_000L

        /** prism_ui _rubberBand：limit * (1 - 1 / (|overshoot| * 0.55 / limit + 1))。 */
        fun rubberBand(overshoot: Float, limit: Float): Float {
            if (limit <= 0f) return 0f
            val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
            return limit * pull * sign(overshoot)
        }

        private fun saturate(v: Float): Float = (1.0 - 2.0 / (exp(2.0 * v) + 1.0)).toFloat()
    }

    var hasValue = false
        private set
    var x = 0f
        private set
    private var vx = 0f
    private var w = 0f
    private var vw = 0f
    var targetX = 0f
        private set
    private var targetW = 0f
    private var jellyValue = 0f
    private var vJelly = 0f
    private var liftValue = 0f
    private var vLift = 0f
    private var liftTarget = 0f
    private var pull = 0f
    private var vPull = 0f
    private var pullTarget = 0f
    private var swell = 0f
    private var vSwell = 0f
    private var swellTarget = 0f
    private var showValue = 0f
    private var vShow = 0f
    private var showTarget = 0f
    private var dropping = false
    private var mode = MODE_TRAVEL
    private var pitch = 64f * density
    private var lastFrameNanos = 0L
    private var lastAx = 0f
    private var settleUntil = 0L

    private val frame = mutableLongStateOf(0L)
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val raisedState = mutableStateOf(false)
    private val jellyState = mutableStateOf(true)

    /** 「果冻特效」：关掉时照样抬起、长大，但不拉长压扁（跟手由调用方关）。 */
    var jellyOn: Boolean
        get() = jellyState.value
        set(value) { if (jellyState.value != value) jellyState.value = value }

    /** 按着或还没放下：画到邻键上面。 */
    val raised: Boolean get() = raisedState.value

    val lift: Float get() = liftValue
    val show: Float get() = showValue
    val jelly: Float get() = if (jellyOn) jellyValue else 0f
    val pressed: Boolean get() = liftTarget > 0f

    /** 在绘制里调用：动画每帧都让读它的绘制重画。 */
    fun subscribe(): Long = frame.longValue

    private fun invalidate() {
        wakeup.trySend(Unit)
    }

    suspend fun run() {
        while (true) {
            wakeup.receive()
            var moving = true
            while (moving) {
                withFrameNanos { now ->
                    moving = step(now)
                    frame.longValue = frame.longValue + 1
                }
            }
        }
    }

    fun setPitch(pitch: Float) {
        if (pitch > 1f) this.pitch = pitch
    }

    fun setTarget(centerX: Float, width: Float, requested: Int) {
        var m = requested
        if (m == MODE_TRAVEL && settleUntil != 0L && System.nanoTime() < settleUntil) m = MODE_SETTLE
        if (!hasValue) {
            hasValue = true
            x = centerX; targetX = centerX
            w = width; targetW = width
            vx = 0f; vw = 0f
            lastFrameNanos = 0L
            mode = m
            frame.longValue = frame.longValue + 1
            return
        }
        if (mode != m || abs(targetX - centerX) > 0.01f || abs(targetW - width) > 0.01f) {
            mode = m
            targetX = centerX
            targetW = width
            invalidate()
        }
    }

    /** 跟手结束：之后 700ms 的目标都用 SETTLE 弹簧。 */
    fun endFollow() {
        settleUntil = System.nanoTime() + SETTLE_WINDOW_NS
    }

    fun jumpTo(centerX: Float, width: Float) {
        hasValue = true
        x = centerX; targetX = centerX
        w = width; targetW = width
        vx = 0f; vw = 0f
        jellyValue = 0f; vJelly = 0f
        frame.longValue = frame.longValue + 1
    }

    /** 绘制里用：空闲时把位置对到 ([centerX], [width])，不写状态（不会引起重画）。 */
    fun place(centerX: Float, width: Float) {
        hasValue = true
        x = centerX; targetX = centerX
        w = width; targetW = width
        vx = 0f; vw = 0f
        jellyValue = 0f; vJelly = 0f
    }

    fun setPull(pullPx: Float) {
        if (pullTarget != pullPx) {
            pullTarget = pullPx
            invalidate()
        }
    }

    fun setShow(target: Float) {
        if (showTarget != target) {
            showTarget = target
            invalidate()
        }
    }

    fun jumpShow(value: Float) {
        showTarget = value
        showValue = value
        vShow = 0f
        frame.longValue = frame.longValue + 1
    }

    /** prism_ui FloatingNavigationBar：按下抬起；松手时等抬满并到位后才放下（_dropLift）。 */
    fun setPressed(pressed: Boolean) {
        if (pressed) {
            dropping = false
            liftTarget = 1f
            swellTarget = 1f
        } else {
            dropping = liftTarget > 0f
            swellTarget = 0f
        }
        noteRaised()
        invalidate()
    }

    private fun noteRaised() {
        val r = liftTarget > 0f || liftValue > 0.004f
        if (raisedState.value != r) raisedState.value = r
    }

    /** 推进到 [now]；还在动返回 true。 */
    private fun step(now: Long): Boolean {
        var dt = if (lastFrameNanos == 0L) 1f / 60f else (now - lastFrameNanos) / 1e9f
        lastFrameNanos = now
        if (dt <= 0f) dt = 1f / 120f
        dt = min(dt, 0.05f)
        if (mode == MODE_SETTLE && (settleUntil == 0L || System.nanoTime() >= settleUntil)) {
            mode = MODE_TRAVEL
            settleUntil = 0L
        }
        val k: Float
        val c: Float
        when (mode) {
            MODE_FOLLOW -> { k = FOLLOW_K; c = FOLLOW_C }
            MODE_SETTLE -> { k = SETTLE_K; c = SETTLE_C }
            else -> { k = TRAVEL_K; c = TRAVEL_C }
        }
        if (dropping && liftValue >= LIFT_SNAP && abs(targetX - x) <= LIFT_RELEASE * pitch) {
            dropping = false
            liftTarget = 0f
        }
        val pullK = if (pullTarget == 0f) PRESS_K else TRACK_K
        val pullC = if (pullTarget == 0f) PRESS_C else TRACK_C
        val steps = max(1, ceil(dt / (1f / 240f)).toInt())
        val h = dt / steps
        for (i in 0 until steps) {
            val ax = k * (targetX - x) - c * vx
            vx += ax * h
            x += vx * h
            lastAx = ax

            val aw = k * (targetW - w) - c * vw
            vw += aw * h
            w += vw * h

            // prism_ui _wobble()：按速度拉长、刹车压扁
            val speed = abs(vx) / pitch
            val braking = max(0f, -lastAx * sign(vx)) / pitch
            val jellyTarget = JELLY_STRETCH * saturate(speed / JELLY_SPEED) - JELLY_SQUASH * saturate(braking / JELLY_BRAKE)
            val aj = JELLY_K * (jellyTarget - jellyValue) - JELLY_C * vJelly
            vJelly += aj * h
            jellyValue += vJelly * h

            val al = LIFT_K * (liftTarget - liftValue) - LIFT_C * vLift
            vLift += al * h
            liftValue += vLift * h

            val ap = pullK * (pullTarget - pull) - pullC * vPull
            vPull += ap * h
            pull += vPull * h

            val asw = PRESS_K * (swellTarget - swell) - PRESS_C * vSwell
            vSwell += asw * h
            swell += vSwell * h

            val ash = LIFT_K * (showTarget - showValue) - LIFT_C * vShow
            vShow += ash * h
            showValue += vShow * h
        }
        val moving = abs(targetX - x) > 0.3f || abs(vx) > 2f ||
            abs(targetW - w) > 0.3f || abs(vw) > 2f ||
            abs(jellyValue) > 0.003f || abs(vJelly) > 0.01f ||
            abs(liftTarget - liftValue) > 0.002f || abs(vLift) > 0.01f ||
            abs(pullTarget - pull) > 0.3f || abs(vPull) > 2f ||
            abs(swellTarget - swell) > 0.002f || abs(vSwell) > 0.01f ||
            abs(showTarget - showValue) > 0.002f || abs(vShow) > 0.01f ||
            dropping || mode == MODE_SETTLE
        if (!moving) {
            x = targetX; w = targetW
            vx = 0f; vw = 0f
            jellyValue = 0f; vJelly = 0f
            liftValue = liftTarget; vLift = 0f
            pull = pullTarget; vPull = 0f
            swell = swellTarget; vSwell = 0f
            showValue = showTarget; vShow = 0f
            lastFrameNanos = 0L
        }
        noteRaised()
        return moving
    }

    /**
     * prism_ui _lensRect：抬起时四周各长 [growthPx]，果冻宽 × (1 + s)、高 × max(.5, 1 − 1.4 s)，以 [centerY] 竖直居中。
     * [dx] 把结果平移（工具栏每格换到自己的坐标）。
     */
    fun rect(centerY: Float, height: Float, growthPx: Float, dx: Float = 0f): Rect {
        val growth = growthPx * 2f * liftValue
        val s = jelly
        val lensW = (max(0f, w) + growth) * (1f + s)
        val lensH = (height + growth) * max(0.5f, 1f - s * JELLY_ASPECT)
        val cx = x + dx
        return Rect(cx - lensW / 2f, centerY - lensH / 2f, cx + lensW / 2f, centerY + lensH / 2f)
    }
}

@Composable
internal fun rememberNekoJelly(): NekoJelly {
    val density = LocalDensity.current.density
    val jelly = remember(density) { NekoJelly(density) }
    jelly.jellyOn = LiquidGlass.jelly(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(jelly) { jelly.run() }
    return jelly
}

// ---------------------------------------------------------------- 着色器

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object NekoShaders {
    private fun compile(source: String, what: String): RuntimeShader? =
        try { RuntimeShader(source) } catch (e: Throwable) {
            android.util.Log.w("CyImeNekoGlass", "$what shader failed to compile", e)
            null
        }

    val rest: RuntimeShader? by lazy { compile(NEKO_LIQUID_GLASS_SHADER, "rest glass") }
    val lensGlass: RuntimeShader? by lazy { compile(NEKO_FLCLASH_GLASS_SHADER, "lens glass") }
    val lensContent: RuntimeShader? by lazy { compile(NEKO_FLCLASH_GLASS_SHADER, "lens content") }

    /** 键盘背景按不透明度取样（透出模式下背景本来就是半透明画的）。 */
    val fade: RuntimeShader? by lazy {
        compile("uniform shader s; uniform float a; half4 main(float2 p) { return s.eval(p) * half(a); }", "fade")
    }
}

/** 每个玻璃件一份画笔（只在主线程绘制里用）。 */
internal class NekoGlassPainter {
    val cache = LensPaintCache()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var rimShader: LinearGradient? = null
    private var rimH = -1f
    private var rimDark = false
    var restFailed = false
    var lensFailed = false
    private val rimMatrix = android.graphics.Matrix()
    private val lens = Path()

    /** 透镜形状（复用同一个 Path，按下时每帧不再新建）。 */
    fun lensPath(rect: Rect, radius: Float): Path {
        lens.reset()
        lens.addRoundRect(RoundRect(rect, CornerRadius(radius)))
        return lens
    }

    /** NekoGlass.rimShader：上亮、中间淡、下沿又亮一点。 */
    fun rimShader(top: Float, height: Float, dark: Boolean): Shader {
        var s = rimShader
        if (s == null || abs(rimH - height) > 0.5f || rimDark != dark) {
            val a1 = if (dark) 0x70 else 0xC0
            val a2 = if (dark) 0x14 else 0x30
            val a3 = if (dark) 0x38 else 0x70
            s = LinearGradient(0f, 0f, 0f, max(1f, height),
                intArrayOf(android.graphics.Color.argb(a1, 255, 255, 255), android.graphics.Color.argb(a2, 255, 255, 255),
                    android.graphics.Color.argb(a3, 255, 255, 255)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            rimShader = s
            rimH = height
            rimDark = dark
        }
        rimMatrix.setTranslate(0f, top)
        s.setLocalMatrix(rimMatrix)
        return s
    }
}

// ---------------------------------------------------------------- 静止玻璃

/** BlurredBackgroundDrawable 的阴影：2.667dp、下移 .85dp（浅 0x20000000、深 0x04FFFFFF），只画在玻璃外。 */
internal fun DrawScope.drawNekoRestShadow(w: Float, h: Float, radiusPx: Float, dark: Boolean, painter: NekoGlassPainter) {
    if (w < 1f || h < 1f) return
    val r = min(radiusPx, min(w, h) / 2f)
    val outline = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r))) }
    val p = painter.shadow
    p.color = android.graphics.Color.BLACK
    p.setShadowLayer(2.667f * density, 0f, 0.85f * density, if (dark) 0x04FFFFFF else 0x20000000)
    clipPath(outline, clipOp = ClipOp.Difference) {
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, w, h, r, r, p) }
    }
    p.clearShadowLayer()
}

/** BlurredBackgroundDrawable.drawStroke：0.4dp 上描边（浅 0x11000000、深 0x06FFFFFF）和下描边（0x20000000 / 0x11FFFFFF）。 */
internal fun DrawScope.drawNekoStrokes(w: Float, h: Float, radiusPx: Float, dark: Boolean, painter: NekoGlassPainter) {
    if (w < 1f || h < 1f) return
    val r = min(radiusPx, min(w, h) / 2f)
    val sw = 0.4f * density
    val half = sw / 2f
    val p = painter.stroke
    p.strokeWidth = sw
    p.shader = null
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        p.color = if (dark) 0x06FFFFFF else 0x11000000
        nc.save()
        if (nc.clipRect(-half, 0f, w + half, (r * 2f).coerceIn(0f, h))) nc.drawRoundRect(-half, half, w + half, h + half, r, r, p)
        nc.restore()
        p.color = if (dark) 0x11FFFFFF else 0x20000000
        nc.save()
        if (nc.clipRect(-half, (h - r * 2f).coerceIn(0f, h), w + half, h)) nc.drawRoundRect(-half, -half, w + half, h - half, r, r, p)
        nc.restore()
    }
}

/**
 * 静止玻璃，铺在 (0, 0, [w], [h])：NekoGlass.Painter 的 KunBox 光学（[input] 是对到本地坐标的键盘背景，按 [opacity] 取样），
 * 底色 [tint]（非预乘 ARGB，已封顶），再画上下描边。Android 12 及以下或着色器失败：只画底色和描边。
 * 宽高显式传入：在 GraphicsLayer 录制里 size 是图层大小。
 */
internal fun DrawScope.drawNekoRestGlass(
    w: Float, h: Float, input: Shader?, opacity: Float, radiusPx: Float, tint: Int, dark: Boolean, painter: NekoGlassPainter,
) {
    if (w < 1f || h < 1f) return
    val r = min(radiusPx, min(w, h) / 2f)
    val drawn = input != null && !painter.restFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        drawNekoRestShader(w, h, input, opacity, r, tint, dark, painter)
    if (!drawn) {
        val p = painter.fill
        p.shader = null
        p.color = tint
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, w, h, r, r, p) }
    }
    drawNekoStrokes(w, h, r, dark, painter)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawNekoRestShader(
    w: Float, h: Float, input: Shader, opacity: Float, r: Float, tint: Int, dark: Boolean, painter: NekoGlassPainter,
): Boolean {
    val s = NekoShaders.rest ?: return false
    try {
        val o = opacity.coerceIn(0f, 1f)
        val img: Shader = if (o >= .999f) input else {
            val fade = NekoShaders.fade ?: return false
            fade.setInputShader("s", input)
            fade.setFloatUniform("a", o)
            fade
        }
        s.setInputShader("img", img)
        s.setFloatUniform("resolution", w, h)
        s.setFloatUniform("center", w / 2f, h / 2f)
        s.setFloatUniform("size", w / 2f, h / 2f)
        s.setFloatUniform("radius", r, r, r, r)
        s.setFloatUniform("thickness", 11f * density)
        s.setFloatUniform("refract_intensity", 1f)
        s.setFloatUniform("refract_index", 1.5f)
        val a = nekoFillAlpha(((tint ushr 24) and 0xFF) / 255f)
        s.setFloatUniform("foreground_color_premultiplied",
            ((tint shr 16) and 0xFF) / 255f * a, ((tint shr 8) and 0xFF) / 255f * a, (tint and 0xFF) / 255f * a, a)
        setNekoOpticsUniforms(s, w, h, dialog = false, dark = dark)
    } catch (e: Throwable) {
        android.util.Log.w("CyImeNekoGlass", "rest glass uniforms failed", e)
        painter.restFailed = true
        return false
    }
    val p = painter.paint
    p.shader = s
    p.alpha = 255
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, w, h, r, r, p) }
    p.shader = null
    return true
}

/** NekoGlass.setOpticsUniforms：scale = clamp(短边 / 64dp, .25, 1)，控件透镜高 40dp、量 40dp，鲜艳度 1.5。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.setNekoOpticsUniforms(shader: RuntimeShader, width: Float, height: Float, dialog: Boolean, dark: Boolean) {
    val minSide = max(1f, min(width, height))
    val scale = (minSide / (64f * density)).coerceIn(0.25f, 1f)
    val lensHeight = min((if (dialog) 48f else 40f) * density * scale, minSide * 0.5f)
    val lensAmount = (if (dialog) 28f else 40f) * density * scale
    shader.setFloatUniform("lens_height", lensHeight)
    shader.setFloatUniform("lens_amount", lensAmount)
    shader.setFloatUniform("vibrancy", 1.5f)
    shader.setFloatUniform("highlight", if (dark) 0.30f else 0.55f)
    shader.setFloatUniform("rim_width", 1.33f * density)
}

/** 把键盘背景按 [opacity] 铺满 [region]（透镜玻璃那一遍的底片）。 */
internal fun DrawScope.drawNekoBackdrop(input: Shader?, opacity: Float, region: Rect, painter: NekoGlassPainter) {
    input ?: return
    val p = painter.paint
    p.shader = input
    p.alpha = (255 * opacity.coerceIn(0f, 1f)).toInt()
    drawIntoCanvas { it.nativeCanvas.drawRect(region.left, region.top, region.right, region.bottom, p) }
    p.shader = null
    p.alpha = 255
}

// ---------------------------------------------------------------- 静止胶囊（工具栏滑块）

/** NekoGlass.drawPill：强调色 .13 + .07 × lift，抬起时叠白，顶部光泽，镜面边。 */
internal fun DrawScope.drawNekoPill(
    rect: Rect, radius: Float, accent: Color, lift: Float, alpha: Float, dark: Boolean, painter: NekoGlassPainter,
) {
    if (alpha <= 0.001f || rect.width <= 0f || rect.height <= 0f) return
    val l = lift.coerceIn(0f, 1f)
    val a = alpha.coerceIn(0f, 1f)
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        val p = painter.fill
        p.shader = null
        p.color = accent.copy(alpha = (accent.alpha * (0.13f + 0.07f * l) * a).coerceIn(0f, 1f)).toArgb()
        nc.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, p)
        if (l > 0.01f) {
            p.color = android.graphics.Color.argb(((if (dark) 0x14 else 0x30) * l * a).toInt(), 255, 255, 255)
            nc.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, p)
        }
        // drawSheen
        val sa = ((if (dark) 0x16 else 0x2C) * a).toInt()
        if (sa > 0) {
            painter.sheen.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height * 0.6f,
                android.graphics.Color.argb(sa, 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP)
            nc.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, painter.sheen)
        }
        // drawRim
        val rp = painter.rim
        rp.strokeWidth = 1f * density
        rp.shader = painter.rimShader(rect.top, rect.height, dark)
        rp.alpha = (255 * (a * (0.75f + 0.25f * l)).coerceIn(0f, 1f)).toInt()
        val inset = rp.strokeWidth / 2f
        val r = max(0f, radius - inset)
        nc.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, r, r, rp)
    }
}

// ---------------------------------------------------------------- 抬起的透镜（FlClashLens）

internal const val NEKO_LENS_MAGNIFY = 0.16f
internal const val NEKO_LENS_GROWTH_DP = 8f

/** FlClashLens.drawShadow（_lensShadows）：模糊 5dp、下移 8dp，.055 × lift（深色 ×3），分 5 层手画。 */
internal fun DrawScope.drawNekoLensShadow(lens: Rect, lift: Float, dark: Boolean, painter: NekoGlassPainter) {
    val l = lift.coerceIn(0f, 1f)
    if (l <= 0f) return
    val alpha = 0.055f * (if (dark) 3f else 1f) * l
    val blur = 5f * density
    val steps = 5
    val p = painter.fill
    p.shader = null
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        for (i in 0 until steps) {
            val t = (i + 0.5f) / steps
            val grow = blur * (t - 0.5f) * 2f
            val top = lens.top - grow + 8f * density * l
            val bottom = lens.bottom + grow + 8f * density * l
            val r = max(0f, (bottom - top) / 2f)
            p.color = android.graphics.Color.argb(round(255 * alpha / steps * 1.6f).toInt().coerceIn(0, 255), 0, 0, 0)
            nc.drawRoundRect(lens.left - grow, top, lens.right + grow, bottom, r, r, p)
        }
    }
}

/** 录制区域（像素整数，透镜外扩 [margin]）。 */
private fun lensRegion(lens: Rect, margin: Float): IntArray {
    val l = floor(lens.left - margin).toInt()
    val t = floor(lens.top - margin).toInt()
    val r = ceil(lens.right + margin).toInt()
    val b = ceil(lens.bottom + margin).toInt()
    return intArrayOf(l, t, r, b)
}

/**
 * FlClashLens.drawGlass：[backdrop] 画在 [lens] 外扩 14dp 的图层里（参数是这块区域，本地坐标），
 * 套 LiquidGlassStyle.lens.scale(lift)（折射 −4.3、斜面 5.5、色散 6.5、高光 .6/.3、暗线 .42、两端 .045、边缘模糊 1.5）。
 * 返回 false：Android 12 及以下或着色器不可用，调用方退回 [drawNekoPill]。
 */
internal fun DrawScope.drawNekoLensGlass(
    layer: GraphicsLayer, lens: Rect, radius: Float, lift: Float, dark: Boolean, alpha: Float, painter: NekoGlassPainter,
    backdrop: DrawScope.(Rect) -> Unit,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || painter.lensFailed || lift <= 0f) return false
    return drawNekoLensGlass33(layer, lens, radius, lift, dark, alpha, painter, backdrop)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawNekoLensGlass33(
    layer: GraphicsLayer, lens: Rect, radius: Float, lift: Float, dark: Boolean, alpha: Float, painter: NekoGlassPainter,
    backdrop: DrawScope.(Rect) -> Unit,
): Boolean {
    val shader = NekoShaders.lensGlass ?: return false
    val region0 = lensRegion(lens, 14f * density)
    val l = region0[0]
    val t = region0[1]
    val r = region0[2]
    val b = region0[3]
    if (r - l <= 0 || b - t <= 0) return false
    return try {
        val region = Rect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        layer.record(size = IntSize(r - l, b - t)) {
            translate(-l.toFloat(), -t.toFloat()) { backdrop(region) }
        }
        val a = lift.coerceIn(0f, 1f)
        setNekoLensUniforms(shader, l, t, r, b, lens, radius, density,
            bevel = 5.5f, blur = 0f,
            refraction = -4.3f * a, saturation = 1f, brightness = 1f + ((if (dark) 1.33f else 1f) - 1f) * a,
            rim = (if (dark) 0.3f else 0.6f) * a, rimWidth = 1.1f, endShade = 0.045f * a,
            hairline = 0.42f * a, dispersion = 6.5f * a, rimBlur = 1.5f * a,
            rimTail = 0f, rimFocus = 0f, rimAdded = 0f, hairlineSide = 0.42f * a)
        layer.renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
        layer.alpha = alpha.coerceIn(0f, 1f)
        translate(l.toFloat(), t.toFloat()) { drawLayer(layer) }
        true
    } catch (e: Throwable) {
        android.util.Log.w("CyImeNekoGlass", "lens glass failed", e)
        painter.lensFailed = true
        false
    }
}

/**
 * FlClashLens.drawContent：[content] 按放大倍数（1 + .16 × lift）画透镜下的内容，染成 [tint]（SRC_IN），
 * 再套 LiquidGlassStyle.lensContent.scale(lift)（折射 14、斜面 11.2、色散 3.6、边缘模糊 2.5）。
 * 区域是 [lens] 外扩 2dp；[content] 里不要读 size（录制时是图层大小）。
 */
internal fun DrawScope.drawNekoLensContent(
    layer: GraphicsLayer, lens: Rect, radius: Float, lift: Float, dark: Boolean, tint: Color, alpha: Float,
    painter: NekoGlassPainter, content: DrawScope.(Float) -> Unit,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || painter.lensFailed || lift <= 0f) return false
    return drawNekoLensContent33(layer, lens, radius, lift, dark, tint, alpha, painter, content)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawNekoLensContent33(
    layer: GraphicsLayer, lens: Rect, radius: Float, lift: Float, dark: Boolean, tint: Color, alpha: Float,
    painter: NekoGlassPainter, content: DrawScope.(Float) -> Unit,
): Boolean {
    val shader = NekoShaders.lensContent ?: return false
    val region = lensRegion(lens, 2f * density)
    val l = region[0]
    val t = region[1]
    val r = region[2]
    val b = region[3]
    if (r - l <= 0 || b - t <= 0) return false
    return try {
        val a = lift.coerceIn(0f, 1f)
        val magnify = 1f + NEKO_LENS_MAGNIFY * a
        layer.record(size = IntSize(r - l, b - t)) {
            translate(-l.toFloat(), -t.toFloat()) { content(magnify) }
        }
        setNekoLensUniforms(shader, l, t, r, b, lens, radius, density,
            bevel = 11.2f, blur = 0f,
            refraction = 14f * a, saturation = 1f, brightness = 1f + ((if (dark) 1.33f else 1f) - 1f) * a,
            rim = 0f, rimWidth = 1.3f, endShade = 0f,
            hairline = 0f, dispersion = 3.6f * a, rimBlur = 2.5f * a,
            rimTail = 0f, rimFocus = 0f, rimAdded = 0f, hairlineSide = 0f)
        val tinted = RenderEffect.createColorFilterEffect(
            PorterDuffColorFilter(tint.copy(alpha = 1f).toArgb(), PorterDuff.Mode.SRC_IN))
        layer.renderEffect = RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "img"), tinted).asComposeRenderEffect()
        layer.alpha = alpha.coerceIn(0f, 1f)
        translate(l.toFloat(), t.toFloat()) { drawLayer(layer) }
        true
    } catch (e: Throwable) {
        android.util.Log.w("CyImeNekoGlass", "lens content failed", e)
        painter.lensFailed = true
        false
    }
}

/** 着色器失败或 Android 12 及以下：透镜里的内容按放大倍数画、用 SRC_IN 染色（不弯）。 */
internal fun DrawScope.drawNekoLensContentFallback(
    lens: Rect, radius: Float, lift: Float, tint: Color, alpha: Float, content: DrawScope.(Float) -> Unit,
) {
    val magnify = 1f + NEKO_LENS_MAGNIFY * lift.coerceIn(0f, 1f)
    val path = Path().apply { addRoundRect(RoundRect(lens, CornerRadius(radius))) }
    clipPath(path) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(lens, androidx.compose.ui.graphics.Paint().apply {
                colorFilter = ColorFilter.tint(tint.copy(alpha = 1f), BlendMode.SrcIn)
                this.alpha = alpha.coerceIn(0f, 1f)
            })
            content(magnify)
            canvas.restore()
        }
    }
}

/** FlClashLens.setUniforms：_LiquidGlassLayer 写的纹理尺寸和映射，再写 _look(style)；样式长度单位 dp。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun setNekoLensUniforms(
    s: RuntimeShader, l: Int, t: Int, r: Int, b: Int, lens: Rect, radiusPx: Float, d: Float,
    bevel: Float, blur: Float, refraction: Float, saturation: Float, brightness: Float,
    rim: Float, rimWidth: Float, endShade: Float, hairline: Float, dispersion: Float, rimBlur: Float,
    rimTail: Float, rimFocus: Float, rimAdded: Float, hairlineSide: Float,
) {
    val w = (r - l).toFloat()
    val h = (b - t).toFloat()
    val ox = (l - lens.left) / d
    val oy = (t - lens.top) / d
    s.setFloatUniform("uSize", w, h)
    s.setFloatUniform("uFrame", ox, oy, 1f / d, 1f / d)
    s.setFloatUniform("uSample", ox, oy, 1f / d, 1f / d)
    s.setFloatUniform("uClamp", 0.5f, 0.5f, w - 0.5f, h - 0.5f)
    s.setFloatUniform("uTexels", 1f / d, 0f)
    val rd = radiusPx / d
    s.setFloatUniform("uShape", lens.width / d, lens.height / d, bevel, blur)
    s.setFloatUniform("uCorners", rd, rd, rd, rd)
    s.setFloatUniform("uOptics", refraction, saturation, brightness)
    s.setFloatUniform("uTint", 0f, 0f, 0f, 0f)
    s.setFloatUniform("uLight", rim, rimWidth, endShade)
    s.setFloatUniform("uEdge", hairline, dispersion, rimBlur)
    s.setFloatUniform("uRim", rimTail, rimFocus, rimAdded, hairlineSide)
}

/** 圆角矩形路径（透镜形状）。 */
internal fun nekoLensPath(lens: Rect, radius: Float): Path =
    Path().apply { addRoundRect(RoundRect(lens, CornerRadius(radius))) }

/** 透镜在 [bounds] 里能不能碰到内容（放大后）。 */
internal fun nekoLensTouches(lens: Rect, w: Float, h: Float, magnify: Float): Boolean {
    val cx = w / 2f
    val cy = h / 2f
    val m = Rect(cx - cx * magnify, cy - cy * magnify, cx + cx * magnify, cy + cy * magnify)
    return lens.overlaps(m)
}
