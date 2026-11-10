package com.kingzcheung.xime.ui.theme

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

/*
 * iOS 液态玻璃第五版（用户 11-10：Nekogram 最新版的玻璃质感和果冻效果很好，键盘每个键都要这样）。
 *
 * 整套照 geniusppl/Nekogram（smart-download 分支，提交 cb46ab29f）移植，Nekogram 是 Telegram 的 GPL 分支，
 * 它的透镜又移植自 chen08209/prism_ui（FlClash 的界面库，GPL-3.0）；CyIME 同为 GPL-3.0：
 * - 着色器 [NEKO_FLCLASH_GLASS]：Nekogram res/raw/neko_flclash_glass.agsl（prism_ui shaders/liquid_glass.frag
 *   的 AGSL 逐行移植）原样搬来，只在 texel() 乘了一个 uContent（键盘底片的不透明度，Nekogram 等于 1）。
 * - 参数 [drawNekoGlass]：FlClashLens.drawGlass 的 LiquidGlassStyle.lens(...).scale(a)——凸透镜折射 -4.3dp、
 *   斜面 5.5dp、色散 6.5dp、上下沿高光 .6（深色 .3）宽 1.1dp、两端变暗 .045、外圈暗线 .42、边缘雾化 1.5dp。
 *   Nekogram 用 RenderNode + RenderEffect 取样底栏的模糊背景；这里同一个着色器当画笔直接画在键帽上，
 *   取样键盘自己的背景（共用背景图 / 主题渐变 / 底色，[LensPaintCache.input]），全进程共用一个实例。
 * - 抬起阴影 [drawNekoLiftShadow]：FlClashLens.drawShadow（prism_ui _lensShadows：模糊 5dp、下移 8dp、
 *   .055 × 抬起，深色 ×3，五层手叠）。
 * - 静止材质 [nekoCardShadow]、[drawNekoSheen]、[drawNekoRim]、[drawNekoPill]：GlassDialogCards.drawCard /
 *   NekoGlass.drawSheen / drawRim / drawPill（键外软阴影 7dp 下移 2dp、上部 60% 白色光泽、1dp 上亮中淡下微亮的边、
 *   选中胶囊的强调色淡底）。
 */

/** prism_ui navigation_dock.dart _lensGrowth：抬起时透镜四周各长 8dp（Nekogram FlClashLens.LENS_GROWTH_DP）。 */
internal const val NEKO_LENS_GROWTH_DP = 8f
/** prism_ui _lensMagnify（Nekogram FlClashLens.LENS_MAGNIFY）。 */
internal const val NEKO_LENS_MAGNIFY = .16f

/**
 * 按键静止时透镜的强度（Nekogram 的底栏透镜只在抬起时出现，a = lift；按键每个都要有玻璃，
 * 所以静止 .75，按下抬起到 1）。
 */
internal const val NEKO_KEY_REST = .75f

/** GlassDialogCards.drawCard 的填充：浅色 64%、深色 56% 的底色盖在背景上，透出背景的那一份。 */
internal fun nekoCardBackdropShare(dark: Boolean): Float = if (dark) 1f - .56f else 1f - .64f
/** 透出模式下键盘底片的不透明度：Nekogram 开液态玻璃时把底栏玻璃底色压到 0x73。 */
internal const val NEKO_SEE_THROUGH_CONTENT = 0x73 / 255f

/**
 * 画一片 Nekogram 玻璃（FlClashLens.drawGlass 同一组参数，按 [a] 缩放），铺满当前尺寸。
 * [input] 是已经对到当前坐标的底片；[content] 是底片的不透明度。返回 false 表示着色器不可用（Android 12 及以下
 * 或编译失败），调用方画 [drawNekoGlassFallback]。
 */
