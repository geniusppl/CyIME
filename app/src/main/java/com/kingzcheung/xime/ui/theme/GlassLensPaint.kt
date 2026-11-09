package com.kingzcheung.xime.ui.theme

import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * 透镜玻璃直接画（11-09 第二版，用户：learn-23 装上后按键和工具栏「和以前一模一样」）。
 *
 * learn-23 的透镜走 Kyant backdrop 的 drawBackdrop：底片录进一个 GraphicsLayer，着色器挂成 RenderEffect。
 * 这条路在输入法窗口里出不来（底片是共用背景按 20% 透明度画的、白键上的白高光、RenderEffect 依赖图层），
 * 真机上什么都看不出。这里不用图层也不用 RenderEffect：着色器（Android 13+）当画笔直接画在键帽底色上面、
 * 文字下面，底片是键盘背景本身（共用背景图、或主题渐变按整窗铺、或底色），以不透明的原色取样。
 * Android 12 及以下没有运行时着色器：同样的底片画在边缘一圈，再画暗线、上下沿高光。
 */

/** 透镜下面可以弯的东西：共用键盘背景图；没有时按主题渐变（整个窗口铺一张）；再没有就是底色。 */
internal class LensBackdropSource(
    val shared: SharedKeyboardBackdrop?,
    /** 主题渐变（不透明 ARGB），没有共用背景图时用。 */
    val gradient: IntArray?,
    val angle: Int,
    val fallback: Color,
    val root: View,
)

@Composable
internal fun rememberLensBackdropSource(dark: Boolean, fallback: Color): LensBackdropSource {
    val root = LocalView.current.rootView
    val shared = LocalSharedKeyboardBackdrop.current?.takeIf { it.windowRoot === root }
    val context = LocalContext.current
    val themeId = SettingsPreferences.getKeyboardTheme(context)
    val gradient = remember(themeId, dark) {
        val background = try { KeyboardThemes.getRenderingScheme(themeId).keyboardBackground } catch (_: Throwable) { null }
        val colors = background?.takeIf { it.type == "gradient" }?.let { if (dark) it.colorsDark ?: it.colors else it.colors }
        colors?.takeIf { it.size >= 2 }?.map { (0xff000000L or it).toInt() }?.toIntArray()
            ?.let { it to (background?.angle ?: 0) }
    }
    return remember(shared, gradient, fallback, root) {
        LensBackdropSource(shared, gradient?.first, gradient?.second ?: 0, fallback, root)
    }
}

/** 每个透镜（每个键、工具栏滑块）一份：画笔和底片着色器按需重建。只在主线程绘制里用。 */
internal class LensPaintCache {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var bitmapFor: ImageBitmap? = null
    private var bitmapShader: BitmapShader? = null
    private var gradientKey: List<Any>? = null
    private var gradientShader: LinearGradient? = null
    private var solidArgb = 0
    private var solidShader: LinearGradient? = null

