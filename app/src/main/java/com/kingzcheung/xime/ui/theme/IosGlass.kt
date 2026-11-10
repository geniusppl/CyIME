package com.kingzcheung.xime.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * iOS 液态玻璃（用户 11-09：照 FlClash 的底部浮动导航栏做玻璃和切换动效，按键和工具栏都要）。
 * 11-10 第六版（用户：learn-28 照 Nekogram 改的果冻比 learn-26 还差，「不能直接照抄 FlClash 的代码吗」）：
 * 动效逐行翻译自 chen08209/prism_ui（FlClash 的界面库，GPL-3.0，与 CyIME 同许可）lib/navigation_dock.dart：
 * - [PrismSpring] = _Spring（Flutter SpringSimulation 的解析解，改目标时从当前值和当前速度重新开始，
 *   Ticker 第一帧经过时间为 0），[PrismSpringDescription] = SpringDescription.withDampingRatio / withDurationAndBounce；
 * - 按键 [GlassKeyPress] = ElasticPress（_swell / _pullToward / _release / _settle / _PressTransform）
 *   + 玻璃按钮 LiquidGlass(pressed:) 的按压光（120ms 亮起、360ms 摊开淡出，easeOut）；
 *   「果冻特效」开着时再加导航栏透镜的 _wobble（_jellySpring，按拉动速度拉长、刹车压扁，_lensRect 的 1.4 倍高宽比）；
 * - 工具栏滑块 [ToolbarLensMotion] = FloatingNavigationBar 的 _lens / _lift / _jelly（_press / _slide / _fling /
 *   _release / _dropLift / _wobble / _lensRect），透镜画法 = _Lens 底板 + _GlassLens（lens 样式 × lift、_lensShadows），
 *   透镜下的图标 = _LensTint（裁成透镜形状、按 _lensMagnify 放大、染成主色）。
 * 玻璃材质见 PrismGlass.kt（shaders/liquid_glass.frag 的逐行翻译）。
 */

// ---------------------------------------------------------------- 弹簧（_Spring）

/** Flutter 的 SpringDescription。 */
internal class PrismSpringDescription(val mass: Double, val stiffness: Double, val damping: Double) {
    companion object {
        fun withDampingRatio(mass: Double = 1.0, stiffness: Double, ratio: Double = 1.0) =
            PrismSpringDescription(mass, stiffness, ratio * 2.0 * sqrt(mass * stiffness))

        /** SpringDescription.withDurationAndBounce（Flutter 源码原样）。 */
        fun withDurationAndBounce(durationMs: Int = 500, bounce: Double = 0.0): PrismSpringDescription {
            val durationInSeconds = durationMs / 1000.0
            val mass = 1.0
            val stiffness = (4 * PI * PI * mass) / durationInSeconds.pow(2)
            val dampingRatio = if (bounce > 0) 1.0 - bounce else 1 / (bounce + 1)
            val damping = dampingRatio * 2.0 * sqrt(mass * stiffness)
            return PrismSpringDescription(mass, stiffness, damping)
        }
    }
}

/** navigation_dock.dart 顶部那组弹簧，原样照搬。 */
internal object PrismSprings {
    val track = PrismSpringDescription.withDurationAndBounce(120)
    val press = PrismSpringDescription.withDampingRatio(mass = 1.0, stiffness = 311.0, ratio = .593)
    val follow = PrismSpringDescription.withDampingRatio(mass = 1.0, stiffness = 1024.0, ratio = .9)
    val lift = PrismSpringDescription.withDurationAndBounce(250)
    val travel = PrismSpringDescription.withDurationAndBounce(400, .15)
    val jelly = PrismSpringDescription.withDampingRatio(mass = 1.0, stiffness = 100.0, ratio = .4)
    val settle = PrismSpringDescription.withDurationAndBounce(500, .32)
    val fade = PrismSpringDescription.withDurationAndBounce(200)
}

