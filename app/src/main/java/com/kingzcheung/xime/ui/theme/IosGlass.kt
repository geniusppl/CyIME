package com.kingzcheung.xime.ui.theme

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.runtimeShaderEffect
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * iOS 液态玻璃透镜（用户 11-09：照 FlClash 的底部浮动导航栏做玻璃和切换动效，按键和工具栏都要）。
 * 数值和做法移植自 chen08209/prism_ui（FlClash 的界面库，GPL-3.0，与 CyIME 同许可）：
 * - 着色器：shaders/liquid_glass.frag 的「凸透镜」分支 + LiquidGlassStyle.lens 参数（scale(lift) 随抬起加强）；
 * - 动效：navigation_dock.dart 的 FloatingNavigationBar —— _Spring（目标可每帧改、速度连续）、
 *   _liftSpring 抬起、_travelSpring 滑到目标、_jellySpring + _wobble（按速度拉长、刹车压扁）、
 *   _settleSpring 回弹、_fadeSpring 淡出、指尖光（_glowInSpring / _glowOutSpring）、甩动投射。
 * 按键（[GlassKeyPress]）和工具栏（[ToolbarLensMotion]）共用同一套弹簧和同一个着色器 [glassLens]。
 */

// ---------------------------------------------------------------- 弹簧

/** 弹簧参数：刚度 + 阻尼比（质量 1）。 */
internal class LensSpringSpec(val stiffness: Float, val ratio: Float) {
    val damping: Float = 2f * ratio * sqrt(stiffness)
}

/** Flutter 的 SpringDescription.withDurationAndBounce：刚度 (2π/d)²，阻尼比 1 - bounce。 */
private fun durationBounce(seconds: Float, bounce: Float = 0f): LensSpringSpec {
    val w = (2.0 * PI / seconds).toFloat()
    return LensSpringSpec(w * w, 1f - bounce)
}

/** prism_ui navigation_dock.dart 顶部那组弹簧，原样照搬。 */
internal object LensSprings {
    val track = durationBounce(.12f)
    val press = LensSpringSpec(311f, .593f)
    val follow = LensSpringSpec(1024f, .9f)
    val lift = durationBounce(.25f)
    val travel = durationBounce(.4f, .15f)
    val jelly = LensSpringSpec(100f, .4f)
    val settle = durationBounce(.5f, .32f)
    val fade = durationBounce(.2f)
    val glowIn = durationBounce(.1f)
    val glowOut = durationBounce(.5f)
}

// 果冻：速度 3 格/秒拉到头，拉长 .2；刹车 25 格/秒² 压扁 .15；高度变化是宽度的 1.4 倍。
private const val JELLY_SPEED = 3f
private const val JELLY_STRETCH = .2f
private const val JELLY_BRAKE = 25f
private const val JELLY_SQUASH = .15f
internal const val JELLY_ASPECT = 1.4f
private const val JELLY_REST = 3e-3f
// 抬起到 97.5% 才放下；离目标 .04 格以内算到了。
private const val LIFT_SNAP = .975f
private const val LIFT_RELEASE = .04f
// 指尖光：半径 28dp，松手时扩大 3 倍淡出；强度按 glow² 走。
// 原版峰值 .06 是给整条导航栏的，键和工具栏小、浅色主题上要看得出，提到 .16。
private const val GLOW_REACH_DP = 28f
private const val GLOW_GROWTH = 3f
private const val GLOW_PEAK = .16f
private const val GLOW_WASH = .35f

private fun saturate(value: Float): Float = 1f - 2f / (exp(2f * value) + 1f)

internal fun rubberBand(overshoot: Float, limit: Float): Float {
    if (limit <= 0f) return 0f
    val pull = 1f - 1f / (abs(overshoot) * .55f / limit + 1f)
    return limit * pull * sign(overshoot)
}

/**
 * prism_ui 的 _Spring：目标随时改，速度不断；能读出加速度（_wobble 判断刹车用）。
 * 值放在快照状态里，绘制/布局读它会跟着重画；推进由 [LensMotion] 的帧循环统一做。
 */