    /**
     * 底片着色器，坐标已对到透镜自己的坐标系（[at] 是透镜左上角在 Compose 根里的位置）。
     * 第二个值：是否有可弯的图案（图片或渐变）；纯色时边缘那一圈不画（画了像键缩了一圈）。
     */
    fun input(source: LensBackdropSource, at: Offset?): Pair<Shader, Boolean> {
        val shared = source.shared
        val image = shared?.image
        if (at != null && shared != null && image != null) {
            val frame = shared.frame
            val width = frame.size.width.toFloat()
            val height = frame.size.height.toFloat()
            val bitmap = image.asAndroidBitmap()
            if (width > 0f && height > 0f && bitmap.width > 0 && bitmap.height > 0) {
                if (bitmapFor !== image || bitmapShader == null) {
                    bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) it.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    }
                    bitmapFor = image
                }
                // 与 drawSharedBackdropForKey / withSharedBackdropMotion 同一套对位（含动态背景的缩放、位移）。
                val offset = frame.origin + shared.windowToRoot - at
                val motion = shared.motion?.transform ?: BackgroundMotionTransform.Identity
                val left = offset.x + width * ((1f - motion.scale) / 2f + motion.shiftX)
                val top = offset.y + height * ((1f - motion.scale) / 2f + motion.shiftY)
                matrix.reset()
                matrix.setScale(width * motion.scale / bitmap.width, height * motion.scale / bitmap.height)
                matrix.postTranslate(left, top)
                val shader = bitmapShader!!
                shader.setLocalMatrix(matrix)
                return shader to true
            }
        }
        val colors = source.gradient
        val rootWidth = source.root.width.toFloat()
        val rootHeight = source.root.height.toFloat()
        if (at != null && colors != null && rootWidth > 0f && rootHeight > 0f) {
            val key = listOf(colors.toList(), source.angle, rootWidth, rootHeight)
            if (gradientKey != key || gradientShader == null) {
                // 与 renderFrostedBackground 相同的角度换算，铺满整个窗口。
                val radians = Math.toRadians(source.angle.toDouble())
                val x = cos(radians).toFloat()
                val y = sin(radians).toFloat()
                val divisor = (abs(x) + abs(y)).coerceAtLeast(0.00001f)
                gradientShader = LinearGradient(
                    rootWidth * (0.5f - x / divisor * 0.5f), rootHeight * (0.5f - y / divisor * 0.5f),
                    rootWidth * (0.5f + x / divisor * 0.5f), rootHeight * (0.5f + y / divisor * 0.5f),
                    colors, null, Shader.TileMode.CLAMP)
                gradientKey = key
            }
            matrix.reset()
            matrix.setTranslate(-at.x, -at.y)
            val shader = gradientShader!!
            shader.setLocalMatrix(matrix)
            return shader to true
        }
        val argb = source.fallback.copy(alpha = 1f).toArgb()
        if (solidShader == null || solidArgb != argb) {
            solidShader = LinearGradient(0f, 0f, 1f, 0f, argb, argb, Shader.TileMode.CLAMP)
            solidArgb = argb
        }
        return solidShader!! to false
    }
}

/**
 * 在当前尺寸里画一片透镜玻璃（圆角 [radiusPx]，胶囊传半高）。画在键帽底色之上、文字之下。
 * [strength]：静止 1，按下抬起到 1.35（折射更深、高光更亮）；[alpha] 整体淡入淡出（工具栏滑块用）。
 * [tint] 是整片透镜的底色（非预乘），按键传透明。
 */
internal fun DrawScope.drawGlassLens(
    source: LensBackdropSource, at: Offset?, radiusPx: Float, dark: Boolean, strength: Float, alpha: Float,
    glowAt: Offset, glow: Float, glowReachPx: Float, tint: Color, cache: LensPaintCache,
) {
    if (alpha <= .001f || size.width < 1f || size.height < 1f) return
    val (input, patterned) = cache.input(source, at)
    val drawn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        drawLensShader(input, radiusPx, dark, strength, if (patterned) 1f else 0f, alpha, glowAt, glow, glowReachPx, tint, cache)
    if (!drawn) drawGlassLensWithoutShader(input, patterned, radiusPx, dark, strength, alpha, tint, cache)
}

