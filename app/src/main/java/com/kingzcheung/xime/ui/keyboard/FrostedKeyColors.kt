package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.kingzcheung.xime.settings.FrostedGlassConfig

/** 按下时键面向文字色靠拢的比例（所有键型共用）。 */
internal const val PRESSED_KEY_TINT = 0.14f

/** Only the keycap fill is translucent; labels and icons keep their theme colors. */
internal fun frostedKeyColor(
    background: Color,
    foreground: Color,
    config: FrostedGlassConfig,
    legacyStateColor: Color = background,
    pressed: Boolean = false,
    highlighted: Boolean = false,
    opacityScale: Float = 1f,
    /** 白色玻璃的功能键再深一档（见 [glassFunctionKeyDepth]）；其他键为 1。 */
    functionDepth: Float = 1f,
): Color {
    // 非玻璃主题的按下反馈也统一：键面向本键文字色靠一点（深色主题变亮、浅色主题变暗），
    // 不再有的键加深 10%、有的 20%、有的变透明 70%。高亮、禁用等其他状态仍用各自的 legacyStateColor。
    if (!config.enabled) return if (pressed) lerp(background, foreground.copy(alpha = background.alpha), PRESSED_KEY_TINT) else legacyStateColor

    val opacity = config.keyOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.22f
    if (config.seeThrough) return seeThroughKeyColor(background, opacity, pressed, highlighted, opacityScale)
    // Dark keycaps must not lose their elevation twice: #1C1C1C at 20% would
    // become #060606 over black. Compensate the tint (not alpha), preserving
    // the theme's channel ratios and its original color at full opacity.
    // For light surfaces the same rule preserves their distance from white.
    val base = if (opacity < 1f) com.kingzcheung.xime.ui.theme.TransparentGlassTheme.glassKeyBase(background) else background
    // 早期近黑键按 1/不透明度提亮（#1C1C1C → #8C8C8C），不走黑白主题的 3 倍。
    val raisedTint = glassKeyTint(base, foreground, opacity, nearBlackLift = base == background, depth = functionDepth)
    val feedback = when {
        pressed -> 0.08f
        highlighted -> 0.04f
        else -> 0f
    }
    val feedbackTarget = if (maxOf(kotlin.math.abs(raisedTint.red - foreground.red),
        kotlin.math.abs(raisedTint.green - foreground.green), kotlin.math.abs(raisedTint.blue - foreground.blue)) < 0.05f)
        base else foreground
    val tint = lerp(raisedTint, feedbackTarget, feedback)
    // Frosted material owns its opacity independently of an imported theme's alpha.
    // Disabled controls and transparent hit regions can explicitly scale the material.
    return tint.copy(alpha = opacity * opacityScale.coerceIn(0f, 1f))
}

/**
 * iOS 液态玻璃透出模式的键帽：一层淡白，后面应用的模糊画面透过来（见 ui/theme/IosSeeThrough.kt）。
 * [opacityScale] 在这里可以大于 1：功能键传 [com.kingzcheung.xime.ui.theme.IOS_FUNCTION_KEY_SCALE] 厚一档；
 * 禁用、透明热区照旧按比例变淡。主题里鲜艳的键（强调色回车等）保留本色，只是也变透。
 */
private fun seeThroughKeyColor(background: Color, opacity: Float, pressed: Boolean, highlighted: Boolean,
    opacityScale: Float): Color {
    val scale = opacityScale.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 1f
    if (scale <= 0f) return Color.Transparent
    val chroma = maxOf(background.red, background.green, background.blue) -
        minOf(background.red, background.green, background.blue)
    val feedback = when {
        pressed -> .10f
        highlighted -> .05f
        else -> 0f
    }
    if (chroma > .3f && background.alpha > .5f) {
        return background.copy(alpha = (.62f * minOf(scale, 1f) + feedback).coerceIn(0f, 1f))
    }
    return Color.White.copy(alpha = (opacity * scale + feedback * minOf(scale, 1f)).coerceIn(0f, 1f))
}