@Stable
internal class LensSpring(initial: Float, private val rest: Float = 1e-3f) {
    private val state = mutableFloatStateOf(initial)
    val value: Float get() = state.floatValue
    var velocity = 0f
        private set
    var target = initial
        private set
    var moving = false
        private set
    private var spec: LensSpringSpec = LensSprings.press

    val acceleration: Float
        get() = if (!moving) 0f else spec.stiffness * (target - value) - spec.damping * velocity

    fun springTo(to: Float, with: LensSpringSpec) {
        target = to
        spec = with
        moving = true
    }

    fun jumpTo(to: Float) {
        target = to
        velocity = 0f
        moving = false
        state.floatValue = to
    }

    /** 推进 [dt] 秒（拆成 2ms 小步，刚度最高的指尖光也稳）；返回是否还在动。 */
    fun step(dt: Float): Boolean {
        if (!moving) return false
        var x = value
        var v = velocity
        val steps = ceil(dt / .002f).toInt().coerceIn(1, 40)
        val h = dt / steps
        repeat(steps) {
            val a = spec.stiffness * (target - x) - spec.damping * v
            v += a * h
            x += v * h
        }
        if (abs(target - x) < rest && abs(v) < rest * sqrt(spec.stiffness)) {
            x = target
            v = 0f
            moving = false
        }
        velocity = v
        state.floatValue = x
        return moving
    }
}

/** 一组弹簧共用一个帧循环：有东西在动才跑，全停了就挂起等 [wake]。 */
internal abstract class LensMotion {
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    protected abstract val springs: List<LensSpring>

    /** 每帧弹簧推进之后调用（果冻、放下、落位都在这里判断）。 */
    protected open fun afterStep() {}

    /** 弹簧都停了但还在等事情发生（手指没抬、等抬满再放下）。 */
    protected open fun busy(): Boolean = false

    fun wake() {
        wakeup.trySend(Unit)
    }

    suspend fun run() {
        while (true) {
            wakeup.receive()
            var last = -1L
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (last < 0) 1f / 120f else ((now - last) / 1e9f).coerceIn(0f, 1f / 20f)
                last = now
                var any = false
                for (spring in springs) if (spring.step(dt)) any = true
                afterStep()
                if (!any && !busy() && springs.none { it.moving }) break
            }
        }
    }

    /** _wobble：按透镜速度拉长、按刹车压扁；[unit] 是一格多少像素（工具栏已是格数时传 1）。 */
    protected fun wobble(source: LensSpring, jelly: LensSpring, unit: Float) {
        val velocity = source.velocity / unit
        val braking = max(0f, -source.acceleration / unit * sign(velocity))
        val target = JELLY_STRETCH * saturate(abs(velocity) / JELLY_SPEED) -
            JELLY_SQUASH * saturate(braking / JELLY_BRAKE)
        if (abs(target - jelly.target) > 1e-4f) jelly.springTo(target, LensSprings.jelly)
    }
}

// ---------------------------------------------------------------- 按键

// 11-10 第五版：按键的果冻换成 Nekogram JellyGlassIndicator（geniusppl/Nekogram cb46ab29f，GPL；常数来自 prism_ui
// navigation_dock.dart，GPL-3.0）的同一套：按下 _liftSpring 抬起、透镜四周长 8dp（_lensGrowth）；手指拖出键外
// 才拉（_rubberBand，限位 min(宽, 高) × 7/32，拉时 _trackSpring、放回 _pressSpring），按拉动速度拉长、刹车压扁
// （_wobble，宽高互相 1.4 倍、不低于一半），拉伸 pull / 宽 × .5 并跟着平移（_PressTransform）；
// 松手要抬满（.975）且回到原位（.04 格）才 _liftSpring 放下（_dropLift）。
// 关掉「果冻特效」：只留抬起（玻璃加强、阴影），键不长大、不拉、不变形。
private const val PULL_LIMIT = 7f / 32f
private const val PULL_STRETCH = .5f

