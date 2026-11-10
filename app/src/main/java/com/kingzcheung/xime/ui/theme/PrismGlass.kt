package com.kingzcheung.xime.ui.theme

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * iOS 液态玻璃第六版（用户 11-10：learn-28 的 Nekogram 玻璃和果冻都不好，要照 FlClash 逐行照搬）。
 *
 * 逐行翻译自 chen08209/prism_ui（FlClash 的界面库，GPL-3.0，与 CyIME 同许可）：
 * - [PRISM_LIQUID_GLASS]：shaders/liquid_glass.frag 的 AGSL 翻译。按键和工具栏用不到的分支没有搬：
 *   uOutlined（轮廓/goo 形状，按键是 LiquidGlassCorners → 0）、LiquidGlassRim 的菜单高光（rim == null → 全 0）、
 *   haze（regular/lens 的 haze 都是 0）、veil（host 没有 LiquidGlassVeil → 透明）。四个角半径相同，
 *   uCurves[12] 里四个角的三组值一样，所以只传一组（uCurveAlong/uCurveAcross/uCurveCenters），cornerInset 的 mix 取到的就是它。
 * - [PrismGlassStyle]：lib/liquid_glass.dart 的 LiquidGlassStyle（regular / lens / scale）原样。
 * - [prismCornerCurve]：liquidGlassCornerCurves / _superellipseOctant / _octantFit 原样（含系数表）。
 * - [drawPrismGlass]：_LiquidGlassLayer._look 的 uniform 顺序与取值；[drawPrismFrost]：没有着色器时的 _drawFrost。
 * 和原版不同、必须说明的地方：
 * - 原版取样的是 host 截下来的「玻璃后面的画面」；输入法读不到后面应用的像素，所以底片是键盘自己的背景
 *   （共用背景图 / 主题渐变 / 底色），按键盘背景的不透明度（uContent）取样——透出模式下就是 learn-25 那层
 *   淡色底，后面由系统窗口模糊打底。
 * - Flutter 把玻璃裁成 RSuperellipse；Android 没有这个图元，裁剪用同半径的圆角矩形（暗线和高光仍按 superellipse 算）。
 */

// ---------------------------------------------------------------- LiquidGlassStyle

