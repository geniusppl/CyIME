package com.kingzcheung.xime.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 液态玻璃质感 · 移植自 roseforljh/KunBox（MIT），app/src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt
 * 的 liquidGlassCrystalSurface / liquidGlassPanelBrush / hollowShadow，以及 ui/components/AppNavBar.kt 的
 * LiquidGlassSelectedIndicator（selected = true、accented = false）。
 * Copyright (c) 2024 SingBox for Android Contributors，MIT License，全文见 assets/NOTICE.txt。
 *
 * KunBox 的质感大部分是纯 Canvas 绘制、不需要背后的像素：左上来的焦散径向高光、右下的深度暗角、0.9dp 外亮边、
 * 3.2dp 内亮边、迎光侧浮雕边、按尺寸（/64dp）缩放的光学强度，外加只画在形状外面的软阴影（hollowShadow）。
 * 输入法读不到背后应用的像素，正好全部可用。
 *
 * 和原版不同的地方（用户 11-09：要 KunBox 的「液态玻璃质感」，按压动效保留）：
 * - KunBox 的控件底色是 84%/88% 的不透明白/黑（给不透明面板用），iOS 透出模式下按键要看得见后面，
 *   [KunBoxGlassRole.KEY] 的底色降到淡白渐变，靠焦散、浮雕边和外亮边读成玻璃；
 * - 按键是 accented 的浮雕边，再加一道减淡的外亮边，透出背景上轮廓才清楚；
 * - [boost] 随按压弹簧加亮焦散（静止 0）。
 */

/** 移植的三种用法：按键键帽、工具栏滑块（KunBox 选中指示器）、候选栏胶囊和卡片（KunBox 导航胶囊）。 */
internal enum class KunBoxGlassRole { KEY, SELECTED, PANEL }

/** 按尺寸缓存的一片玻璃：路径和各道渐变。只在 drawWithCache 里建。 */
internal class KunBoxCrystal(
    val path: Path,
    val visible: Boolean,
    val accented: Boolean,
    val panelBrush: Brush,
    val causticBrush: Brush,
    val depthBrush: Brush,
    val rimBrush: Brush,
    val rimWidth: Float,
    val outerRimBrush: Brush,
    val outerRimWidth: Float,
    val outerRimAlpha: Float,
    val specularBrush: Brush?,
    val specularY: Float,
    val specularStart: Float,
    val specularEnd: Float,
    val specularWidth: Float,
    val highlightBlend: BlendMode,
)