@Stable
internal class GlassKeyPress(private val density: Float) : LensMotion() {
    val lift = LensSpring(0f)
    val pullX = LensSpring(0f, rest = .05f)
    val pullY = LensSpring(0f, rest = .05f)
    val jellyX = LensSpring(0f, rest = JELLY_REST)
    val jellyY = LensSpring(0f, rest = JELLY_REST)
    override val springs = listOf(lift, pullX, pullY, jellyX, jellyY)
    private val raisedState = mutableStateOf(false)
    /** 抬起中（按下到放下）：用来把这个键画到邻键上面。 */
    val raised: Boolean get() = raisedState.value
    private val jellyState = mutableStateOf(true)
    /** 「果冻特效」开关（快照状态：设置里改了，图层立刻跟）。 */
    var jellyOn: Boolean
        get() = jellyState.value
        set(value) { if (jellyState.value != value) jellyState.value = value }
    private var down = false
    private var dropping = false
    private var unit = 1f

    // 按住不动时不跑帧（长按退格连发时省电），移动和松手都会 wake。
    override fun busy() = dropping

    override fun afterStep() {
        if (jellyOn) {
            wobble(pullX, jellyX, unit)
            wobble(pullY, jellyY, unit)
        }
        // _dropLift：抬满且回到原位才放下。
        if (dropping && !down && lift.value >= LIFT_SNAP &&
            abs(pullX.target - pullX.value) <= LIFT_RELEASE * unit && abs(pullY.target - pullY.value) <= LIFT_RELEASE * unit) {
            dropping = false
            lift.springTo(0f, LensSprings.lift)
        }
        if (!down && !dropping && !lift.moving && lift.value <= .002f && raisedState.value) raisedState.value = false
    }

    fun press(width: Float, height: Float) {
        down = true
        dropping = false
        unit = max(min(width, height), 1f)
        raisedState.value = true
        lift.springTo(1f, LensSprings.lift)
        wake()
    }

    /** 手指拖到键外的部分才拉（MainTabsLayout.updateJellyDrag 的 overshoot）。 */
    fun move(at: Offset, width: Float, height: Float) {
        if (!jellyOn) return
        val limit = min(width, height) * PULL_LIMIT
        val overX = at.x - at.x.coerceIn(0f, width)
        val overY = at.y - at.y.coerceIn(0f, height)
        pullX.springTo(rubberBand(overX, limit), if (overX == 0f) LensSprings.press else LensSprings.track)
        pullY.springTo(rubberBand(overY, limit), if (overY == 0f) LensSprings.press else LensSprings.track)
        wake()
    }

    fun release() {
        down = false
        dropping = lift.target > 0f
        pullX.springTo(0f, LensSprings.press)
        pullY.springTo(0f, LensSprings.press)
        wake()
    }

    /** 玻璃强度（FlClashLens 的 a）：静止 [NEKO_KEY_REST]，抬起到 1。 */
    fun glass(): Float = NEKO_KEY_REST + (1f - NEKO_KEY_REST) * lift.value.coerceIn(0f, 1f)

    val modifier: Modifier = Modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                // 只旁观，不消费：不影响点击、滑动和长按。
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                press(size.width.toFloat(), size.height.toFloat())
                val id = first.id
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == id } ?: break
                        if (!change.pressed) break
                        move(change.position, size.width.toFloat(), size.height.toFloat())
                    }
                } finally {
                    release()
                }
            }
        }
        .graphicsLayer {
            if (!jellyOn) {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                return@graphicsLayer
            }
            val w = max(size.width, 1f)
            val h = max(size.height, 1f)
            // _lensRect：抬起四周长 8dp，果冻横向拉长 (1 + j)、竖向收 max(.5, 1 - 1.4j)；竖向同理。
            val growth = NEKO_LENS_GROWTH_DP * density * 2f * lift.value
            val jx = jellyX.value
            val jy = jellyY.value
            val jellyW = (1f + jx) * max(.5f, 1f - jy * JELLY_ASPECT)
            val jellyH = (1f + jy) * max(.5f, 1f - jx * JELLY_ASPECT)
            val px = pullX.value
            val py = pullY.value
            // _PressTransform：拉过头时沿拉的方向伸长 pull / 宽 × .5 并跟着走。
            scaleX = (w + growth) / w * jellyW * (1f + abs(px) / w * PULL_STRETCH)
            scaleY = (h + growth) / h * jellyH * (1f + abs(py) / h * PULL_STRETCH)
            translationX = px
            translationY = py
        }
}