/** lib/liquid_glass.dart 的 LiquidGlassStyle，长度单位 dp；[tint] 是非预乘颜色。 */
internal data class PrismGlassStyle(
    val tint: Color,
    val contrast: Float = 0f,
    val blur: Float = 2.5f,
    val haze: Float = 0f,
    val hazeBlur: Float = 0f,
    val refraction: Float = 60f,
    val bevel: Float = 20f,
    val saturation: Float = 1.5f,
    val brightness: Float = 1f,
    val rim: Float = .6f,
    val rimWidth: Float = 1.3f,
    val rimTail: Float = 0f,
    val rimFocus: Float = 0f,
    val rimAdded: Float = 0f,
    val endShade: Float = 0f,
    val dispersion: Float = 0f,
    val rimBlur: Float = 0f,
    val hairline: Float = 0f,
    val hairlineSide: Float? = null,
) {
    val resolvedHairlineSide: Float get() = hairlineSide ?: hairline

    /** LiquidGlassStyle.scale：「玻璃是弯出来的，不是淡入的」。 */
    fun scale(t: Float): PrismGlassStyle {
        val amount = t.coerceIn(0f, 1f)
        return PrismGlassStyle(
            tint = tint.copy(alpha = tint.alpha * amount),
            contrast = contrast * amount,
            blur = blur * amount,
            haze = haze * amount,
            hazeBlur = hazeBlur,
            refraction = refraction * amount,
            bevel = bevel,
            saturation = 1f + (saturation - 1f) * amount,
            brightness = 1f + (brightness - 1f) * amount,
            rim = rim * amount,
            rimWidth = rimWidth,
            rimTail = rimTail,
            rimFocus = rimFocus,
            rimAdded = rimAdded,
            endShade = endShade * amount,
            dispersion = dispersion * amount,
            rimBlur = rimBlur * amount,
            hairline = hairline * amount,
            hairlineSide = hairlineSide?.let { it * amount },
        )
    }

    companion object {
        /** LiquidGlassStyle.regular：玻璃按钮（TonalButtonGroup / 玻璃上的按钮）的样式。 */
        fun regular(dark: Boolean, surfaceContainer: Color): PrismGlassStyle = if (dark) PrismGlassStyle(
            tint = darkGlassTint(surfaceContainer).copy(alpha = .62f),
            contrast = -.4f,
            saturation = 2.84f,
            rim = .28f,
            rimWidth = 1.2f,
            rimTail = .17f,
            rimFocus = 1f,
            rimAdded = .38f,
            hairlineSide = .75f,
        ) else PrismGlassStyle(
            tint = Color(0x8AFDFDFD),
            contrast = .4f,
            saturation = 2.08f,
            rim = .28f,
            rimWidth = 1.2f,
            rimTail = .17f,
            rimFocus = 1f,
            rimAdded = .38f,
            hairline = .25f,
            hairlineSide = .75f,
        )

        /** LiquidGlassStyle.lens：导航栏抬起的透镜。 */
        fun lens(dark: Boolean): PrismGlassStyle = PrismGlassStyle(
            tint = Color.Transparent,
            blur = 0f,
            refraction = -4.3f,
            bevel = 5.5f,
            saturation = 1f,
            brightness = if (dark) 1.33f else 1f,
            rim = if (dark) .3f else .6f,
            rimWidth = 1.1f,
            endShade = .045f,
            dispersion = 6.5f,
            rimBlur = 1.5f,
            hairline = .42f,
        )

        /** LiquidGlassStyle.lensContent 的亮度（standard 档里透镜内的图标按它提亮着色）。 */
        fun lensContentBrightness(dark: Boolean): Float = if (dark) 1.33f else 1f

        /** _darkGlassTint：colorScheme.surfaceContainer.shiftTone(7)（HCT 色调 +7，色相、彩度不变）。 */
        fun darkGlassTint(surfaceContainer: Color): Color = shiftTone(surfaceContainer, 7.0)

        /** color.dart 的 shiftTone，用 Material Color Utilities 的 HCT（与 prism_ui 的 hct 包同源）。 */
        @Suppress("RestrictedApi")
        fun shiftTone(color: Color, delta: Double): Color {
            val hct = com.google.android.material.color.utilities.Hct.fromInt(color.copy(alpha = 1f).toArgb())
            val shifted = com.google.android.material.color.utilities.Hct.from(
                hct.hue, hct.chroma, (hct.tone + delta).coerceIn(0.0, 100.0))
            return Color(shifted.toInt())
        }

        /** navigation_dock.dart _Lens：静止透镜的底板，次色的色相与彩度、色调深色 4 / 浅色 90，不透明度 .85。 */
        @Suppress("RestrictedApi")
        fun platter(secondary: Color, dark: Boolean): Color {
            val hct = com.google.android.material.color.utilities.Hct.fromInt(secondary.copy(alpha = 1f).toArgb())
            val tone = if (dark) 4.0 else 90.0
            return Color(com.google.android.material.color.utilities.Hct.from(hct.hue, hct.chroma, tone).toInt())
                .copy(alpha = .85f)
        }
    }
}

/** LiquidGlassGlow：按压在玻璃上打的光，坐标 dp（玻璃左上角起）。 */
internal class PrismGlow(val center: Offset, val reach: Float, val strength: Float, val wash: Float = 0f) {
    companion object {
        val None = PrismGlow(Offset.Zero, 1f, 0f)
    }
}

// ---------------------------------------------------------------- superellipse 角

/** _Octant：n、J、圆弧半径、圆弧圆心。 */
private class Octant(val n: Double, val jx: Double, val jy: Double, val cx: Double, val cy: Double, val radius: Double)

