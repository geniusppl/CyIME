package com.kingzcheung.xime.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * iOS 液态玻璃按键（用户 11-09：参考 FlClash 的液态玻璃和选中动效）。
 * 数值移植自 chen08209/prism_ui（FlClash 的界面库，GPL-3.0，与 CyIME 同许可）：
 * - 着色器：shaders/liquid_glass.frag 的「凸透镜」分支 + LiquidGlassStyle.lens 参数；
 * - 手感：navigation_dock.dart 的 ElasticPress（按下鼓起、跟手拉伸、松手回弹）和指尖光。
 */

// ElasticPress：鼓起最多 1/4，且不超过 maxGrowth（键小，取 10dp，免得压住邻键太多）。
private const val PRESS_GROWTH = .25f
private const val MAX_GROWTH_DP = 10f
private const val PULL_LIMIT = 7f / 32f
private const val PULL_STRETCH = .5f
private const val LIFT_SNAP = .975f
// 指尖光：半径 28dp，松手时扩大 3 倍淡出；峰值键上调到 .10（原 .06 是给整条导航栏的）。
private const val GLOW_REACH_DP = 28f
private const val GLOW_GROWTH = 3f
private const val GLOW_PEAK = .10f
private const val GLOW_WASH = .35f

private val pressSpring = spring<Float>(dampingRatio = .593f, stiffness = 311f)
// 120ms 无回弹：刚度 ≈ (2π/0.12)²。
private val trackSpring = spring<Float>(dampingRatio = 1f, stiffness = 2700f)

/** 弹性按压：按下鼓起，拖动时往手指方向拉长，松手弹回；轻点也先鼓满再回（UIKit 的做法）。 */
@Stable
internal class ElasticKeyPress(private val scope: CoroutineScope, private val density: Float) {
    val lift = Animatable(0f)
    val pullX = Animatable(0f)
    val pullY = Animatable(0f)
    val glow = Animatable(0f)
    val glowSpread = Animatable(0f)
    var glowAt = Offset.Zero
        private set
    private var releaseJob: Job? = null

    private fun rubberBand(overshoot: Float, limit: Float): Float {
        if (limit <= 0f) return 0f
        val pull = 1f - 1f / (abs(overshoot) * .55f / limit + 1f)
        return limit * pull * sign(overshoot)
    }

    fun down(at: Offset) {
        releaseJob?.cancel()
        glowAt = at
        scope.launch { lift.animateTo(1f, pressSpring) }
        scope.launch { glowSpread.snapTo(0f) }
        scope.launch { glow.animateTo(1f, tween(100)) }
    }

    fun move(at: Offset, origin: Offset, width: Float, height: Float) {
        glowAt = at
        val limit = min(width, height) * PULL_LIMIT
        scope.launch { pullX.animateTo(rubberBand(at.x - origin.x, limit), trackSpring) }
        scope.launch { pullY.animateTo(rubberBand(at.y - origin.y, limit), trackSpring) }
    }

    fun up() {
        scope.launch { pullX.animateTo(0f, pressSpring) }
        scope.launch { pullY.animateTo(0f, pressSpring) }
        scope.launch { glowSpread.animateTo(1f, tween(500)) }
        scope.launch { glow.animateTo(0f, tween(500)) }
        releaseJob = scope.launch {
            // 轻点比鼓起快：先鼓满再回。
            while (lift.value < LIFT_SNAP && lift.targetValue == 1f) {
                androidx.compose.runtime.withFrameNanos { }
            }
            lift.animateTo(0f, pressSpring)
        }
    }

    val modifier: Modifier = Modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                // 只旁观，不消费：不影响点击、滑动和长按。
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down(first.position)
                val id = first.id
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == id } ?: break
                    if (!change.pressed) break
                    move(change.position, first.position, size.width.toFloat(), size.height.toFloat())
                }
                up()
            }
        }
        .graphicsLayer {
            val swell = 1f + lift.value * min(PRESS_GROWTH, MAX_GROWTH_DP * density / max(size.maxDimension, 1f))
            val px = pullX.value
            val py = pullY.value
            scaleX = swell * (1f + abs(px) / max(size.width, 1f) * PULL_STRETCH)
            scaleY = swell * (1f + abs(py) / max(size.height, 1f) * PULL_STRETCH)
            translationX = px
            translationY = py
        }
}