/** 键帽最亮通道低于此值视为近黑（#29292B 以下）；其他主题的深色键帽都高于此值。 */
internal const val NEAR_BLACK_KEY_PEAK = .18f

/** 近黑键帽在玻璃下的提亮倍数（可调参数；渲染测试会临时改它出对比图）。 */
internal var glassNearBlackLift = 3f

private fun glassKeyTint(background: Color, foreground: Color, opacity: Float, nearBlackLift: Boolean = true,
    depth: Float = 1f): Color {
    if (opacity <= 0f || opacity >= 1f) return background
    val darkSurface = foreground.luminance() > background.luminance()
    val r = if (darkSurface) background.red else 1f - background.red
    val g = if (darkSurface) background.green else 1f - background.green
    val b = if (darkSurface) background.blue else 1f - background.blue
    val peak = maxOf(r, g, b)
    if (peak <= 0f) return background
    // 近黑键帽（黑白主题深色）在玻璃下只提亮 GLASS_NEAR_BLACK_LIFT 倍：
    // 按 1/不透明度（默认 5 倍）会发灰，不提亮（1 倍）又和背景混在一起（2026-10-22 用户反馈）。
    val lift = if (nearBlackLift && darkSurface && peak < NEAR_BLACK_KEY_PEAK) glassNearBlackLift else 1f / opacity
    val scale = minOf(lift * depth, depth / opacity, 1f / peak)
    fun channel(value: Float) = if (darkSurface) value * scale else 1f - value * scale
    return Color(channel(r), channel(g), channel(b), background.alpha)
}

/**
 * 玻璃下键帽色按亮度统一提到同一层白（或黑），深色主题里功能键与字母键会变得一样。
 * 功能键（主题的功能键色）改用更透的材质，与 iOS 一样比字母键更暗/更淡，深浅色都看得出。
 */
internal const val GLASS_FUNCTION_KEY_SCALE = 0.5f

@androidx.compose.runtime.Composable
internal fun glassFunctionKeyScale(background: Color): Float {
    val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current ?: return 1f
    // iOS 液态玻璃透出模式：功能键比字母键厚一档（iOS 键盘的功能键颜色更深/更实）。
    if (LocalKeyboardInputPreferences.current.frostedGlass.seeThrough) {
        return if (com.kingzcheung.xime.ui.theme.TransparentGlassTheme.isGlassFunctionKey(background) ||
            palette.isThemeFunctionKey(background)) com.kingzcheung.xime.ui.theme.IOS_FUNCTION_KEY_SCALE else 1f
    }
    if (com.kingzcheung.xime.ui.theme.TransparentGlassTheme.isGlassFunctionKey(background)) return 1f
    // 白色玻璃不减半：后面是深色应用时，更透的功能键会透成深灰块（用户 11-03 截图）；改为同样厚、颜色深一档。
    if (palette.isWhiteGlass()) return 1f
    return if (palette.isThemeFunctionKey(background)) GLASS_FUNCTION_KEY_SCALE else 1f
}

/** 白色玻璃功能键离白色的距离再乘这么多：和字母键同样厚时，在白色和深色应用上都比底色深一档（像 iOS 浅色键盘）。 */
internal const val WHITE_GLASS_FUNCTION_DEPTH = 1.5f

@androidx.compose.runtime.Composable
internal fun glassFunctionKeyDepth(background: Color): Float {
    val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current ?: return 1f
    return if (palette.isWhiteGlass() && palette.isThemeFunctionKey(background)) WHITE_GLASS_FUNCTION_DEPTH else 1f
}

private fun com.kingzcheung.xime.ui.theme.KeyboardPalette.isWhiteGlass() = background.luminance() >= .5f

private fun com.kingzcheung.xime.ui.theme.KeyboardPalette.isThemeFunctionKey(background: Color): Boolean {
    fun Color.sameRgb(other: Color) = kotlin.math.abs(red - other.red) < 0.004f &&
        kotlin.math.abs(green - other.green) < 0.004f && kotlin.math.abs(blue - other.blue) < 0.004f
    return background.sameRgb(function) && !key.sameRgb(function)
}