private val NO_OCTANT = Octant(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
private val OCTANT_GAP = 1 - sqrt(.5)

/** _octantFits：按八分之一边长与半径之比：n、1 / (1 - xJ / a)。 */
private val OCTANT_FITS = arrayOf(
    doubleArrayOf(2.0, 2.00000000, 1.13276676),
    doubleArrayOf(2.1, 2.18349805, 1.20311921),
    doubleArrayOf(2.2, 2.33888662, 1.28698796),
    doubleArrayOf(2.3, 2.48660575, 1.36351941),
    doubleArrayOf(2.4, 2.62226596, 1.44717976),
    doubleArrayOf(2.5, 2.75148990, 1.53385819),
    doubleArrayOf(3.0, 3.36298265, 1.98288283),
    doubleArrayOf(3.5, 4.08649929, 2.23811846),
    doubleArrayOf(4.0, 4.85481134, 2.47563463),
    doubleArrayOf(4.5, 5.62945551, 2.72948597),
    doubleArrayOf(5.0, 6.43023796, 2.98020421),
)

private fun lerpDouble(a: Double, b: Double, t: Double) = a + (b - a) * t

/** _octantFit。 */
private fun octantFit(ratio: Double): Pair<Double, Double> {
    val lastFit = OCTANT_FITS.last()
    val past = ratio - lastFit[0]
    if (past > 0) return (lastFit[1] + 1.559599389 * past) to (1 - 1 / (lastFit[2] + 0.522807185 * past))
    var index = 0
    while (index < OCTANT_FITS.size - 2 && ratio > OCTANT_FITS[index + 1][0]) index++
    val f0 = OCTANT_FITS[index]
    val f1 = OCTANT_FITS[index + 1]
    val t = max(0.0, (ratio - f0[0]) / (f1[0] - f0[0]))
    return lerpDouble(f0[1], f1[1], t) to (1 - 1 / lerpDouble(f0[2], f1[2], t))
}

/** _superellipseOctant（impeller/geometry/round_superellipse_param.cc 的 ComputeOctant）。 */
private fun superellipseOctant(a: Double, radius: Double): Octant {
    if (radius <= 1e-3) return NO_OCTANT
    val (n, jointAt) = octantFit(a * 2 / radius)
    val jx = jointAt * a
    val jy = (1 - jointAt.pow(n)).pow(1 / n) * a
    val tangent = (jx / jy).pow(n - 1)
    val diagonal = (jx - tangent * jy) / (1 - tangent)
    val meet = a - OCTANT_GAP * radius
    val arc = (meet - diagonal) * sqrt(2.0)
    val chordX = meet - jx
    val chordY = meet - jy
    val chordSq = chordX * chordX + chordY * chordY
    val reach = sqrt(max(0.0, arc * arc - chordSq / 4))
    val chord = sqrt(chordSq)
    val cx = (jx + meet) / 2 + chordY / chord * reach
    val cy = (jy + meet) / 2 + -chordX / chord * reach
    return Octant(n, jx, jy, cx, cy, arc)
}

/** _cornerCurve：沿上/下边的八分之一、沿侧边的八分之一、两段圆弧圆心（各 4 个数）。 */
internal fun prismCornerCurve(halfWidth: Float, halfHeight: Float, radius: Float): FloatArray {
    val along = superellipseOctant(halfWidth.toDouble(), radius.toDouble())
    val across = superellipseOctant(halfHeight.toDouble(), radius.toDouble())
    return floatArrayOf(
        along.n.toFloat(), along.jx.toFloat(), along.jy.toFloat(), along.radius.toFloat(),
        across.n.toFloat(), across.jx.toFloat(), across.jy.toFloat(), across.radius.toFloat(),
        along.cx.toFloat(), along.cy.toFloat(), across.cx.toFloat(), across.cy.toFloat(),
    )
}

// ---------------------------------------------------------------- 画

/**
 * 画一片 prism 玻璃，铺满当前尺寸（玻璃坐标 = 本地像素 / density）。[input] 是对到本地坐标的底片，[content] 是它的不透明度。
 * 返回 false 表示着色器不可用（Android 12 及以下或编译失败），调用方画 [drawPrismFrost]。
 */
internal fun DrawScope.drawPrismGlass(
    input: Shader, content: Float, style: PrismGlassStyle, radiusPx: Float, glow: PrismGlow, paint: Paint,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (size.width < 1f || size.height < 1f) return true
    return drawPrismGlass33(input, content, style, radiusPx, glow, paint)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawPrismGlass33(
    input: Shader, content: Float, style: PrismGlassStyle, radiusPx: Float, glow: PrismGlow, paint: Paint,
): Boolean {
    val s = PrismGlassShader.shared ?: return false
    val d = density
    val w = size.width / d
    val h = size.height / d
    // LiquidGlassCorners：半径不超过短边一半。
    val corner = min(radiusPx / d, min(w, h) / 2f)
    val curve = prismCornerCurve(w / 2f, h / 2f, corner)
    try {
        s.setInputShader("uTexture", input)
        s.setFloatUniform("uSize", 1e5f, 1e5f)
        // 玻璃坐标（dp）：fragCoord × (1 / density)；底片是本地像素，同一换算。
        s.setFloatUniform("uFrame", 0f, 0f, 1f / d, 1f / d)
        s.setFloatUniform("uSample", 0f, 0f, 1f / d, 1f / d)
        // 底片铺满整个键盘，不必夹在某个截图槽位里。
        s.setFloatUniform("uClamp", -1e5f, -1e5f, 1e5f, 1e5f)
        // 每个设备像素多少 dp；底片没有预先模糊（sigma 0）。
        s.setFloatUniform("uTexels", 1f / d, 0f)
        // _look 的顺序：
        s.setFloatUniform("uShape", w, h, style.bevel, style.blur)
        s.setFloatUniform("uCorners", corner, corner, corner, corner)
        s.setFloatUniform("uCurveAlong", curve[0], curve[1], curve[2], curve[3])
        s.setFloatUniform("uCurveAcross", curve[4], curve[5], curve[6], curve[7])
        s.setFloatUniform("uCurveCenters", curve[8], curve[9], curve[10], curve[11])
        s.setFloatUniform("uOptics", style.refraction, style.saturation, style.brightness)
        s.setFloatUniform("uTint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
        s.setFloatUniform("uLight", style.rim, style.rimWidth, style.endShade)
        s.setFloatUniform("uGlow", glow.center.x, glow.center.y, glow.strength, style.contrast)
        s.setFloatUniform("uGlowSpread", glow.reach, glow.wash)
        s.setFloatUniform("uEdge", style.hairline, style.dispersion, style.rimBlur)
        s.setFloatUniform("uRim", style.rimTail, style.rimFocus, style.rimAdded, style.resolvedHairlineSide)
        s.setFloatUniform("uContent", content.coerceIn(0f, 1f))
    } catch (e: Throwable) {
        android.util.Log.w("CyImePrismGlass", "prism glass uniforms failed", e)
        return false
    }
    val r = corner * d
    paint.shader = s
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
    paint.shader = null
    return true
}

/**
 * _LiquidGlassLayer._drawFrost（没有截图时）：底色加厚 .2 代替模糊，左上→右下的白色边（rim、rim×.15、rim×.5），
 * 外圈 0.67dp 黑色细线（不透明度 (hairline + hairlineSide) / 2 × .3）。
 */
internal fun DrawScope.drawPrismFrost(style: PrismGlassStyle, radiusPx: Float, paint: Paint) {
    if (size.width < 1f || size.height < 1f) return
    val d = density
    val w = size.width
    val h = size.height
    val corner = min(radiusPx, min(w, h) / 2f)
    drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = style.tint.copy(alpha = min(1f, style.tint.alpha + .2f)).toArgb()
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
        val rimWidth = style.rimWidth * d
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rimWidth
        paint.shader = android.graphics.LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.White.copy(alpha = style.rim.coerceIn(0f, 1f)).toArgb(),
                Color.White.copy(alpha = (style.rim * .15f).coerceIn(0f, 1f)).toArgb(),
                Color.White.copy(alpha = (style.rim * .5f).coerceIn(0f, 1f)).toArgb()),
            null, Shader.TileMode.CLAMP)
        val ri = rimWidth / 2f
        canvas.drawRoundRect(ri, ri, w - ri, h - ri, max(0f, corner - ri), max(0f, corner - ri), paint)
        paint.shader = null
        val line = .67f * d
        paint.strokeWidth = line
        paint.color = Color.Black.copy(alpha = ((style.hairline + style.resolvedHairlineSide) / 2f * .3f).coerceIn(0f, 1f)).toArgb()
        val li = line / 2f
        canvas.drawRoundRect(li, li, w - li, h - li, max(0f, corner - li), max(0f, corner - li), paint)
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
    }
}