private const val PRESS_GROWTH = 1.0 / 8
private const val MAX_PRESS_GROWTH_DP = 16.0
private const val LENS_GROWTH_DP = 8f
internal const val LENS_MAGNIFY = .16f
private const val LIFT_SNAP = .975
private const val LIFT_RELEASE = .04
private const val JELLY_SPEED = 3.0
private const val JELLY_STRETCH = .2
private const val JELLY_BRAKE = 25.0
private const val JELLY_SQUASH = .15
internal const val JELLY_ASPECT = 1.4f
// A fifth of a dp at each edge of the widest mobile lens, 134 dp lifted.
private const val JELLY_REST = 3e-3
private const val OVERDRAG = .35
private const val PULL_LIMIT = 7.0 / 32
private const val PULL_STRETCH = .5
private const val FLING_PROJECTION_S = .1
private const val BAR_PADDING_DP = 4f
// Flutter 的 kTouchSlop（逻辑像素）。
private const val TOUCH_SLOP_DP = 18f
// GlassLensPaint 的旧透镜着色器（glassLens）仍引用。
private const val GLOW_WASH = .35f

private fun rubberBand(overshoot: Double, limit: Double): Double {
    val pull = 1 - 1 / (abs(overshoot) * .55 / limit + 1)
    return limit * pull * sign(overshoot)
}

private fun saturate(value: Double): Double = 1 - 2 / (Math.exp(2 * value) + 1)

/** Dart 的 double.round()：四舍五入，.5 远离零。 */
private fun dartRound(x: Double): Int = if (x >= 0) floor(x + .5).toInt() else -floor(-x + .5).toInt()

private fun nearZero(a: Double, epsilon: Double): Boolean = (a > -epsilon && a < epsilon) || a == 0.0

/** Flutter 的 _SpringSolution（临界 / 过阻尼 / 欠阻尼三种解析解）。 */
private class SpringSolution(spring: PrismSpringDescription, distance: Double, velocity: Double) {
    private val kind: Int
    private val r: Double
    private val r2: Double
    private val w: Double
    private val c1: Double
    private val c2: Double

    init {
        val cmk = spring.damping * spring.damping - 4 * spring.mass * spring.stiffness
        when {
            cmk > 0.0 -> {
                kind = 1
                val ra = (-spring.damping - sqrt(cmk)) / (2.0 * spring.mass)
                val rb = (-spring.damping + sqrt(cmk)) / (2.0 * spring.mass)
                r = ra
                r2 = rb
                w = 0.0
                c2 = (velocity - ra * distance) / (rb - ra)
                c1 = distance - c2
            }
            cmk < 0.0 -> {
                kind = 2
                w = sqrt(4.0 * spring.mass * spring.stiffness - spring.damping * spring.damping) / (2.0 * spring.mass)
                r = -(spring.damping / 2.0 / spring.mass)
                r2 = 0.0
                c1 = distance
                c2 = (velocity - r * distance) / w
            }
            else -> {
                kind = 0
                r = -spring.damping / (2.0 * spring.mass)
                r2 = 0.0
                w = 0.0
                c1 = distance
                c2 = velocity - (r * distance)
            }
        }
    }

    fun x(time: Double): Double = when (kind) {
        1 -> c1 * Math.pow(Math.E, r * time) + c2 * Math.pow(Math.E, r2 * time)
        2 -> Math.pow(Math.E, r * time) * (c1 * Math.cos(w * time) + c2 * Math.sin(w * time))
        else -> (c1 + c2 * time) * Math.pow(Math.E, r * time)
    }

    fun dx(time: Double): Double = when (kind) {
        1 -> c1 * r * Math.pow(Math.E, r * time) + c2 * r2 * Math.pow(Math.E, r2 * time)
        2 -> {
            val power = Math.pow(Math.E, r * time)
            val cosine = Math.cos(w * time)
            val sine = Math.sin(w * time)
            power * (c2 * w * cosine - c1 * w * sine) + r * power * (c2 * sine + c1 * cosine)
        }
        else -> {
            val power = Math.pow(Math.E, r * time)
            r * (c1 + c2 * time) * power + c2 * power
        }
    }
}

/** Flutter 的 SpringSimulation（snapToEnd false）。 */
private class SpringSimulation(
    spring: PrismSpringDescription, start: Double, private val end: Double, velocity: Double,
    private val toleranceDistance: Double, private val toleranceVelocity: Double,
) {
    private val solution = SpringSolution(spring, start - end, velocity)
    fun x(time: Double): Double = end + solution.x(time)
    fun dx(time: Double): Double = solution.dx(time)
    fun isDone(time: Double): Boolean =
        nearZero(solution.x(time), toleranceDistance) && nearZero(solution.dx(time), toleranceVelocity)
}