@Composable
internal fun rememberElasticKeyPress(): ElasticKeyPress {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember(scope, density) { ElasticKeyPress(scope, density) }
}

/** LiquidGlassStyle.lens：凸透镜往外读、色散 6.5dp、上下沿高光 .6、外圈暗线 .42、两端略暗 .045。 */
internal fun BackdropEffectScope.iosGlass(radiusPx: Float, dark: Boolean, press: ElasticKeyPress) {
    val glow = press.glow.value
    val spread = press.glowSpread.value
    runtimeShaderEffect("CyImeIosGlass", IOS_GLASS_SHADER, "content") {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("radius", radiusPx)
        setFloatUniform("dp", density)
        // 深度、斜面宽、色散（dp）
        setFloatUniform("optics", 4.3f, 5.5f, 6.5f)
        // 饱和度、亮度、两端变暗
        setFloatUniform("tone", 1f, if (dark) 1.33f else 1f, .045f)
        // 高光强度、高光宽（dp）、暗线强度
        setFloatUniform("rim", if (dark) .3f else .6f, 1.1f, .42f)
        setFloatUniform("glow", press.glowAt.x, press.glowAt.y,
            GLOW_PEAK * glow, GLOW_REACH_DP * density * (1f + GLOW_GROWTH * spread))
        setFloatUniform("wash", GLOW_WASH)
    }
}

private const val IOS_GLASS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float dp;
uniform float3 optics;
uniform float3 tone;
uniform float3 rim;
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

    // 凸透镜：边上不偏，斜面内侧偏到最深，往中间渐平；往外读，把边缘下面的画面拉进来。
    float bevel = max(min(optics.y * dp, halfMinor * 0.5), 0.001);
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

    float alpha = base.a;
    float3 color = float3(base.rgb) / max(alpha, 0.0001);
    color = mix(float3(dot(color, kLuma)), color, tone.x) * tone.y;
    color *= 1.0 - tone.z * abs(n.x) * exp(-inset / (3.0 * dp));
    float3 glass = color * alpha;
    float cover = alpha;

    // 暗线：最外 0.67dp，压暗下面的画面；底片是空的（纯色主题）就画一道很淡的黑边。
    float kLine = 0.67 * dp;
    float line = clamp((kLine - inset) / 1.0 + 0.5, 0.0, 1.0);
    if (rim.z > 0.0 && line > 0.0) {
        float3 darker = max(glass - 0.298 * cover, 0.22 * glass);
        glass = mix(glass, darker, rim.z * line);
        float faint = 0.10 * rim.z * line * (1.0 - cover);
        glass = glass * (1.0 - faint);
        cover = cover + (1.0 - cover) * faint;
    }

    // 高光：只在上下沿（法线朝上下时），紧贴暗线内侧，宽 rim.y dp，像白光叠上去。
    float within = max(inset - kLine, 0.0);
    float facing = smoothstep(0.65, 0.95, abs(n.y));
    float band = clamp(1.0 - within / max(rim.y * dp, 0.001), 0.0, 1.0);
    float shine = min(rim.x * facing * band, 1.0);
    glass = glass * (1.0 - shine) + float3(shine);
    cover = cover * (1.0 - shine) + shine;

    // 指尖光：手指下面亮一团，松手时摊开淡出；一部分均匀洒满整个键。
    if (glow.z > 0.0) {
        float2 toGlow = p - glow.xy;
        float reach = max(glow.w * glow.w, 1.0);
        float light = glow.z * mix(exp(-dot(toGlow, toGlow) / reach), 1.0, wash);
        glass = glass * (1.0 - light) + float3(light);
        cover = cover * (1.0 - light) + light;
    }

    return half4(half3(min(glass, float3(cover))), half(cover)) * half(inside);
}
"""