internal fun DrawScope.drawNekoGlass(
    input: Shader, radiusPx: Float, a: Float, dark: Boolean, content: Float, alpha: Float, paint: Paint,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (size.width < 1f || size.height < 1f || alpha <= .001f) return true
    return drawNekoGlass33(input, radiusPx, a, dark, content, alpha, paint)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawNekoGlass33(
    input: Shader, radiusPx: Float, a: Float, dark: Boolean, content: Float, alpha: Float, paint: Paint,
): Boolean {
    val s = NekoFlClashShader.shared ?: return false
    val l = a.coerceIn(0f, 1f)
    val d = density
    // FlClashLens.setUniforms：玻璃点（dp，从透镜左上角起）= fragCoord / density；画笔坐标就是键帽自己的像素。
    s.setInputShader("img", input)
    s.setFloatUniform("uSize", 1e5f, 1e5f)
    s.setFloatUniform("uFrame", 0f, 0f, 1f / d, 1f / d)
    s.setFloatUniform("uSample", 0f, 0f, 1f / d, 1f / d)
    // 底片铺满整个键盘，折射和色散读到键外也有东西，不必像 RenderNode 那样夹在节点里。
    s.setFloatUniform("uClamp", -1e5f, -1e5f, 1e5f, 1e5f)
    s.setFloatUniform("uTexels", 1f / d, 0f)
    val rd = min(radiusPx, min(size.width, size.height) / 2f) / d
    // LiquidGlassStyle.lens(colorScheme).scale(lift)
    s.setFloatUniform("uShape", size.width / d, size.height / d, /* bevel */ 5.5f, /* blur */ 0f)
    s.setFloatUniform("uCorners", rd, rd, rd, rd)
    s.setFloatUniform("uOptics", /* refraction */ -4.3f * l, /* saturation */ 1f,
        /* brightness */ 1f + ((if (dark) 1.33f else 1f) - 1f) * l)
    s.setFloatUniform("uTint", 0f, 0f, 0f, 0f)
    s.setFloatUniform("uLight", /* rim */ (if (dark) .3f else .6f) * l, /* rimWidth */ 1.1f, /* endShade */ .045f * l)
    s.setFloatUniform("uEdge", /* hairline */ .42f * l, /* dispersion */ 6.5f * l, /* rimBlur */ 1.5f * l)
    s.setFloatUniform("uRim", /* rimTail */ 0f, /* rimFocus */ 0f, /* rimAdded */ 0f, /* hairlineSide */ .42f * l)
    s.setFloatUniform("uContent", content.coerceIn(0f, 1f))
    paint.shader = s
    paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    paint.shader = null
    paint.alpha = 255
    return true
}

/** NekoGlass.Painter 的退路：底片按 [content] 铺一层，再画 1dp 高光边。 */
internal fun DrawScope.drawNekoGlassFallback(
    input: Shader, radiusPx: Float, dark: Boolean, content: Float, alpha: Float, paint: Paint,
) {
    if (size.width < 1f || size.height < 1f || alpha <= .001f) return
    val r = min(radiusPx, size.minDimension / 2f)
    paint.shader = input
    paint.alpha = (255 * (content * alpha).coerceIn(0f, 1f)).toInt()
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
    paint.shader = null
    paint.alpha = 255
    drawNekoRim(r, dark, alpha)
}

/** NekoGlass.drawSheen：上部 60% 一层白色光泽，浅色 0x2C、深色 0x16。 */
internal fun DrawScope.drawNekoSheen(radiusPx: Float, dark: Boolean, alpha: Float) {
    val a = (if (dark) 0x16 else 0x2C) / 255f * alpha.coerceIn(0f, 1f)
    if (a <= 0f) return
    val r = min(radiusPx, size.minDimension / 2f)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = a), Color.White.copy(alpha = 0f)),
        startY = 0f, endY = size.height * .6f), cornerRadius = CornerRadius(r))
}

/** NekoGlass.drawRim：1dp 描边，上沿亮（浅 0xC0 / 深 0x70）、55% 处淡（0x30 / 0x14）、下沿微亮（0x70 / 0x38）。 */
internal fun DrawScope.drawNekoRim(radiusPx: Float, dark: Boolean, alpha: Float) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0f) return
    val a1 = (if (dark) 0x70 else 0xC0) / 255f
    val a2 = (if (dark) 0x14 else 0x30) / 255f
    val a3 = (if (dark) 0x38 else 0x70) / 255f
    val stroke = 1f * density
    val inset = stroke / 2f
    val r = max(0f, min(radiusPx, size.minDimension / 2f) - inset)
    drawRoundRect(Brush.verticalGradient(
        0f to Color.White.copy(alpha = a1), .55f to Color.White.copy(alpha = a2), 1f to Color.White.copy(alpha = a3),
        startY = 0f, endY = max(1f, size.height)),
        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(r), style = Stroke(stroke), alpha = a)
}