/**
 * prism_ui 的 _Spring：一个可以被手指每帧改目标的弹簧值。
 * 每个弹簧有自己的「Ticker」：从停着到开始的那一帧经过时间为 0（原版注释说的那一帧），之后按帧时间算解析解；
 * 改目标时若在动，就从上一帧的值和速度、以上一帧时间为起点重新开始。[listeners] 每帧（含停下那帧）和 jumpTo 时调用。
 */
@Stable
internal class PrismSpring(private val clock: PrismMotion, initial: Double, val rest: Double = 1e-3) {
    private val state = mutableFloatStateOf(initial.toFloat())
    private var current = initial
    private var goal = 0.0
    private var simulation: SpringSimulation? = null
    private var spring: PrismSpringDescription? = null
    private var now = 0.0
    private var start = 0.0
    private var tickerStart = -1L
    private var startedInFrame = -1L
    var active = false
        private set
    val listeners = ArrayList<() -> Unit>()

    /** 当前值（读快照状态：在绘制/布局里读会跟着重画）。 */
    val value: Double get() { state.floatValue; return current }

    val target: Double get() = if (simulation == null) current else goal

    val velocity: Double get() = simulation?.dx(now - start) ?: 0.0

    val acceleration: Double
        get() {
            val s = spring
            if (simulation == null || s == null) return 0.0
            return (s.stiffness * (goal - current) - s.damping * velocity) / s.mass
        }

    fun springTo(target: Double, with: PrismSpringDescription) {
        simulation = SpringSimulation(with, current, target, velocity,
            toleranceDistance = rest, toleranceVelocity = rest * sqrt(with.stiffness / with.mass))
        spring = with
        goal = target
        if (active) {
            start = now
            return
        }
        now = 0.0
        start = 0.0
        active = true
        tickerStart = -1L
        // 在帧回调里启动的 Ticker 从下一帧开始（Flutter 的帧回调表在一帧里只取一次）。
        startedInFrame = if (clock.inFrame) clock.frameId else -1L
        clock.wake()
    }

    fun jumpTo(value: Double) {
        simulation = null
        active = false
        current = value
        notifyListeners()
    }

    internal fun tick(frameNanos: Long, frameId: Long) {
        if (!active || startedInFrame == frameId) return
        if (tickerStart < 0) tickerStart = frameNanos
        // Duration.inMicroseconds / microsecondsPerSecond
        now = ((frameNanos - tickerStart) / 1000L) / 1e6
        val sim = simulation ?: run { active = false; return }
        val time = now - start
        if (sim.isDone(time)) {
            current = goal
            simulation = null
            active = false
        } else {
            current = sim.x(time)
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        state.floatValue = current.toFloat()
        for (listener in listeners.toList()) listener()
    }
}

/** 一组 [PrismSpring] 共用一个帧循环（相当于同一个 TickerProvider）：有东西在动才跑。 */
internal abstract class PrismMotion {
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    protected abstract val springs: List<PrismSpring>
    var inFrame = false
        private set
    var frameId = 0L
        private set

    /** 弹簧以外每帧要推进的东西（按压光的补间）。 */
    protected open fun onFrame(frameNanos: Long) {}

    protected open fun busy(): Boolean = false

    fun wake() {
        wakeup.trySend(Unit)
    }

    suspend fun run() {
        while (true) {
            wakeup.receive()
            while (springs.any { it.active } || busy()) {
                withFrameNanos { nanos ->
                    frameId++
                    inFrame = true
                    try {
                        for (spring in springs) spring.tick(nanos, frameId)
                        onFrame(nanos)
                    } finally {
                        inFrame = false
                    }
                }
            }
        }
    }