@Composable
internal fun rememberGlassKeyPress(): GlassKeyPress {
    val density = LocalDensity.current.density
    val press = remember(density) { GlassKeyPress(density) }
    press.jellyOn = LiquidGlass.jelly(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(press) { press.run() }
    return press
}

// ---------------------------------------------------------------- 工具栏透镜

// 11-10 第五版：透镜抬起时四周各长 8dp（_lensGrowth，Nekogram 同值），高过工具栏时竖向收回到工具栏高度
// （MainTabsLayout 的做法）；透镜下的图标随抬起放大 16%（_lensMagnify）；甩动往前看 100ms（_flingProjection）；两端可多拖 .35 格。
private const val LENS_GROWTH_DP = NEKO_LENS_GROWTH_DP
internal const val LENS_MAGNIFY = NEKO_LENS_MAGNIFY
private const val FLING_PROJECTION_S = .1f
private const val OVERDRAG = .35f
// 停在开启的按钮上时玻璃 .6 强度，抬起到 1。
private const val LENS_REST_STRENGTH = .6f

/**
 * 工具栏透镜（FloatingNavigationBar 的 _lens/_lift/_jelly）：位置以「格」为单位。
 * 按下：透镜 _travelSpring 滑到按下那格并 _liftSpring 抬起；拖过超过触摸阈值后 _followSpring 跟手；
 * 松手：甩动最多多走一格，_travelSpring 落位，抬满后放下；停下后有开启的按钮就停在它上面，没有就 _fadeSpring 淡出。
 */
@Stable
internal class ToolbarLensMotion(private val density: Float) : LensMotion() {
    val lens = LensSpring(0f)
    val lift = LensSpring(0f)
    val jelly = LensSpring(0f, rest = JELLY_REST)
    val show = LensSpring(0f)
    val glow = LensSpring(0f)
    val glowSpread = LensSpring(0f)
    override val springs = listOf(lens, lift, jelly, show, glow, glowSpread)
    private val glowAtState = mutableStateOf(Offset.Zero)
    /** 指尖在工具栏内容坐标里的位置。 */
    val glowAt: Offset get() = glowAtState.value
    /** 透镜左上角在内容坐标里（布局时写入）。 */
    var lensTopLeft = Offset.Zero
    /** 停靠目标：开启的按钮所在格，没有为 null；由工具栏每次组合更新。 */
    var restIndex: () -> Int? = { null }
    var count = 0
    private var pressedIndex: Int? = null
    private var downIndex = 0
    private var pressX = 0f
    private var dragging = false
    private var dropping = false
    private var down = false
    private var settled = true

    private val last: Int get() = max(0, count - 1)

    override fun busy() = dropping || (!settled && !down)

    private val jellyState = mutableStateOf(true)
    /** 「果冻特效」：关掉时滑块照样滑、抬起，但不长大、不拉长压扁。 */
    var jellyOn: Boolean
        get() = jellyState.value
        set(value) { if (jellyState.value != value) jellyState.value = value }

    override fun afterStep() {
        if (jellyOn) wobble(lens, jelly, 1f) else if (jelly.target != 0f) jelly.springTo(0f, LensSprings.jelly)
        dropLift()
        settleRest()
    }

    private fun dropLift() {
        if (!dropping || down || lift.value < LIFT_SNAP) return
        if (abs(lens.target - lens.value) > LIFT_RELEASE) return
        dropping = false
        lift.springTo(0f, LensSprings.lift)
    }

    /** 落定后：有开启的按钮就滑过去停着，没有就淡出。 */
    private fun settleRest() {
        if (down || dropping || lift.value > .02f || abs(lens.target - lens.value) > LIFT_RELEASE) return
        val rest = restIndex()?.takeIf { it in 0..last && count > 0 }
        if (rest == null) {
            if (show.target != 0f) show.springTo(0f, LensSprings.fade)
        } else {
            if (lens.target != rest.toFloat()) lens.springTo(rest.toFloat(), LensSprings.travel)
            if (show.target != 1f) show.springTo(1f, LensSprings.fade)
        }
        settled = true
    }

    /** 开启的按钮或格子顺序变了：空闲时重新落位。第一次直接摆好，不做动画。 */
    fun syncRest(initial: Boolean) {
        val rest = restIndex()?.takeIf { it in 0..last && count > 0 }
        if (initial && rest != null) {
            lens.jumpTo(rest.toFloat())
            show.jumpTo(1f)
            return
        }
        settled = false
        wake()
    }

    private fun positionAt(x: Float, pitch: Float): Float {
        val position = x / pitch - .5f
        return when {
            position < 0f -> rubberBand(position, OVERDRAG)
            position > last -> last + rubberBand(position - last, OVERDRAG)
            else -> position
        }
    }

    private fun indexAt(position: Float): Int = position.roundToInt().coerceIn(0, last)

    fun press(at: Offset, pitch: Float) {
        if (count <= 0 || pitch <= 0f) return
        down = true
        settled = false
        val index = indexAt(positionAt(at.x, pitch))
        pressedIndex = index
        downIndex = index
        pressX = at.x
        dragging = false
        dropping = false
        glowAtState.value = at
        glowSpread.jumpTo(0f)
        glow.springTo(1f, LensSprings.glowIn)
        // 透镜原本不在（淡出了）：从按下那格直接长出来，不从远处滑来。
        if (show.value < .05f) lens.jumpTo(index.toFloat())
        show.springTo(1f, LensSprings.fade)
        lift.springTo(1f, LensSprings.lift)
        lens.springTo(index.toFloat(), LensSprings.travel)
        wake()
    }

    /** 拖动：超过触摸阈值后透镜跟手。 */
    fun slide(at: Offset, pitch: Float, touchSlop: Float) {
        if (pressedIndex == null || pitch <= 0f) return
        glowAtState.value = at
        if (!dragging) {
            if (abs(at.x - pressX) < touchSlop) return
            dragging = true
        }
        val position = positionAt(at.x, pitch)
        lens.springTo(position, LensSprings.follow)
        pressedIndex = indexAt(position)
        wake()
    }

    val dragged: Boolean get() = dragging

    /**
     * 松手：[commit] 时透镜落在拖到的格（甩动最多多走一格），返回该格；否则轻点落在按下那格，
     * 拖了但不算数（横向滚动、被长按换位接管）就回停靠位。
     */
    fun release(commit: Boolean, velocityX: Float, pitch: Float): Int? {
        val pressed = pressedIndex
        down = false
        glow.springTo(0f, LensSprings.glowOut)
        glowSpread.springTo(1f, LensSprings.glowOut)
        if (pressed == null) {
            dropping = true
            wake()
            return null
        }
        var index = pressed
        if (commit && dragging && pitch > 0f) {
            val lead = velocityX / pitch * FLING_PROJECTION_S
            index = (lens.target + lead).roundToInt().coerceIn(pressed - 1, pressed + 1).coerceIn(0, last)
        }
        pressedIndex = null
        val wasDragging = dragging
        dragging = false
        dropping = true
        val target = when {
            commit || !wasDragging -> index
            else -> restIndex()?.takeIf { it in 0..last } ?: downIndex
        }
        lens.springTo(target.toFloat(), LensSprings.travel)
        wake()
        return if (commit) index else null
    }

    /** 放弃这次按压（长按换位开始）：透镜放下并淡出。 */
    fun cancel() {
        pressedIndex = null
        dragging = false
        down = false
        dropping = false
        lift.springTo(0f, LensSprings.lift)
        show.springTo(0f, LensSprings.fade)
        glow.springTo(0f, LensSprings.glowOut)
        settled = true
        wake()
    }

    /** 透镜在内容坐标里的框（_lensRect）：抬起长大，果冻拉长压扁，竖直居中。 */
    fun rect(pitch: Float, pillWidth: Float, pillHeight: Float, height: Float): Rect {
        val growth = if (jellyOn) LENS_GROWTH_DP * density * 2f * lift.value else 0f
        val j = if (jellyOn) jelly.value else 0f
        val w = (pillWidth + growth) * (1f + j)
        // JellyGlassIndicator.getRect：竖向不低于一半；MainTabsLayout：高过容器就收回容器高度。
        val h = ((pillHeight + growth) * max(.5f, 1f - j * JELLY_ASPECT)).coerceIn(1f, max(height, 1f))
        val cx = (lens.value + .5f) * pitch
        return Rect(cx - w / 2f, (height - h) / 2f, cx + w / 2f, (height + h) / 2f)
    }

    /** 玻璃强度：淡出时到 0；停靠 .6，抬起到 1。 */
    fun strength(): Float = show.value.coerceIn(0f, 1f) * (LENS_REST_STRENGTH + (1f - LENS_REST_STRENGTH) * lift.value)

    /** 第 [index] 格的图标放大倍数：透镜盖住多少放大多少。 */
    fun magnify(index: Int): Float {
        val cover = (1f - abs(lens.value - index)).coerceIn(0f, 1f)
        return 1f + LENS_MAGNIFY * lift.value * cover * show.value.coerceIn(0f, 1f)
    }

    fun glowStrength(): Float = GLOW_PEAK * glow.value * glow.value

    fun glowReach(): Float = GLOW_REACH_DP * density * (1f + GLOW_GROWTH * glowSpread.value)
}

@Composable
internal fun rememberToolbarLensMotion(): ToolbarLensMotion {
    val density = LocalDensity.current.density
    val motion = remember(density) { ToolbarLensMotion(density) }
    motion.jellyOn = LiquidGlass.jelly(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(motion) { motion.run() }
    return motion
}

/**
 * 工具栏透镜本体：铺满工具栏内容（随横向滚动一起走），透镜按 [ToolbarLensMotion.rect] 摆放。
 * Android 13 起画玻璃（折射键盘背景、色散、上下沿高光、外圈暗线、淡白底色、指尖光）；
 * 以下只画半透明胶囊 + 细亮边，动效相同。
 */
@Composable
internal fun ToolbarGlassLens(
    motion: ToolbarLensMotion, pitch: Float, pillWidth: Float, pillHeight: Float, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val dark = palette.surface.luminance() < .5f
    val origin = remember { mutableStateOf<Offset?>(null) }
    val placed = Modifier
        .layout { measurable, constraints ->
            val r = motion.rect(pitch, pillWidth, pillHeight, constraints.maxHeight.toFloat())
            val w = r.width.roundToInt().coerceAtLeast(1)
            val h = r.height.roundToInt().coerceAtLeast(1)
            val left = r.left.roundToInt()
            val top = r.top.roundToInt()
            motion.lensTopLeft = Offset(left.toFloat(), top.toFloat())
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, top) }
        }
        .onGloballyPositioned { origin.value = it.positionInRoot() }
    val source = rememberLensBackdropSource(dark, (LocalKeyboardPalette.current?.surface ?: palette.surface))
    val seeThrough = com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.frostedGlass.seeThrough
    // 11-10 第五版：照 Nekogram MainTabsLayout.drawJelly——静止时是强调色淡底的玻璃胶囊（NekoGlass.drawPill），
    // 抬起时淡出，换成 FlClash 透镜（FlClashLens：抬起阴影 + neko_flclash_glass 着色器按 a = lift 取样键盘背景）。
    // 透出模式下背景是系统模糊（读不到像素），底片用主题背景按 0x73 的不透明度（Nekogram 液态玻璃底栏的上限）。
    val accent = LocalKeyboardPalette.current?.accent ?: palette.accent
    val content = if (seeThrough) NEKO_SEE_THROUGH_CONTENT else 1f
    val surface = Modifier.drawWithCache {
        val cache = LensPaintCache()
        val r = size.height / 2f
        onDrawBehind {
            val shown = motion.show.value.coerceIn(0f, 1f)
            if (shown <= .001f) return@onDrawBehind
            val lift = motion.lift.value.coerceIn(0f, 1f)
            if (lift < 1f) drawNekoPill(r, accent, dark, 0f, shown * (1f - lift))
            if (lift > .004f) {
                drawNekoLiftShadow(r, lift * shown, dark)
                val (input, _) = cache.input(source, origin.value)
                if (!drawNekoGlass(input, r, lift, dark, content, shown, cache.paint)) {
                    drawNekoGlassFallback(input, r, dark, content * lift, shown, cache.paint)
                }
            }
        }
    }
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.Box(placed.then(surface))
    }
}