/** NekoGlass.drawPill：选中胶囊静止时的强调色淡底（.13 + .07 × lift）、抬起时一层白、光泽、高光边。 */
internal fun DrawScope.drawNekoPill(radiusPx: Float, accent: Color, dark: Boolean, lift: Float, alpha: Float) {
    val l = lift.coerceIn(0f, 1f)
    val a = alpha.coerceIn(0f, 1f)
    if (a <= .001f) return
    val r = min(radiusPx, size.minDimension / 2f)
    drawRoundRect(accent.copy(alpha = accent.alpha * (.13f + .07f * l) * a), cornerRadius = CornerRadius(r))
    if (l > .01f) drawRoundRect(Color.White.copy(alpha = (if (dark) 0x14 else 0x30) / 255f * l * a), cornerRadius = CornerRadius(r))
    drawNekoSheen(r, dark, a)
    drawNekoRim(r, dark, a * (.75f + .25f * l))
}

/**
 * FlClashLens.drawShadow（prism_ui _lensShadows）：一道阴影，模糊 5dp、下移 8dp × 抬起，透明度 .055（深色三倍）× 抬起。
 * 硬件画布不吃 mask filter，按原版手叠五层圆角矩形。
 */
internal fun DrawScope.drawNekoLiftShadow(radiusPx: Float, lift: Float, dark: Boolean) {
    val l = lift.coerceIn(0f, 1f)
    if (l <= 0f) return
    val alpha = .055f * (if (dark) 3f else 1f) * l
    val blur = 5f * density
    val down = 8f * density * l
    val steps = 5
    for (i in 0 until steps) {
        val t = (i + .5f) / steps
        val grow = blur * (t - .5f) * 2f
        val w = size.width + grow * 2f
        val h = size.height + grow * 2f
        if (w <= 0f || h <= 0f) continue
        val r = max(0f, min(radiusPx + grow, h / 2f))
        drawRoundRect(Color.Black.copy(alpha = (alpha / steps * 1.6f).coerceIn(0f, 1f)),
            topLeft = Offset(-grow, -grow + down), size = Size(w, h), cornerRadius = CornerRadius(r))
    }
}

/**
 * GlassDialogCards.drawCard 的阴影：只画在键外（键里挖空，透明键面不被压暗），模糊 7dp、下移 2dp，
 * 浅色 0x24、深色 0x70。用 setShadowLayer（硬件画布从 Android 9 起支持）。
 */
internal fun Modifier.nekoCardShadow(radius: Dp, dark: Boolean): Modifier = this.drawWithCache {
    val corner = radius.toPx().coerceIn(0f, size.minDimension / 2f)
    val path = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(corner))) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (if (dark) Color.Black else Color.White).toArgb()
        setShadowLayer(7f * density, 0f, 2f * density, android.graphics.Color.argb(if (dark) 0x70 else 0x24, 0, 0, 0))
    }
    onDrawBehind {
        if (size.width < 1f || size.height < 1f) return@onDrawBehind
        clipPath(path, clipOp = ClipOp.Difference) {
            drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, paint) }
        }
    }
}