    /** _wobble：按 [source] 的速度拉长、按刹车压扁；[unit] 把 [source] 换成「格」（导航栏是一个目的地的宽度）。 */
    protected fun wobble(source: PrismSpring, jelly: PrismSpring, unit: Double) {
        val velocity = source.velocity / unit
        val braking = max(0.0, -source.acceleration / unit * sign(velocity))
        val target = JELLY_STRETCH * saturate(abs(velocity) / JELLY_SPEED) -
            JELLY_SQUASH * saturate(braking / JELLY_BRAKE)
        if (target != jelly.target) jelly.springTo(target, PrismSprings.jelly)
    }
}

// ---------------------------------------------------------------- 按键（ElasticPress + LiquidGlass(pressed)）

// Flutter Curves.easeOut。
private val EASE_OUT = CubicBezierEasing(0f, 0f, .58f, 1f)
private const val GLOW_IN_MS = 120L
private const val GLOW_OUT_MS = 360L
private const val GLOW_SPREAD = 3f

/**
 * 按键 = prism_ui 的 ElasticPress 包着一片 LiquidGlass.regular 玻璃按钮：
 * - 按下 _swell：lift 用 _pressSpring 升到 1；
 * - 手指移动 _pullToward：pull = 手指 − 按下点（pullPastEdges 默认 false），按短边 × 7/32 橡皮筋限位，_trackSpring 跟；
 * - 松手 _release：pull 用 _pressSpring 回 0；_settle：lift 升到 .975 以上才用 _pressSpring 落回（轻点也先抬满）；
 * - _PressTransform：以键中心缩放 1 + lift × min(1/4, 16dp / 长边)，再沿拉的方向伸长 |pull| / 边长 × .5 并平移 pull；
 * - 玻璃按钮的按压光：pressed 时 120ms easeOut 升到 1，松开 360ms 落回，光心在手指处，半径 高/√2 ×（1 + 3 ×（1 − press）淡出时），强度 press × .16。
 * 「果冻特效」开着时，pull 弹簧每帧再走导航栏透镜的 _wobble（横向以键宽、竖向以键高为一格），按 _lensRect 的
 * (1 + s) / (1 − 1.4 s) 伸缩；关掉时只留鼓起和按压光，不拉、不变形。
 */
@Stable
internal class GlassKeyPress(private val density: Float) : PrismMotion() {
    val lift = PrismSpring(this, 0.0)
    val pullX = PrismSpring(this, 0.0)
    val pullY = PrismSpring(this, 0.0)
    val jellyX = PrismSpring(this, 0.0, rest = JELLY_REST)
    val jellyY = PrismSpring(this, 0.0, rest = JELLY_REST)
    override val springs = listOf(lift, pullX, pullY, jellyX, jellyY)
    private val raisedState = mutableStateOf(false)
    /** 鼓起或拉动中：把这个键画到邻键上面。 */
    val raised: Boolean get() = raisedState.value
    private val jellyState = mutableStateOf(true)
    /** 「果冻特效」开关（快照状态：设置里改了，图层立刻跟）。 */
    var jellyOn: Boolean
        get() = jellyState.value
        set(value) { if (jellyState.value != value) jellyState.value = value }
    private var origin = Offset.Zero
    private var releasing = false
    private var panelWidth = 1.0
    private var panelHeight = 1.0

    // LiquidGlass(pressed:) 的 TweenAnimationBuilder。
    private val pressState = mutableFloatStateOf(0f)
    private var pressedNow = false
    private var tweenFrom = 0f
    private var tweenTo = 0f
    private var tweenStart = -1L
    private var tweenDurationNs = 0L
    private var tweening = false
    private val touchState = mutableStateOf<Offset?>(null)

    init {
        lift.listeners += ::settle
        lift.listeners += ::noteRaised
        pullX.listeners += {
            if (jellyOn) wobble(pullX, jellyX, panelWidth)
            noteRaised()
        }
        pullY.listeners += {
            if (jellyOn) wobble(pullY, jellyY, panelHeight)
            noteRaised()
        }
    }

    override fun busy() = tweening

    override fun onFrame(frameNanos: Long) {
        if (!tweening) return
        if (tweenStart < 0) tweenStart = frameNanos
        val t = ((frameNanos - tweenStart).toDouble() / tweenDurationNs).coerceIn(0.0, 1.0).toFloat()
        val v = tweenFrom + (tweenTo - tweenFrom) * EASE_OUT.transform(t)
        pressState.floatValue = if (t >= 1f) tweenTo else v
        if (t >= 1f) tweening = false
        if (pressState.floatValue == 0f) touchState.value = null
    }

    private fun setPressed(pressed: Boolean) {
        if (pressed == pressedNow) return
        pressedNow = pressed
        tweenFrom = pressState.floatValue
        tweenTo = if (pressed) 1f else 0f
        tweenDurationNs = (if (pressed) GLOW_IN_MS else GLOW_OUT_MS) * 1_000_000L
        tweenStart = -1L
        tweening = true
        wake()
    }

    private fun noteRaised() {
        val raised = lift.value > 0 || pullX.value != 0.0 || pullY.value != 0.0
        if (raisedState.value != raised) raisedState.value = raised
    }

    /** _swell。 */
    private fun swell(at: Offset) {
        origin = at
        releasing = false
        lift.springTo(1.0, PrismSprings.press)
    }