/** 着色器编译失败时返回 false，由调用方退回手画。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawLensShader(
    input: Shader, radiusPx: Float, dark: Boolean, strength: Float, edge: Float, alpha: Float,
    glowAt: Offset, glow: Float, glowReachPx: Float, tint: Color, cache: LensPaintCache,
): Boolean {
    val shader = GlassLensPaintShader.shared ?: return false
    bindLens(shader, input, radiusPx, dark, strength, edge, alpha, glowAt, glow, glowReachPx, tint)
    val paint = cache.paint
    paint.shader = shader
    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    paint.shader = null
    return true
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.bindLens(
    shader: RuntimeShader, input: Shader, radiusPx: Float, dark: Boolean, strength: Float, edge: Float, alpha: Float,
    glowAt: Offset, glow: Float, glowReachPx: Float, tint: Color,
) {
    shader.setInputShader("image", input)
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("radius", radiusPx)
    shader.setFloatUniform("dp", density)
    shader.setFloatUniform("strength", strength.coerceIn(0f, 1.6f))
    shader.setFloatUniform("edge", edge)
    shader.setFloatUniform("dark", if (dark) 1f else 0f)
    shader.setFloatUniform("fade", alpha.coerceIn(0f, 1f))
    val a = tint.alpha
    shader.setFloatUniform("tint", tint.red * a, tint.green * a, tint.blue * a, a)
    shader.setFloatUniform("glow", glowAt.x, glowAt.y, glow, glowReachPx)
}

/** Android 12 及以下：底片画在边缘一圈（不弯），再画厚度、暗线和上下沿高光。按压鼓起由外面的弹簧做，两边一样。 */
private fun DrawScope.drawGlassLensWithoutShader(
    input: Shader, patterned: Boolean, radiusPx: Float, dark: Boolean, strength: Float, alpha: Float, tint: Color,
    cache: LensPaintCache,
) {
    val w = size.width
    val h = size.height
    val half = min(w, h) / 2f
    val corner = radiusPx.coerceIn(0f, half)
    val outer = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(corner))) }
    val t = min(strength, 1.35f)
    if (tint.alpha > 0f) drawPath(outer, tint, alpha = alpha)
    if (patterned) {
        val bevel = min(6f * density, half * .7f)
        val inner = Path().apply {
            addRoundRect(RoundRect(Rect(bevel, bevel, w - bevel, h - bevel), CornerRadius((corner - bevel).coerceAtLeast(0f))))
        }
        val ring = Path().apply { op(outer, inner, PathOperation.Difference) }
        val paint = cache.paint
        paint.shader = input
        paint.alpha = (255 * .8f * alpha).toInt().coerceIn(0, 255)
        drawIntoCanvas { it.nativeCanvas.drawPath(ring.asAndroidPath(), paint) }
        paint.shader = null
        paint.alpha = 255
    }
    clipPath(outer) {
        // 厚度：上沿亮、下沿暗。
        drawRect(Brush.verticalGradient(
            0f to Color.White.copy(alpha = .30f * t), .22f to Color.White.copy(alpha = 0f),
            .8f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = (if (dark) .18f else .10f) * t)), alpha = alpha)
        // 暗线 0.7dp。
        val line = .7f * density
        drawRoundRect(Color.Black.copy(alpha = if (dark) .5f else .34f), topLeft = Offset(line / 2f, line / 2f),
            size = androidx.compose.ui.geometry.Size(w - line, h - line), cornerRadius = CornerRadius((corner - line / 2f).coerceAtLeast(0f)),
            style = Stroke(line), alpha = alpha)
        // 上下沿高光，紧贴暗线内侧。
        val rim = 1.3f * density
        val inset = line + rim / 2f
        drawRoundRect(Brush.verticalGradient(
            0f to Color.White.copy(alpha = .95f), .3f to Color.White.copy(alpha = .28f),
            .7f to Color.White.copy(alpha = .28f), 1f to Color.White.copy(alpha = .55f)),
            topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(w - inset * 2f, h - inset * 2f),
            cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)), style = Stroke(rim), alpha = alpha * min(t, 1f))
    }
}