// ---------------------------------------------------------------- 着色器

/**
 * LiquidGlassStyle.lens 按 [strength] 缩放（prism_ui 的 scale(t)）：凸透镜往外读 4.3dp、斜面 5.5dp、色散 6.5dp、
 * 上下沿高光、外圈 0.67dp 暗线、两端略暗。
 * 和原版不同的地方（用户 11-09「真机上看不出变化」）：
 * - 只在边缘斜面那一圈画折射后的底片，中间透明，露出下面的键帽底色（[edge] 是这一圈的不透明度）；
 * - 浅色主题上白高光看不出，高光加宽加亮、暗线加深，斜面再加一点上亮下暗的厚度；
 * - [tint] 是预乘前的底色，铺在整片透镜下层。
 */
internal fun BackdropEffectScope.glassLens(
    radiusPx: Float, dark: Boolean, strength: Float, edge: Float,
    glowAt: Offset, glow: Float, glowReachPx: Float, tint: Color = Color.Transparent,
) {
    val t = strength.coerceIn(0f, 1.6f)
    val a = tint.alpha
    runtimeShaderEffect("CyImeGlassLens", GLASS_LENS_SHADER, "content") {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("radius", radiusPx)
        setFloatUniform("dp", density)
        // 深度、斜面宽（dp）、色散（dp）、斜面圈不透明度
        setFloatUniform("optics", 4.3f * t, 5.5f, 6.5f * t, edge.coerceIn(0f, 1f))
        // 饱和度、亮度、两端变暗
        setFloatUniform("tone", 1f, 1f + ((if (dark) 1.33f else 1f) - 1f) * t, .045f * t)
        // 高光强度、高光宽（dp）、暗线强度、斜面厚度
        setFloatUniform("rim", (if (dark) .5f else .9f) * t, 1.5f, (if (dark) .45f else .55f) * t,
            (if (dark) .08f else .12f) * t)
        setFloatUniform("tint", tint.red * a, tint.green * a, tint.blue * a, a)
        setFloatUniform("glow", glowAt.x, glowAt.y, glow, glowReachPx)
        setFloatUniform("wash", GLOW_WASH)
    }
}