    fun press(at: Offset, width: Float, height: Float) {
        panelWidth = max(width.toDouble(), 1.0)
        panelHeight = max(height.toDouble(), 1.0)
        touchState.value = at
        setPressed(true)
        swell(at)
        noteRaised()
    }

    /** _pullToward。 */
    fun move(at: Offset, width: Float, height: Float) {
        touchState.value = at
        if (!jellyOn) return
        val limit = min(width, height) * PULL_LIMIT
        if (limit <= 0) return
        pullX.springTo(rubberBand((at.x - origin.x).toDouble(), limit), PrismSprings.track)
        pullY.springTo(rubberBand((at.y - origin.y).toDouble(), limit), PrismSprings.track)
    }

    /** _release。 */
    fun release() {
        pullX.springTo(0.0, PrismSprings.press)
        pullY.springTo(0.0, PrismSprings.press)
        releasing = true
        setPressed(false)
        settle()
    }

    /** _settle：轻点在鼓满之前就松开，先鼓满再落回（UIKit 抬起标签栏透镜的做法）。 */
    private fun settle() {
        if (!releasing || lift.value < LIFT_SNAP) return
        releasing = false
        lift.springTo(0.0, PrismSprings.press)
    }

    /** 当前变换：横竖缩放和平移（像素，以键中心为原点）。 */
    fun transform(width: Float, height: Float): FloatArray {
        val w = max(width, 1f)
        val h = max(height, 1f)
        val longest = max(w, h) / density
        val growth = min(PRESS_GROWTH * 2, MAX_PRESS_GROWTH_DP / longest)
        val swell = 1 + lift.value * growth
        if (!jellyOn) return floatArrayOf(swell.toFloat(), swell.toFloat(), 0f, 0f)
        val px = pullX.value
        val py = pullY.value
        val jx = jellyX.value
        val jy = jellyY.value
        val sx = swell * (1 + abs(px) / w * PULL_STRETCH) * (1 + jx) * (1 - jy * JELLY_ASPECT)
        val sy = swell * (1 + abs(py) / h * PULL_STRETCH) * (1 + jy) * (1 - jx * JELLY_ASPECT)
        return floatArrayOf(sx.toFloat(), sy.toFloat(), px.toFloat(), py.toFloat())
    }

    /** 玻璃按钮的按压光（_RenderLiquidGlass.paint 的 LiquidGlassGlow），坐标 dp。 */
    fun glow(width: Float, height: Float): PrismGlow {
        val p = pressState.floatValue
        val center = touchState.value ?: Offset(width / 2f, height / 2f)
        val fading = !pressedNow
        val reach = (height / density) / sqrt(2f) * (1f + if (fading) GLOW_SPREAD * (1f - p) else 0f)
        return PrismGlow(center / density, reach, p * .16f)
    }

    val modifier: Modifier = Modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                // 只旁观，不消费：不影响点击、滑动和长按。
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                press(first.position, size.width.toFloat(), size.height.toFloat())
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
            val t = transform(size.width, size.height)
            scaleX = t[0]
            scaleY = t[1]
            translationX = t[2]
            translationY = t[3]
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

/**
 * 把底片的局部矩阵改成「按变换前的屏幕位置取样」：原版玻璃取样的是它在屏幕上实际盖住的背景，
 * 鼓起、拉动时背景不跟着放大。[t] 是 [GlassKeyPress.transform] 的结果。
 */
internal fun compensateBackdrop(input: android.graphics.Shader, width: Float, height: Float, t: FloatArray) {
    if (t[0] == 1f && t[1] == 1f && t[2] == 0f && t[3] == 0f) return
    val cx = width / 2f
    val cy = height / 2f
    val m = android.graphics.Matrix()
    input.getLocalMatrix(m)
    val inverse = android.graphics.Matrix()
    inverse.setTranslate(-(cx + t[2]), -(cy + t[3]))
    inverse.postScale(1f / t[0], 1f / t[1])
    inverse.postTranslate(cx, cy)
    m.postConcat(inverse)
    input.setLocalMatrix(m)
}

// ---------------------------------------------------------------- 工具栏透镜（FloatingNavigationBar）

/**
 * 工具栏透镜 = FloatingNavigationBar 的 _lens / _lift / _jelly，位置以「格」为单位：
 * 按下 _press：_liftSpring 抬起、_travelSpring 滑到按下那格；_slide：横向超过 kTouchSlop（18dp）后 _followSpring 跟手，
 * 两端多拖按 _rubberBand(.35)；松手 _fling（往前看 100ms，最多多走一格）+ _release：_travelSpring 落位；
 * _dropLift：抬满（.975）且离目标 .04 格以内才 _liftSpring 放下；_wobble：按透镜速度拉长、刹车压扁。
 * 工具栏自己的部分（导航栏没有）：没有开启的按钮时透镜 _fadeSpring 淡出（[show]），开启的按钮变了空闲时滑过去；长按换位时 [cancel]。
 */
@Stable
internal class ToolbarLensMotion(private val density: Float) : PrismMotion() {
    val lens = PrismSpring(this, 0.0)
    val lift = PrismSpring(this, 0.0)
    val jelly = PrismSpring(this, 0.0, rest = JELLY_REST)
    val show = PrismSpring(this, 0.0)
    override val springs = listOf(lens, lift, jelly, show)
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