/** 全进程编译一次，每次画前设参数（系统录制时取快照）。编译失败为 null。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object PrismGlassShader {
    val shared: RuntimeShader? by lazy {
        try { RuntimeShader(PRISM_LIQUID_GLASS) } catch (e: Throwable) {
            android.util.Log.w("CyImePrismGlass", "prism liquid glass shader failed to compile", e)
            null
        }
    }
}

/** chen08209/prism_ui shaders/liquid_glass.frag 的 AGSL 翻译（注释保留原文）。 */
internal const val PRISM_LIQUID_GLASS = """
// Translated line by line from shaders/liquid_glass.frag of prism_ui
// (https://github.com/chen08209/prism_ui, the UI kit of FlClash, GPL-3.0).
// FlutterFragCoord() -> fragCoord; texture(uTexture, pixel / uSize) ->
// uTexture.eval(pixel). Branches the keyboard never takes are left out:
// outlined and goo panes (uOutlined 0), LiquidGlassRim's shine (all 0), haze
// (0) and the veil (none). All four corners are alike, so uCurves holds the
// same three vectors for each and they are passed once.

uniform shader uTexture;

// The texture's size.
uniform float2 uSize;
// Glass coordinates, in dp from its top left, of fragCoord: an origin,
// then a scale per unit.
uniform float4 uFrame;
// Glass coordinates of the texture's pixels, as uFrame.
uniform float4 uSample;
// The first and last pixel centers the pane may read.
uniform float4 uClamp;
// Dp per device pixel, and the frost sigma in its own pixels the texture
// already holds.
uniform float2 uTexels;
// Width, height, bevel width, frost sigma.
uniform float4 uShape;
// Corner radii: top left, top right, bottom right, bottom left.
uniform float4 uCorners;
// The superellipse corner the pane is clipped to: n, J and the arc's radius
// for the octant along the top or bottom, the same for the one along the
// side, then both arcs' centers. A sharp corner has n 0.
uniform float4 uCurveAlong;
uniform float4 uCurveAcross;
uniform float4 uCurveCenters;
// Refraction depth at the rim, saturation, brightness. A negative depth makes
// a raised lens, which bends outward on UIKit's profile.
uniform float3 uOptics;
// Straight alpha.
uniform float4 uTint;
// Highlight along the top and bottom of the rim, the dp it fades over, how
// much a lens's rounded ends darken.
uniform float3 uLight;
// Glow center, glow strength, how far the tint thickens over content much
// darker than it, or when negative much lighter.
uniform float4 uGlow;
// How far the glow reaches, and the share of it spread over the whole pane.
uniform float2 uGlowSpread;
// How much the dark line around the rim darkens the backdrop, how far apart in
// dp the color channels part along the rim, and the frost sigma the rim adds
// at its edge, easing off across the bevel.
uniform float3 uEdge;
// The highlight's share in a faint tail kRimTail long, how far its fall
// round rounded ends goes from the tab bar's to iOS 27 controls', and its
// share added as light rather than screened; then the line at the sides.
uniform float4 uRim;
// CyIME: how opaque the keyboard's own backdrop is where the pane reads it.
uniform float uContent;

const float3 kLuma = float3(0.2126, 0.7152, 0.0722);
const float kGoldenAngle = 2.39996322973;
// 1.2 times the engine's kernel reach of (sigma - 0.5) * sqrt(3) pixels, which
// on a Vogel disc fits its blur best.
const float kFrostReach = 2.07846096908;
// Measured on iOS 27: the width of the dark line in dp and the most it takes
// off a channel of the backdrop.
const float kLine = 0.67;
const float kLineCap = 0.298;
// The most the engine's superellipse corner lies inside a circular one, as a
// share of the radius, with room to spare over the 1.8% it reaches.
const float kCornerGap = 0.03;
// How far in dp a lens's ends darken in from the rim.
const float kEndShade = 3.0;
// How far in dp from the rim the color channels part, and where they start
// to close: iOS parts them nearly as far across a lens's band as at its rim.
const float kPartReach = 10.0;
const float kPartHold = 7.0;
// Measured on iOS 27 controls: how far in dp the highlight's tail reaches.
const float kRimTail = 4.5;
// A frost this wide in dp reads the backdrop's luma once, as a haze does.
const float kSteadyFrost = 8.0;

float4 texel(float2 pixel) {
  return float4(uTexture.eval(clamp(pixel, uClamp.xy, min(uClamp.zw, uSize - 0.5)))) * uContent;
}

float4 sampleGlass(float2 point) {
  return texel((point - uSample.xy) / uSample.zw);
}

float4 frostDisc16(float2 center, float2 reach, float falloff) {
  float decay = exp(-falloff / 16.0);
  float weight = sqrt(decay);
  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
  float2 ray = float2(1.0, 0.0);
  float4 sum = float4(0.0);
  float total = 0.0;
  for (int i = 0; i < 16; i++) {
    float spread = sqrt((float(i) + 0.5) / 16.0);
    sum += texel(center + ray * reach * spread) * weight;
    total += weight;
    weight *= decay;
    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);
  }
  return sum / total;
}

float4 frostDisc32(float2 center, float2 reach, float falloff) {
  float decay = exp(-falloff / 32.0);
  float weight = sqrt(decay);
  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
  float2 ray = float2(1.0, 0.0);
  float4 sum = float4(0.0);
  float total = 0.0;
  for (int i = 0; i < 32; i++) {
    float spread = sqrt((float(i) + 0.5) / 32.0);
    sum += texel(center + ray * reach * spread) * weight;
    total += weight;
    weight *= decay;
    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);
  }
  return sum / total;
}

float4 frostDisc64(float2 center, float2 reach, float falloff) {
  float decay = exp(-falloff / 64.0);
  float weight = sqrt(decay);
  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
  float2 ray = float2(1.0, 0.0);
  float4 sum = float4(0.0);
  float total = 0.0;
  for (int i = 0; i < 64; i++) {
    float spread = sqrt((float(i) + 0.5) / 64.0);
    sum += texel(center + ray * reach * spread) * weight;
    total += weight;
    weight *= decay;
    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);
  }
  return sum / total;
}

// The engine's Gaussian of a frost sigma in device pixels, past what the
// texture holds, read on a Vogel disc.
float4 sampleFrosted(float2 point, float frost) {
  float2 sigma = frost / uSample.zw;
  sigma = sqrt(max(sigma * sigma - uTexels.y * uTexels.y, float2(0.0)));
  float2 reach = max(sigma - 0.5, 0.0) * kFrostReach;
  if (reach.x <= 0.0 || reach.y <= 0.0) {
    return sampleGlass(point);
  }
  float2 ratio = reach / sigma;
  float falloff = 0.5 * ratio.x * ratio.y;
  float2 center = (point - uSample.xy) / uSample.zw;
  float widest = max(sigma.x, sigma.y);
  if (widest <= 2.0) {
    return frostDisc16(center, reach, falloff);
  }
  if (widest <= 3.5) {
    return frostDisc32(center, reach, falloff);
  }
  return frostDisc64(center, reach, falloff);
}

// Its taps sit a frost sigma apart, so that a glyph passing under one point
// does not flicker the tint; a wide frost is blurred enough to read once.
float4 sampleBehind(float2 point) {
  if (uShape.w >= kSteadyFrost) {
    return sampleGlass(point);
  }
  float2 spread = float2(uShape.w, -uShape.w);
  return 0.25 * (sampleGlass(point + spread.xx)
      + sampleGlass(point + spread.yy)
      + sampleGlass(point + spread.xy)
      + sampleGlass(point + spread.yx));
}

float roundedBoxDistance(float2 p, float2 halfSize, float radius) {
  float2 q = abs(p) - halfSize + radius;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 roundedBoxNormal(float2 p, float2 halfSize, float radius) {
  float2 q = abs(p) - halfSize + radius;
  float2 n = float2(0.0, 1.0);
  if (q.x > 0.0 && q.y > 0.0) {
    n = normalize(q);
  } else if (q.x > q.y) {
    n = float2(1.0, 0.0);
  }
  return n * float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

// How far inside one octant of the engine's rounded superellipse a point lies,
// in the octant's frame: a superellipse of half side a up to J, then a
// circular arc. Close to exact only within a few dp of the curve.
float octantInset(float2 point, float a, float4 curve, float2 center) {
  float2 fromCenter = point - center;
  float2 toJoint = curve.yz - center;
  if (toJoint.x * fromCenter.y < toJoint.y * fromCenter.x) {
    return curve.w - length(fromCenter);
  }
  float n = curve.x;
  float2 s = max(point, 0.0) / a;
  float major = max(max(s.x, s.y), 1e-4);
  float norm = major * pow(1.0 + pow(min(s.x, s.y) / major, n), 1.0 / n);
  float2 slope = pow(s / norm, float2(n - 1.0));
  return a * (1.0 - norm) / max(length(slope), 1e-4);
}

// A corner's quadrant lies about the pane's center, as the engine lays it when
// all corners are alike, and its octants part along the diagonal through where
// their arcs meet, half the difference of the sides off center.
float cornerInset(float2 p, float2 halfSize) {
  float4 along = uCurveAlong;
  float4 across = uCurveAcross;
  float4 centers = uCurveCenters;
  float2 q = abs(p);
  if (along.x < 2.0) {
    return min(halfSize.x - q.x, halfSize.y - q.y);
  }
  float c = halfSize.x - halfSize.y;
  if (q.y + c >= q.x) {
    return octantInset(float2(q.x, q.y + c), halfSize.x, along, centers.xy);
  }
  return octantInset(float2(q.y, q.x - c), halfSize.y, across, centers.zw);
}

half4 main(float2 fragCoord) {
  float2 point = uFrame.xy + fragCoord * uFrame.zw;
  float2 halfSize = uShape.xy * 0.5;
  float halfMinor = min(halfSize.x, halfSize.y);
  float2 centered = point - halfSize;
  float inset;
  float2 normal;
  // The inset of the line and the highlight. A pane is clipped to the engine's
  // superellipse, up to half a dp inside its circular corners where a long side
  // runs into them, and they keep to it; the optics read deeper than its inset
  // holds true, so they keep the circles. Only the band the line and the
  // highlight can reach works it out.
  float edge;
  bool lit = uEdge.x > 0.0 || uRim.w > 0.0 || uLight.x > 0.0;
  float traced = lit ? kLine + uLight.y : 0.0;
  float2 side = step(0.0, centered);
  float radius = min(
    mix(
      mix(uCorners.x, uCorners.y, side.x),
      mix(uCorners.w, uCorners.z, side.x),
      side.y
    ),
    halfMinor
  );
  inset = max(-roundedBoxDistance(centered, halfSize, radius), 0.0);
  normal = roundedBoxNormal(centered, halfSize, radius);
  edge = inset;
  if (traced > 0.0 && inset < traced + uTexels.x + kCornerGap * radius) {
    edge = max(cornerInset(centered, halfSize), 0.0);
  }

  bool lens = uOptics.x < 0.0;
  float bevel = max(min(uShape.z, halfMinor * 0.5), 1e-3);
  float rise = 1.0 - clamp(inset / bevel, 0.0, 1.0);
  float2 shift;
  if (lens) {
    // As measured on iOS 27, the raised lens reads outward along its normal
    // all around, by nothing at the rim and its full depth a bevel in, so its
    // outer band draws out what lies right under the rim; the depth then eases
    // off toward the middle.
    shift = normal * -uOptics.x * smoothstep(0.0, bevel, inset)
        * (1.0 - smoothstep(bevel, halfMinor, inset));
  } else {
    // A bevel of circular profile, fitted to small panes: flat where it meets
    // the face and steepening toward the rim, which reads from so far inside
    // that its outermost band shows the content squeezed and mirrored.
    float depth = min(uOptics.x, halfMinor) * (1.0 - sqrt(1.0 - rise * rise));
    shift = -normal * depth;
  }

  float frost = uShape.w + uEdge.z * rise * rise;
  float4 base = sampleFrosted(point + shift, frost);
  if (uEdge.y > 0.0 && inset < kPartReach) {
    // iOS 27 parts red from blue along the normal's mirror image, across it
    // at the sides and along it at the corners. Along the top and bottom the
    // parting narrows from the rim to what it holds around the ends. Over
    // clear glass a channel shows past the others, so the cover is the widest
    // of them.
    float2 part = normal.yx * 0.5 * uEdge.y
        * (0.6 + 0.4 * abs(normal.y) * max(1.0 - inset / kPartHold, 0.0))
        * (1.0 - smoothstep(kPartHold, kPartReach, inset));
    float4 red = sampleFrosted(point + shift + part, frost);
    float4 blue = sampleFrosted(point + shift - part, frost);
    base = float4(red.r, base.g, blue.b, max(base.a, max(red.a, blue.a)));
  }
  float alpha = base.a;
  float3 color = base.rgb / max(alpha, 1e-4);
  color = mix(float3(dot(color, kLuma)), color, uOptics.y) * uOptics.z;
  color *= 1.0 - uLight.z * abs(normal.x) * exp(-inset / kEndShade);

  float clash = 0.0;
  if (uGlow.w != 0.0) {
    float4 behind = sampleBehind(halfSize)
        + sampleBehind(halfSize * float2(0.4, 0.6))
        + sampleBehind(halfSize * float2(1.6, 0.6))
        + sampleBehind(halfSize * float2(0.4, 1.4))
        + sampleBehind(halfSize * float2(1.6, 1.4));
    behind = behind * 0.2;
    float behindLuma = dot(behind.rgb / max(behind.a, 1e-4), kLuma);
    clash = clamp(
      (sign(uGlow.w) * (dot(uTint.rgb, kLuma) - behindLuma) - 0.3) / 0.5,
      0.0,
      1.0
    );
  }
  float cover = uTint.a + (1.0 - uTint.a) * clash * abs(uGlow.w);
  float coverage = alpha * (1.0 - cover) + cover;
  float3 glass = color * alpha * (1.0 - cover) + uTint.rgb * cover;

  // iOS lights the top and bottom of its glass, just inside the line, and on
  // its tab bar fades that around the rounded ends with the normal's upward
  // part squared, out only at their tips; its controls dim far faster. All
  // screened toward white, a pale tint would clip into a flat white ring.
  // The tail reads the circular inset, as it reaches past where the edge's is
  // worked out, and moves too slowly for the difference to show.
  float within = max(edge - kLine, 0.0);
  float facing;
  if (lens) {
    facing = smoothstep(0.65, 0.95, abs(normal.y));
  } else {
    float rise2 = normal.y * normal.y;
    float rise4 = rise2 * rise2;
    float rise16 = rise4 * rise4 * rise4 * rise4;
    facing = mix(rise2, 0.5 * rise2 * abs(normal.y) * (1.0 + rise16), uRim.y);
  }
  float band = clamp(1.0 - within / max(uLight.y, 1e-3), 0.0, 1.0);
  float tail = clamp(1.0 - max(inset - kLine, 0.0) / kRimTail, 0.0, 1.0);
  float shine = min(uLight.x * facing * mix(band, tail, uRim.x), 1.0);
  glass += (float3(coverage) - (1.0 - uRim.z) * glass) * shine;

  // iOS 27 outlines its glass with a dark line, which UIKit draws just outside
  // the bounds, over the backdrop, where the pane cannot draw, so it takes the
  // outermost pixels here. iOS draws it darkest at the sides; the dock keeps
  // it as faint all around as along the top, as its darker sides read as
  // brackets.
  float line = clamp((kLine - edge) / uTexels.x + 0.5, 0.0, 1.0);
  float across = abs(normal.x);
  float darken = mix(uEdge.x, uRim.w, across * across * across);
  if ((uEdge.x > 0.0 || uRim.w > 0.0) && line > 0.0) {
    float4 under = sampleGlass(point);
    float3 dark = max(under.rgb - kLineCap * under.a, 0.22 * under.rgb);
    glass = mix(glass, mix(under.rgb, dark, darken), line);
    coverage = mix(coverage, under.a, line);
  }

  float2 toGlow = point - uGlow.xy;
  float reach = max(uGlowSpread.x * uGlowSpread.x, 1.0);
  glass += uGlow.z * coverage
      * mix(exp(-dot(toGlow, toGlow) / reach), 1.0, uGlowSpread.y);

  return half4(float4(min(glass, float3(coverage)), coverage));
}
"""