/** 全进程编译一次，各键、工具栏滑块共用（每次画前设参数，系统在录制时取快照）。编译失败为 null。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object NekoFlClashShader {
    val shared: RuntimeShader? by lazy {
        try { RuntimeShader(NEKO_FLCLASH_GLASS) } catch (e: Throwable) {
            android.util.Log.w("CyImeNekoGlass", "neko flclash glass shader failed to compile", e)
            null
        }
    }
}

/** Nekogram res/raw/neko_flclash_glass.agsl（提交 cb46ab29f），只加了 uContent。 */
internal const val NEKO_FLCLASH_GLASS = """
// Nekogram "Liquid Glass" for the bottom main tabs bar.
//
// A line-by-line AGSL port of shaders/liquid_glass.frag from prism_ui
// (https://github.com/chen08209/prism_ui, the UI kit of FlClash by chen08209),
// licensed under GPL-3.0 like Nekogram. Comments are kept from the original.
//
// Port notes:
//  - FlutterFragCoord() -> fragCoord, texture(uTexture, uv) -> img.eval(pixel).
//  - The texture is the RenderNode this effect is set on, in its own pixels.
//  - Only the rounded-rectangle (pill) path of the original is kept: no goo,
//    profile outline or menu shine, no haze, veil or touch glow (the tab bar's
//    lens uses none of them). The engine's superellipse corners are taken as
//    circles, so the line and highlight read the circular inset.
//  - Flutter clips the pane to its shape; a RenderEffect can not, so the
//    result is multiplied by the shape's anti-aliased coverage (uMask).

uniform shader img;

// The texture's size in pixels.
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
// Refraction depth at the rim, saturation, brightness. A negative depth makes
// a raised lens, which bends outward on UIKit's profile.
uniform float3 uOptics;
// Straight alpha.
uniform float4 uTint;
// Highlight along the top and bottom of the rim, the dp it fades over, how
// much a lens's rounded ends darken.
uniform float3 uLight;
// How much the dark line around the rim darkens the backdrop, how far apart in
// dp the color channels part along the rim, and the frost sigma the rim adds
// at its edge, easing off across the bevel.
uniform float3 uEdge;
// The highlight's share in a faint tail kRimTail long, how far its fall
// round rounded ends goes from the tab bar's to iOS 27 controls', and its
// share added as light rather than screened; then the line at the sides.
uniform float4 uRim;
// CyIME port: how opaque the sampled keyboard background is (1 = as Nekogram).
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
// How far in dp a lens's ends darken in from the rim.
const float kEndShade = 3.0;
// How far in dp from the rim the color channels part, and where they start
// to close: iOS parts them nearly as far across a lens's band as at its rim.
const float kPartReach = 10.0;
const float kPartHold = 7.0;
// Measured on iOS 27 controls: how far in dp the highlight's tail reaches.
const float kRimTail = 4.5;

float4 texel(float2 pixel) {
  return float4(img.eval(clamp(pixel, uClamp.xy, min(uClamp.zw, uSize - 0.5)))) * uContent;
}

float4 sampleGlass(float2 point) {
  return texel((point - uSample.xy) / uSample.zw);
}

// FROST_DISC(taps) of the original, with the tap count as a bound.
float4 frostDisc(float2 center, float2 reach, float falloff, int taps) {
  float count = float(taps);
  float decay = exp(-falloff / count);
  float weight = sqrt(decay);
  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
  float2 ray = float2(1.0, 0.0);
  float4 sum = float4(0.0);
  float total = 0.0;
  for (int i = 0; i < 64; i++) {
    if (i >= taps) {
      break;
    }
    float spread = sqrt((float(i) + 0.5) / count);
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
    return frostDisc(center, reach, falloff, 16);
  }
  if (widest <= 3.5) {
    return frostDisc(center, reach, falloff, 32);
  }
  return frostDisc(center, reach, falloff, 64);
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

half4 main(float2 fragCoord) {
  float2 point = uFrame.xy + fragCoord * uFrame.zw;
  float2 halfSize = uShape.xy * 0.5;
  float halfMinor = min(halfSize.x, halfSize.y);
  float2 centered = point - halfSize;

  float2 side = step(0.0, centered);
  float radius = min(
    mix(
      mix(uCorners.x, uCorners.y, side.x),
      mix(uCorners.w, uCorners.z, side.x),
      side.y
    ),
    halfMinor
  );
  float dist = roundedBoxDistance(centered, halfSize, radius);
  // The clip Flutter puts round the pane.
  float mask = clamp(0.5 - dist / uTexels.x, 0.0, 1.0);
  if (mask <= 0.0) {
    return half4(0.0);
  }
  float inset = max(-dist, 0.0);
  float2 normal = roundedBoxNormal(centered, halfSize, radius);
  float edge = inset;

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

  float cover = uTint.a;
  float coverage = alpha * (1.0 - cover) + cover;
  float3 glass = color * alpha * (1.0 - cover) + uTint.rgb * cover;

  // iOS lights the top and bottom of its glass, just inside the line, and on
  // its tab bar fades that around the rounded ends with the normal's upward
  // part squared, out only at their tips; its controls dim far faster. All
  // screened toward white, a pale tint would clip into a flat white ring.
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

  // iOS 27 outlines its glass with a dark line, drawn just outside the bounds,
  // over the backdrop, so it takes the outermost pixels here.
  float line = clamp((kLine - edge) / uTexels.x + 0.5, 0.0, 1.0);
  float across = abs(normal.x);
  float darken = mix(uEdge.x, uRim.w, across * across * across);
  if ((uEdge.x > 0.0 || uRim.w > 0.0) && line > 0.0) {
    float4 under = sampleGlass(point);
    float3 dark = max(under.rgb - kLineCap * under.a, 0.22 * under.rgb);
    glass = mix(glass, mix(under.rgb, dark, darken), line);
    coverage = mix(coverage, under.a, line);
  }

  return half4(float4(min(glass, float3(coverage)), coverage) * mask);
}
"""