    private val jellyState = mutableStateOf(true)
    /** 「果冻特效」：关掉时透镜照样滑、抬起、长大，但不拉长压扁。 */
    var jellyOn: Boolean
        get() = jellyState.value
        set(value) { if (jellyState.value != value) jellyState.value = value }

    init {
        lens.listeners += {
            if (jellyOn) {
                wobble(lens, jelly, 1.0)
            } else if (jelly.target != 0.0) {
                jelly.springTo(0.0, PrismSprings.jelly)
            }
            Unit
        }
        lens.listeners += ::dropLift
        lift.listeners += ::dropLift
        lens.listeners += ::settleRest
        lift.listeners += ::settleRest
    }

    override fun busy() = !settled && !down && !dropping

    override fun onFrame(frameNanos: Long) {
        if (!settled) settleRest()
    }

    /** _dropLift：UIKit 让透镜抬满并到位后才放下。 */
    private fun dropLift() {
        if (!dropping || down || lift.value < LIFT_SNAP) return
        if (abs(lens.target - lens.value) > LIFT_RELEASE) return
        dropping = false
        lift.springTo(0.0, PrismSprings.lift)
    }

    /** 工具栏：落定后有开启的按钮就滑过去停着（didUpdateWidget 的 _travelSpring），没有就淡出。 */
    private fun settleRest() {
        if (settled || down || dropping || lift.value > 0.0) return
        val rest = restIndex()?.takeIf { it in 0..last && count > 0 }
        if (rest == null) {
            if (show.target != 0.0) show.springTo(0.0, PrismSprings.fade)
        } else {
            if (lens.target != rest.toDouble()) lens.springTo(rest.toDouble(), PrismSprings.travel)
            if (show.target != 1.0) show.springTo(1.0, PrismSprings.fade)
        }
        settled = true
    }

    /** 开启的按钮或格子顺序变了：空闲时重新落位。第一次直接摆好，不做动画。 */
    fun syncRest(initial: Boolean) {
        val rest = restIndex()?.takeIf { it in 0..last && count > 0 }
        if (initial && rest != null) {
            lens.jumpTo(rest.toDouble())
            show.jumpTo(1.0)
            return
        }
        settled = false
        wake()
    }

    /** _positionAt（格宽 [pitch]，工具栏没有两端内边距）。 */
    private fun positionAt(x: Float, pitch: Float): Double {
        val position = x.toDouble() / pitch - 0.5
        if (position < 0) return rubberBand(position, OVERDRAG)
        if (position > last) return last + rubberBand(position - last, OVERDRAG)
        return position
    }

    private fun indexAt(position: Double): Int = dartRound(position).coerceIn(0, last)

    /** _handlePointerDown + _press。 */
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
        // 工具栏：透镜淡出了就从按下那格长出来，不从远处滑来。
        if (show.value < .05) lens.jumpTo(index.toDouble())
        show.springTo(1.0, PrismSprings.fade)
        lift.springTo(1.0, PrismSprings.lift)
        lens.springTo(index.toDouble(), PrismSprings.travel)
    }

    /** _slide。 */
    fun slide(at: Offset, pitch: Float) {
        if (pressedIndex == null || pitch <= 0f) return
        if (!dragging) {
            if (abs(at.x - pressX) < TOUCH_SLOP_DP * density) return
            dragging = true
        }
        val position = positionAt(at.x, pitch)
        lens.springTo(position, PrismSprings.follow)
        val index = indexAt(position)
        if (index != pressedIndex) pressedIndex = index
    }

    val dragged: Boolean get() = dragging