private const val GLASS_LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float dp;
uniform float4 optics;
uniform float3 tone;
uniform float4 rim;
uniform float4 tint;
uniform float4 glow;
uniform float wash;

const float3 kLuma = float3(0.2126, 0.7152, 0.0722);

half4 main(float2 coord) {
    float2 p = coord + offset;
    float2 h = size * 0.5;
    float2 c = p - h;
    float halfMinor = min(h.x, h.y);
    float r = min(radius, halfMinor);
    float2 q = abs(c) - h + r;
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    if (d > 1.0) return half4(0.0);
    float inside = clamp(0.5 - d, 0.0, 1.0);
    float inset = max(-d, 0.0);
    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
    n *= float2(c.x < 0.0 ? -1.0 : 1.0, c.y < 0.0 ? -1.0 : 1.0);

    float bevel = max(min(optics.y * dp, halfMinor * 0.5), 0.001);
    float3 glass = float3(tint.rgb);
    float cover = tint.a;

    // 斜面一圈：凸透镜往外读，边上不偏、斜面内侧偏到最深、往中间渐平；中间透明。
    float band = (1.0 - smoothstep(bevel * 0.7, bevel * 1.6, inset)) * optics.w;
    if (band > 0.0) {
        float depth = min(optics.x * dp, halfMinor);
        float2 shift = n * depth * smoothstep(0.0, bevel, inset) * (1.0 - smoothstep(bevel, halfMinor, inset));
        float2 at = coord + shift;
        half4 base = content.eval(at);
        // 色散：沿法线的镜像方向把红蓝分开，离边 7dp 起收、10dp 合拢。
        float partReach = 10.0 * dp;
        float partHold = 7.0 * dp;
        if (optics.z > 0.0 && inset < partReach) {
            float2 part = n.yx * 0.5 * optics.z * dp
                * (0.6 + 0.4 * abs(n.y) * max(1.0 - inset / partHold, 0.0))
                * (1.0 - smoothstep(partHold, partReach, inset));
            half4 red = content.eval(at + part);
            half4 blue = content.eval(at - part);
            base = half4(red.r, base.g, blue.b, max(base.a, max(red.a, blue.a)));
        }
        float alpha = float(base.a);
        float3 color = float3(base.rgb) / max(alpha, 0.0001);
        color = mix(float3(dot(color, kLuma)), color, tone.x) * tone.y;
        color *= 1.0 - tone.z * abs(n.x) * exp(-inset / (3.0 * dp));
        float a = clamp(alpha * band, 0.0, 1.0);
        glass = glass * (1.0 - a) + color * a;
        cover = cover * (1.0 - a) + a;
    }

    // 厚度：斜面里上沿略亮、下沿略暗。
    float shade = rim.w * (1.0 - smoothstep(0.0, bevel, inset));
    float lit = clamp(shade * max(-n.y, 0.0), 0.0, 1.0);
    float dim = clamp(shade * max(n.y, 0.0) * 0.6, 0.0, 1.0);
    glass = glass * (1.0 - lit) + float3(lit);
    cover = cover * (1.0 - lit) + lit;
    glass = glass * (1.0 - dim);
    cover = cover * (1.0 - dim) + dim;

    // 暗线：最外 0.67dp，把下面的颜色压暗；底片是空的就画一道淡黑边。
    float kLine = 0.67 * dp;
    float line = clamp((kLine - inset) / 1.0 + 0.5, 0.0, 1.0);
    if (rim.z > 0.0 && line > 0.0) {
        half4 here = content.eval(coord);
        float ha = float(here.a);
        float3 under = ha > 0.001 ? float3(here.rgb) / ha : float3(0.0);
        float3 darker = max(under - 0.298, 0.22 * under);
        float la = clamp(rim.z * line * (ha > 0.001 ? 1.0 : 0.5), 0.0, 1.0);
        glass = glass * (1.0 - la) + darker * la;
        cover = cover * (1.0 - la) + la;
    }

    // 高光：只在上下沿（法线朝上下时），紧贴暗线内侧，像白光叠上去。
    float within = max(inset - kLine, 0.0);
    float facing = smoothstep(0.55, 0.95, abs(n.y));
    float sheen = clamp(1.0 - within / max(rim.y * dp, 0.001), 0.0, 1.0);
    float shine = min(rim.x * facing * sheen, 1.0);
    glass = glass * (1.0 - shine) + float3(shine);
    cover = cover * (1.0 - shine) + shine;

    // 指尖光：手指下面亮一团，松手时摊开淡出；一部分均匀洒满。
    if (glow.z > 0.0) {
        float2 toGlow = p - glow.xy;
        float reach = max(glow.w * glow.w, 1.0);
        float light = clamp(glow.z * mix(exp(-dot(toGlow, toGlow) / reach), 1.0, wash), 0.0, 1.0);
        glass = glass * (1.0 - light) + float3(light);
        cover = cover * (1.0 - light) + light;
    }

    cover = clamp(cover, 0.0, 1.0);
    return half4(half3(min(glass, float3(cover))), half(cover)) * half(inside);
}
"""
