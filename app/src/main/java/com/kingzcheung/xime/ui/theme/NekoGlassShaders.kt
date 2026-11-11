package com.kingzcheung.xime.ui.theme

/*
 * Nekogram（tw.nekomimi.nekogram，GPL-3.0）液态玻璃的两个着色器，逐字照搬自 res/raw：
 * - neko_liquid_glass_shader.agsl：静止玻璃（KunBox roseforljh/KunBox（MIT）的光学 + 镜面边），文件夹标签栏/按键静止时用；
 * - neko_flclash_glass.agsl：抬起的透镜，chen08209/prism_ui（FlClash 的界面库，GPL-3.0）shaders/liquid_glass.frag 的 AGSL 移植。
 * 不要改动内容：调参在 NekoGlass.kt 里按 Nekogram 的 Java 设 uniform。
 */

internal const val NEKO_LIQUID_GLASS_SHADER = """
// Nekogram "Liquid Glass" optics (used only when Neko settings -> Appearance -> Liquid Glass is on).
// Lens refraction (circle-mapped edge displacement), vibrancy and a specular rim, tuned after
// KunBox (https://github.com/roseforljh/KunBox, MIT; Kyant backdrop: vibrancy + blur + lens)
// and prism_ui's liquid_glass.frag (https://github.com/chen08209/prism_ui, GPL-3.0).
// Uniform names are shared with liquid_glass_shader.agsl so LiquidGlassEffect can drive both.
uniform shader img;

uniform float2 resolution;
uniform float2 center;
uniform float2 size;
uniform float4 radius;
uniform float thickness;
uniform float refract_index;
uniform float refract_intensity;
uniform float4 foreground_color_premultiplied;

uniform float lens_height;
uniform float lens_amount;
uniform float vibrancy;
uniform float highlight;
uniform float rim_width;

float sdfRect(float2 p, float4 r) {
  r.xy = (p.x > 0.0) ? r.xy : r.zw;
  r.x  = (p.y > 0.0) ? r.x  : r.y;
  float2 q = abs(p) - size + r.x;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r.x;
}

half4 srcOver(half4 src, half4 dst) {
  return half4(src.rgb + dst.rgb * (1.0 - src.a), src.a + (1.0 - src.a) * dst.a);
}

half4 main(float2 fragCoord) {
  float2 p = fragCoord - center;
  float sd = sdfRect(p, radius);
  float2 uv = fragCoord;
  float edge = 0.0;
  float2 n = float2(0.0, -1.0);
  // keeps every uniform referenced (the Java side sets all of them)
  float guard = (thickness + refract_index + refract_intensity + resolution.x) * 0.0;
  if (sd < 0.0) {
    float sdX = sdfRect(p + float2(1.0, 0.0), radius);
    float sdY = sdfRect(p + float2(0.0, 1.0), radius);
    float2 g = float2(sdX - sd, sdY - sd);
    float gl = length(g);
    n = gl > 0.0001 ? g / gl : float2(0.0, -1.0);
    float depth = -sd;
    if (depth < lens_height && lens_height > 0.0) {
      float x = 1.0 - depth / lens_height;
      float circle = 1.0 - sqrt(max(1.0 - x * x, 0.0));
      uv -= n * circle * lens_amount;
      edge = x;
    }
  }
  uv += guard;

  half4 c = img.eval(uv);

  // vibrancy: saturation boost, kept inside the premultiplied range
  half lum = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
  c.rgb = clamp(mix(half3(lum), c.rgb, half(vibrancy)), half3(0.0), half3(c.a));

  c = srcOver(half4(foreground_color_premultiplied), c);

  if (sd < 0.0) {
    // soft inner glow along the lens band
    float glow = highlight * 0.10 * edge * edge;
    // specular rim, lit from the top-left and (weaker) from the bottom-right
    float rim = rim_width > 0.0 ? clamp(1.0 + sd / rim_width, 0.0, 1.0) : 0.0;
    float facing = dot(n, normalize(float2(-0.6, -1.0)));
    float light = 0.35 + 0.65 * max(facing, 0.0) + 0.35 * max(-facing, 0.0);
    half h = half(clamp(highlight * rim * light + glow, 0.0, 1.0));
    c = srcOver(half4(h, h, h, h), c);
  }
  return c;
}
"""

internal const val NEKO_FLCLASH_GLASS_SHADER = """
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
  return float4(img.eval(clamp(pixel, uClamp.xy, min(uClamp.zw, uSize - 0.5))));
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