    /**
     * 松手：先 _fling（拖动中松手往前看 100ms，最多比手下那格多走一格），再 _release——[commit]（拖到别的格松手）
     * 落在那格并返回它；轻点落在按下那格（点击交给按钮自己）；拖了但不算数（横向滚动）回停靠位。
     */
    fun release(commit: Boolean, velocityX: Float, pitch: Float): Int? {
        down = false
        val pressed = pressedIndex
        if (pressed == null) {
            dropping = true
            dropLift()
            return null
        }
        if (dragging && pitch > 0f) {
            val lead = velocityX.toDouble() / pitch * FLING_PROJECTION_S
            val index = dartRound(lens.target + lead).coerceIn(pressed - 1, pressed + 1).coerceIn(0, last)
            if (index != pressed) pressedIndex = index
        }
        val index = pressedIndex ?: pressed
        val wasDragging = dragging
        pressedIndex = null
        dragging = false
        dropping = true
        val target = when {
            commit || !wasDragging -> index
            else -> restIndex()?.takeIf { it in 0..last } ?: downIndex
        }
        lens.springTo(target.toDouble(), PrismSprings.travel)
        dropLift()
        return if (commit) index else null
    }

    /** 工具栏：放弃这次按压（长按换位开始），透镜放下并淡出。 */
    fun cancel() {
        pressedIndex = null
        dragging = false
        down = false
        dropping = false
        lift.springTo(0.0, PrismSprings.lift)
        show.springTo(0.0, PrismSprings.fade)
        settled = true
    }

    /** _lensRect：抬起四周各长 8dp，果冻宽 × (1 + s)、高 × (1 − 1.4 s)，在 [height] 里竖直居中（可超出）。 */
    fun rect(position: Double, pitch: Float, pillWidth: Float, pillHeight: Float, height: Float): Rect {
        val growth = LENS_GROWTH_DP * density * 2f * lift.value.toFloat()
        val stretch = if (jellyOn) jelly.value.toFloat() else 0f
        val w = (pillWidth + growth) * (1f + stretch)
        val h = (pillHeight + growth) * (1f - stretch * JELLY_ASPECT)
        val left = (position.toFloat() + .5f) * pitch - w / 2f
        val top = (height - h) / 2f
        return Rect(left, top, left + w, top + h)
    }