/** 圆角矩形（[radiusPx] 大于半高就是胶囊）上的 KunBox 晶体表面。[surface] 是主题表面色（面板渐变用）。 */
internal fun CacheDrawScope.kunBoxCrystal(
    radiusPx: Float, dark: Boolean, role: KunBoxGlassRole, surface: Color,
): KunBoxCrystal {
    val w = size.width
    val h = size.height
    val corner = radiusPx.coerceIn(0f, size.minDimension / 2f)
    val path = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(corner))) }
    val accented = role == KunBoxGlassRole.KEY
    val selected = role == KunBoxGlassRole.SELECTED
    val maxDimension = maxOf(w, h).coerceAtLeast(1f)
    val opticsScale = (size.minDimension / 64.dp.toPx()).coerceIn(0.35f, 1f)
    val rimAlphaScale = 0.72f + (0.28f * opticsScale)
    val outerRimWidth = 0.9.dp.toPx() * opticsScale
    val innerRimWidth = 3.2.dp.toPx() * opticsScale
    val embossedRimWidth = (if (dark) 2.6.dp else 2.dp).toPx() * opticsScale
    val highlight = Color.White
    val causticAlpha = when (role) {
        // KunBox 不透明控件是 .16/.10；透出的键帽底色薄，焦散按非强调档（.17/.29）往下收一点。
        KunBoxGlassRole.KEY -> if (dark) .17f else .24f
        KunBoxGlassRole.SELECTED -> if (dark) .24f else .38f
        KunBoxGlassRole.PANEL -> if (dark) .17f else .29f
    } * opticsScale
    val causticMid = when (role) {
        KunBoxGlassRole.KEY -> (if (dark) .05f else .06f) * opticsScale
        else -> (if (dark) .045f else .075f) * opticsScale
    }
    val causticBrush = Brush.radialGradient(
        colors = listOf(highlight.copy(alpha = causticAlpha), highlight.copy(alpha = causticMid), Color.Transparent),
        center = Offset(w * 0.16f, -h * 0.08f),
        radius = maxDimension * 0.74f,
    )
    val depthBrush = Brush.radialGradient(
        colors = listOf(Color.Black.copy(alpha = if (dark) 0.12f else 0.045f), Color.Transparent),
        center = Offset(w * 1.03f, h * 1.08f),
        radius = maxDimension * 0.72f,
    )
    val outerRimBrush = Brush.linearGradient(
        0f to Color.White.copy(alpha = (if (dark) 0.62f else 0.96f) * rimAlphaScale),
        0.28f to Color.White.copy(alpha = (if (dark) 0.28f else 0.56f) * rimAlphaScale),
        0.56f to Color.White.copy(alpha = (if (dark) 0.10f else 0.24f) * rimAlphaScale),
        0.78f to Color.White.copy(alpha = (if (dark) 0.16f else 0.34f) * rimAlphaScale),
        1f to Color.White.copy(alpha = (if (dark) 0.42f else 0.72f) * rimAlphaScale),
        start = Offset(-w * 0.08f, -h * 0.16f),
        end = Offset(w * 1.06f, h * 1.12f),
    )
    val innerRimBrush = Brush.linearGradient(
        0f to Color.White.copy(alpha = (if (dark) 0.26f else 0.48f) * opticsScale),
        0.38f to Color.Transparent,
        0.72f to Color.White.copy(alpha = (if (dark) 0.10f else 0.20f) * opticsScale),
        1f to Color.White.copy(alpha = (if (dark) 0.12f else 0.28f) * opticsScale),
        start = Offset.Zero,
        end = Offset(w, h),
    )
    // 只保留迎光侧高光，底部深度由 hollowShadow 负责，避免连续暗边呈现为分割线。
    val embossedRimBrush = if (dark) Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.24f * opticsScale),
        0.32f to Color.White.copy(alpha = 0.05f * opticsScale),
        0.66f to Color.White.copy(alpha = 0f),
        1f to Color.White.copy(alpha = 0f),
        start = Offset.Zero, end = Offset(w, h),
    ) else Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.92f * opticsScale),
        0.34f to Color.White.copy(alpha = 0.28f * opticsScale),
        0.66f to Color.White.copy(alpha = 0f),
        1f to Color.White.copy(alpha = 0f),
        start = Offset.Zero, end = Offset(w, h),
    )
    val specularBrush = if (accented) null else Brush.horizontalGradient(
        0f to Color.Transparent,
        0.18f to Color.White.copy(alpha = (if (dark) 0.20f else 0.42f) * opticsScale),
        0.52f to Color.White.copy(alpha = (if (dark) 0.52f else 0.90f) * opticsScale),
        1f to Color.Transparent,
        startX = w * 0.06f,
        endX = w * 0.70f,
    )
    return KunBoxCrystal(
        path = path,
        visible = w >= 1f && h >= 1f,
        accented = accented,
        panelBrush = kunBoxPanelBrush(role, dark, surface, w, h),
        causticBrush = causticBrush,
        depthBrush = depthBrush,
        rimBrush = if (accented) embossedRimBrush else innerRimBrush,
        rimWidth = if (accented) embossedRimWidth else innerRimWidth,
        outerRimBrush = outerRimBrush,
        outerRimWidth = outerRimWidth,
        // 键帽：透出背景上补一道减淡的外亮边（KunBox 的强调控件没有）。
        outerRimAlpha = if (accented) .6f else 1f,
        specularBrush = specularBrush,
        specularY = 1.3.dp.toPx(),
        specularStart = w * 0.08f,
        specularEnd = w * 0.68f,
        specularWidth = 1.4.dp.toPx(),
        // 常规透明合成避免离屏图层改变中性色（KunBox：强调控件 SrcOver，其余 Screen）。
        highlightBlend = if (accented) BlendMode.SrcOver else BlendMode.Screen,
    )
}

/**
 * KunBox liquidGlassPanelBrush。PANEL / SELECTED 用 backdropVisible 档；KEY 是为透出模式调过的淡白（KunBox 原值
 * 76%~90% 白/黑是给不透明面板的，键帽上面已有 frostedKeyColor 的淡白一层，这里只再加一点上亮下淡的渐变）。
 */