/**
 * 白色玻璃（浅色玻璃）的底色最少这么厚。原来浅色默认底色 20%，后面是深色应用时整块透成近黑的灰，
 * 按键和工具栏（补到按键厚度）浮在上面像几块不同的灰（用户 11-03「白色玻璃问题有点多」）。
 * 88% 时深色应用后面也是浅灰白的玻璃，仍透出一点后面的颜色；用户设得更厚照旧。深色玻璃不变。
 */
internal const val WHITE_GLASS_PANEL_OPACITY = 0.88f

/** [white]：浅色外观且主题底色是浅色（深底主题的浅色外观不算白色玻璃）。 */
internal fun FrostedGlassConfig.whiteGlassPanel(white: Boolean): FrostedGlassConfig {
    // 用户在浅色页亲手拖过背景不透明度：按滑条来，不再补。
    if (!enabled || !white || lightPanelCustom) return this
    val opacity = backgroundOpacity.takeIf { it.isFinite() } ?: return copy(backgroundOpacity = WHITE_GLASS_PANEL_OPACITY)
    return if (opacity >= WHITE_GLASS_PANEL_OPACITY) this else copy(backgroundOpacity = WHITE_GLASS_PANEL_OPACITY)
}

/**
 * On glass, size and position distinguish hints; fading them would expose the host through text.
 * [opacity] 是用户的「角标不透明度」（默认 1 = 原样），开不开玻璃都乘上去。
 */
internal fun keyHintColor(themeText: Color, config: FrostedGlassConfig, legacyAlpha: Float, opacity: Float = 1f): Color =
    themeText.copy(alpha = (if (config.enabled) 1f else legacyAlpha) * opacity.coerceIn(0f, 1f))

/** 玻璃材质上键面文字的最低对比度（按键字号大，取 WCAG AA 正文线 4.5:1）。 */
internal const val GLASS_LABEL_CONTRAST = 4.5f

/**
 * 玻璃材质的可读性下限：键盘后面可能是纯白页面，也可能是纯黑页面，两种最坏情况下键面文字都要清楚。
 * 用户设得太透时，背景与按键两个不透明度按同一比例向不透明靠拢，刚好达标为止；本来就清楚的设置原样保留。
 */
internal fun FrostedGlassConfig.legibleOver(
    surface: Color,
    keys: List<Pair<Color, Color>>,
    target: Float = GLASS_LABEL_CONTRAST,
): FrostedGlassConfig {
    if (!enabled || keys.isEmpty()) return this
    val base = normalized()
    fun worst(config: FrostedGlassConfig): Float = listOf(Color.White, Color.Black).minOf { backdrop ->
        val panel = surface.copy(alpha = config.backgroundOpacity).compositeOver(backdrop)
        keys.minOf { (key, text) -> contrastRatio(text, frostedKeyColor(key, text, config).compositeOver(panel)) }
    }
    if (worst(base) >= target) return base
    for (step in 1..20) {
        val t = step / 20f
        val raised = base.copy(
            backgroundOpacity = base.backgroundOpacity + (1f - base.backgroundOpacity) * t,
            keyOpacity = base.keyOpacity + (1f - base.keyOpacity) * t,
        )
        if (worst(raised) >= target) return raised
    }
    return base.copy(backgroundOpacity = 1f, keyOpacity = 1f)
}

/** 当前主题的字母键与功能键都参与检查。白色玻璃先把底色补到 [WHITE_GLASS_PANEL_OPACITY]。 */
internal fun FrostedGlassConfig.legibleForTheme(themeId: String, isDark: Boolean): FrostedGlassConfig {
    if (!enabled) return this
    val palette = com.kingzcheung.xime.ui.theme.resolveKeyboardPalette(themeId, isDark)
    return whiteGlassPanel(!isDark && palette.isWhiteGlass()).legibleOver(palette.background,
        listOf(palette.key to palette.text, palette.function to palette.functionText))
}

private fun contrastRatio(a: Color, b: Color): Float {
    val light = maxOf(a.luminance(), b.luminance())
    val dark = minOf(a.luminance(), b.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}