    fun rect(pitch: Float, pillWidth: Float, pillHeight: Float, height: Float): Rect =
        rect(lens.value, pitch, pillWidth, pillHeight, height)
}

@Composable
internal fun rememberToolbarLensMotion(): ToolbarLensMotion {
    val density = LocalDensity.current.density
    val motion = remember(density) { ToolbarLensMotion(density) }
    motion.jellyOn = LiquidGlass.jelly(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(motion) { motion.run() }
    return motion
}

/** _lensShadows：模糊 5、下移 8，shadow 色 .055 × lift（深色 ×3），只画在玻璃外。Skia 的模糊半径换 sigma 与 Flutter 相同。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLensShadow(radiusPx: Float, lift: Float, dark: Boolean, paint: android.graphics.Paint) {
    val l = lift.coerceIn(0f, 1f)
    if (l <= 0f || size.width < 1f || size.height < 1f) return
    val alpha = (.055f * (if (dark) 3f else 1f) * l).coerceIn(0f, 1f)
    val r = min(radiusPx, size.minDimension / 2f)
    val outline = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(r))) }
    paint.reset()
    paint.isAntiAlias = true
    // 形状本身被裁掉，只留外面的阴影；阴影色不透明度不是 1 时 Android 用阴影色自己的不透明度。
    paint.color = android.graphics.Color.BLACK
    paint.setShadowLayer(5f * density, 0f, 8f * density, Color.Black.copy(alpha = alpha).toArgb())
    clipPath(outline, clipOp = ClipOp.Difference) {
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
    }
    paint.clearShadowLayer()
}

/**
 * 工具栏透镜本体：铺满工具栏内容（随横向滚动一起走），透镜按 [ToolbarLensMotion.rect] 摆放。
 * 静止 = _Lens 的底板（次色 HCT 色调 4/90、.85 × (1 − lift)）；抬起 = _GlassLens（LiquidGlassStyle.lens × lift + _lensShadows）。
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
            val w = kotlin.math.round(r.width).toInt().coerceAtLeast(1)
            val h = kotlin.math.round(r.height).toInt().coerceAtLeast(1)
            val left = kotlin.math.round(r.left).toInt()
            val top = kotlin.math.round(r.top).toInt()
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, top) }
        }
        .onGloballyPositioned { origin.value = it.positionInRoot() }
    val source = rememberLensBackdropSource(dark, (LocalKeyboardPalette.current?.surface ?: palette.surface))
    val content = com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.frostedGlass.backgroundOpacity
        .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val platter = remember(colors.secondary, dark) { PrismGlassStyle.platter(colors.secondary, dark) }
    val lensStyle = remember(dark) { PrismGlassStyle.lens(dark) }
    val surface = Modifier.drawWithCache {
        val cache = LensPaintCache()
        val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = size.height / 2f
        onDrawBehind {
            val shown = motion.show.value.toFloat().coerceIn(0f, 1f)
            if (shown <= .001f) return@onDrawBehind
            val lift = motion.lift.value.toFloat().coerceIn(0f, 1f)
            val platterAlpha = platter.alpha * (1f - lift) * shown
            if (platterAlpha > 0f) drawRoundRect(platter.copy(alpha = platterAlpha), cornerRadius = CornerRadius(min(r, size.width / 2f)))
            if (lift > 0f) {
                drawLensShadow(r, lift * shown, dark, shadowPaint)
                val (input, _) = cache.input(source, origin.value)
                val style = lensStyle.scale(lift)
                // 工具栏的淡入淡出（导航栏没有）按整片透明度叠。
                cache.paint.alpha = (255 * shown).toInt().coerceIn(0, 255)
                val drawn = drawPrismGlass(input, content, style, r, PrismGlow.None, cache.paint)
                cache.paint.alpha = 255
                if (!drawn) drawPrismFrost(style.scale(shown), r, cache.paint)
            }
        }
    }
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.Box(placed.then(surface))
    }
}

/**
 * _LensTint：第 [index] 格的图标，透镜外照常画，透镜里裁成透镜形状、按 1 + .16 × lift 放大、染成 [tint]
 * （standard 档：没有单独的 lensContent 玻璃时按它的亮度提亮）。透镜没碰到这格（左右各让 4dp）就照常画。
 */
internal fun Modifier.toolbarLensTint(
    motion: ToolbarLensMotion, index: Int, pitch: Float, pillWidth: Float, pillHeight: Float, tint: Color, dark: Boolean,
): Modifier = this.drawWithContent {
    val shown = motion.show.value.toFloat().coerceIn(0f, 1f)
    if (shown <= .001f || pitch <= 0f) {
        drawContent()
        return@drawWithContent
    }
    val lift = motion.lift.value.toFloat()
    val lensBounds = motion.rect(motion.lens.value - index, size.width, pillWidth, pillHeight, size.height)
    val barPadding = BAR_PADDING_DP * density
    val reached = Rect(lensBounds.left + barPadding, lensBounds.top, lensBounds.right - barPadding, lensBounds.bottom)
    val magnify = 1f + LENS_MAGNIFY * lift
    val bounds = Rect(0f, 0f, size.width, size.height)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val magnified = Rect(cx - cx * magnify, cy - cy * magnify, cx + cx * magnify, cy + cy * magnify)
    if (!reached.overlaps(bounds) && !reached.overlaps(magnified)) {
        drawContent()
        return@drawWithContent
    }
    val r = min(lensBounds.width, lensBounds.height) / 2f
    val lens = Path().apply { addRoundRect(RoundRect(lensBounds, CornerRadius(r))) }
    clipPath(lens, clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
    val brightness = PrismGlassStyle.lensContentBrightness(dark)
    val lit = Color(min(tint.red * brightness, 1f), min(tint.green * brightness, 1f), min(tint.blue * brightness, 1f), tint.alpha)
    clipPath(lens) {
        // 工具栏淡入淡出时（导航栏没有）：原样那份按 1 − show、着色那份按 show 叠。
        if (shown < 1f) drawIntoCanvas { canvas ->
            canvas.saveLayer(lensBounds, androidx.compose.ui.graphics.Paint().apply { alpha = 1f - shown })
            this@drawWithContent.drawContent()
            canvas.restore()
        }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(lensBounds, androidx.compose.ui.graphics.Paint().apply {
                colorFilter = ColorFilter.tint(lit, BlendMode.SrcIn)
                alpha = shown
            })
            scale(magnify, magnify, Offset(cx, cy)) { this@drawWithContent.drawContent() }
            canvas.restore()
        }
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