/** 全进程编译一次；每次画前设参数（系统在绘制时取快照，各键共用安全）。编译失败为 null，退回手画。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object GlassLensPaintShader {
    val shared: RuntimeShader? by lazy {
        try { RuntimeShader(SOURCE) } catch (e: Throwable) {
            android.util.Log.w("CyImeGlassLens", "lens shader failed to compile", e)
            null
        }
    }

    private const val SOURCE = """
uniform shader image;
uniform float2 size;
uniform float radius;
uniform float dp;
uniform float strength;
uniform float edge;
uniform float dark;
uniform float fade;
uniform float4 tint;
uniform float4 glow;

half4 main(float2 p) {
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
    float t = strength;
    float tt = min(t, 1.0);
    float bevel = max(min(6.5 * dp, halfMinor * 0.7), 1.0);
    float e = 1.0 - smoothstep(0.0, bevel, inset);

    // 预乘累积：col 已乘 a。
    float3 col = tint.rgb;
    float a = tint.a;

    // 斜面一圈：凸透镜边缘把背景往里折（越靠外偏得越多），红绿蓝偏移不同 = 色散；不透明取样，饱和度提一点，
    // 浅色渐变上才看得出是「透过玻璃边看到的背景」。
    float bandA = edge * 0.92 * e * (2.0 - e);
    if (bandA > 0.002) {
        float depth = min(9.0 * dp * t, halfMinor);
        float2 s = -n * depth * e * e;
        float2 tangent = float2(-n.y, n.x) * 1.6 * dp * t * e;
        half4 rr = image.eval(p + s * 0.82 + tangent);
        half4 gg = image.eval(p + s);
        half4 bb = image.eval(p + s * 1.22 - tangent);
        float ia = max(float(gg.a), 0.001);
        float3 g = float3(float(rr.r), float(gg.g), float(bb.b)) / ia;
        float l = dot(g, float3(0.2126, 0.7152, 0.0722));
        g = clamp(mix(float3(l), g, 1.0 + 0.55 * t), 0.0, 1.0);
        g *= 1.0 - (dark > 0.5 ? 0.05 : 0.08) * e;
        float ba = clamp(bandA * min(ia, 1.0), 0.0, 1.0);
        col = g * ba + col * (1.0 - ba);
        a = ba + a * (1.0 - ba);
    }

    // 厚度：斜面里上沿亮、下沿暗。
    float lit = clamp((dark > 0.5 ? 0.16 : 0.22) * t * e * max(-n.y, 0.0), 0.0, 1.0);
    col = float3(lit) + col * (1.0 - lit);
    a = lit + a * (1.0 - lit);
    float dim = clamp((dark > 0.5 ? 0.16 : 0.10) * t * e * max(n.y, 0.0), 0.0, 1.0);
    col = col * (1.0 - dim);
    a = dim + a * (1.0 - dim);

    // 外圈暗线 0.7dp。
    float lw = 0.7 * dp;
    float line = clamp(lw - inset + 0.5, 0.0, 1.0);
    float la = line * (dark > 0.5 ? 0.5 : 0.36) * tt;
    col = col * (1.0 - la);
    a = la + a * (1.0 - la);

    // 高光：紧贴暗线内侧 1.3dp，上沿最亮、下沿次之、两侧淡。
    float within = inset - lw;
    float rimBand = clamp(1.0 - within / (1.3 * dp), 0.0, 1.0) * clamp(within + 1.0, 0.0, 1.0);
    float topFace = smoothstep(0.15, 0.85, -n.y);
    float bottomFace = smoothstep(0.15, 0.85, n.y);
    float sideFace = 1.0 - max(topFace, bottomFace);
    float shine = clamp(rimBand * (0.95 * topFace + 0.6 * bottomFace + 0.3 * sideFace) * min(t, 1.2), 0.0, 1.0);
    col = float3(shine) + col * (1.0 - shine);
    a = shine + a * (1.0 - shine);

    // 指尖光：手指下面亮一团，松手时摊开淡出；一部分均匀洒满。
    if (glow.z > 0.0) {
        float2 toGlow = p - glow.xy;
        float reach = max(glow.w * glow.w, 1.0);
        float light = clamp(glow.z * mix(exp(-dot(toGlow, toGlow) / reach), 1.0, 0.35), 0.0, 1.0);
        col = float3(light) + col * (1.0 - light);
        a = light + a * (1.0 - light);
    }

    a = clamp(a, 0.0, 1.0);
    float k = inside * fade;
    return half4(half3(min(col, float3(a)) * k), half(a * k));
}
"""
}