private fun kunBoxPanelBrush(role: KunBoxGlassRole, dark: Boolean, surface: Color, w: Float, h: Float): Brush {
    val colors = when {
        role == KunBoxGlassRole.KEY && dark -> listOf(
            Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.035f),
            Color.Transparent, Color.White.copy(alpha = 0.05f))
        role == KunBoxGlassRole.KEY -> listOf(
            Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.12f),
            Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.14f))
        // 工具栏滑块：KunBox 的指示器在不透明导航胶囊上，透出的工具栏上再垫一层淡白才认得出。
        role == KunBoxGlassRole.SELECTED && dark -> listOf(
            Color.White.copy(alpha = 0.12f), surface.copy(alpha = 0.035f),
            Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.09f))
        role == KunBoxGlassRole.SELECTED -> listOf(
            Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.20f),
            Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.22f))
        dark -> listOf(
            Color.White.copy(alpha = 0.025f), surface.copy(alpha = 0.035f),
            Color.Transparent, Color.White.copy(alpha = 0.012f))
        else -> listOf(
            Color.White.copy(alpha = 0.08f), surface.copy(alpha = 0.035f),
            Color.Transparent, Color.White.copy(alpha = 0.025f))
    }
    return Brush.linearGradient(colors, start = Offset.Zero, end = Offset(w, h))
}

/** 晶体表面里层：面板渐变、焦散、深度、浮雕边/内亮边。画在底色之上、内容之下。 */
internal fun DrawScope.drawKunBoxCrystalUnder(c: KunBoxCrystal, alpha: Float = 1f, boost: Float = 0f) {
    if (!c.visible || alpha <= .001f) return
    clipPath(c.path) {
        drawPath(c.path, c.panelBrush, alpha = alpha)
        drawRect(c.causticBrush, alpha = alpha, blendMode = c.highlightBlend)
        // 按压：焦散再叠一层，随弹簧亮起、回弹时退去。
        if (boost > .001f) drawRect(c.causticBrush, alpha = (alpha * boost).coerceIn(0f, 1f), blendMode = c.highlightBlend)
        drawRect(c.depthBrush, alpha = alpha)
        drawPath(c.path, c.rimBrush, alpha = alpha, style = Stroke(width = c.rimWidth))
    }
}

/** 晶体表面外层：上沿高光线（非强调）、0.9dp 外亮边。画在内容之上。 */
internal fun DrawScope.drawKunBoxCrystalOver(c: KunBoxCrystal, alpha: Float = 1f) {
    if (!c.visible || alpha <= .001f) return
    val specular = c.specularBrush
    if (specular != null) clipPath(c.path) {
        drawLine(specular, start = Offset(c.specularStart, c.specularY), end = Offset(c.specularEnd, c.specularY),
            strokeWidth = c.specularWidth, cap = StrokeCap.Round, alpha = alpha, blendMode = c.highlightBlend)
    }
    drawPath(c.path, c.outerRimBrush, alpha = (alpha * c.outerRimAlpha).coerceIn(0f, 1f), style = Stroke(width = c.outerRimWidth))
}

/**
 * KunBox hollowShadow：只画在圆角矩形外面的软阴影（形状里面挖空，透出的键面不会被压暗）。
 * 必须放在裁剪之前（键帽的 clip 之外）。
 */
internal fun Modifier.kunBoxHollowShadow(
    radius: Dp,
    color: Color = Color.Black,
    alpha: Float = 0.08f,
    blurRadius: Dp = 12.dp,
    offsetY: Dp = 6.dp,
): Modifier = this.drawWithCache {
    val corner = radius.toPx().coerceIn(0f, size.minDimension / 2f)
    val path = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(corner))) }
    val blurRadiusPx = blurRadius.toPx()
    val offsetYPx = offsetY.toPx()
    val paint = Paint().apply {
        this.color = color.copy(alpha = alpha)
        if (blurRadiusPx > 0f) {
            asFrameworkPaint().maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
        }
    }
    onDrawBehind {
        if (size.width < 1f || size.height < 1f) return@onDrawBehind
        clipPath(path, clipOp = ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(0f, offsetYPx)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }
    }
}
