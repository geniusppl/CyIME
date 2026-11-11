package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 用户明确调整过固定宽度后，按实际宽度排键，不再自动收回屏幕中央。 */
internal val LocalKeyboardExplicitWidth = staticCompositionLocalOf { false }

/** 主键区底排键帽下方的留白；只调整移动条，不参与键区尺寸计算。 */
internal val LocalFloatingBottomClearance = staticCompositionLocalOf<MutableFloatState?> { null }
// The keycap inset belongs to the key's touch target but is part of the visible gap below its ink.
internal val LocalFloatingKeyCapInset = staticCompositionLocalOf<MutableFloatState?> { null }
internal val KeyboardGridBottomMargin = 8.dp



/**
 * 当前键盘体的视觉度量（由布局策略算出）。
 *
 * [KeyVisualMetrics.Unspecified] = 没有策略上下文（候选栏等嵌套网格），
 * 此时 [scaledKeyVisualPadding] 原样返回声明值，不改变嵌套网格的既有视觉。
 */
internal val LocalKeyboardKeyVisualMetrics = staticCompositionLocalOf { KeyVisualMetrics.Unspecified }

/**
 * 键盘体尺寸上下文：算出 gutter、每侧 inset 与内容缩放。
 *
 * - 命中区域不变：gutter 只作用于内容尺寸，键仍按整格接收触摸（见 KeyButton）。
 * - [columns] 是“单位列数”（26 键 = 首行键数；九键/数字/笔画 = 4.41）。
 * - [policy] 决定缝的目标值与上下限，集中定义在 [KeyVisualPolicy]。
 * - [allowShrink] 悬浮键盘：允许整体缩小，不套用手机最小缝。
 * - [applyGutter] 是否把算出的 gutter 作为内容左右边距（手机呼吸空间 + 大屏居中）。
 */
@Composable
internal fun KeyboardKeySpacingScope(
    modifier: Modifier,
    columns: Float = 10f,
    rows: Float = 4f,
    horizontalInset: Dp = 8.dp,
    verticalInset: Dp = 8.dp,
    widthFraction: Float = 1f,
    policy: KeyVisualPolicy = KeyVisualPolicy.Qwerty,
    allowShrink: Boolean = false,
    applyGutter: Boolean = false,
    /** Four-row typing grids opt in with their existing X/Y spacing overrides. */
    growthSpacing: Pair<Float?, Float?>? = null,
    gridBottomMargin: Dp = KeyboardGridBottomMargin,
    /** 「加宽按键」：只作用于铺 gutter 的主键盘体（见 [KeyVisualPolicy.widened]）。 */
    widenKeys: Boolean = LocalKeyboardInputPreferences.current.wideKeys,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val widened = widenKeys && applyGutter
        val basePolicy = if (widened) policy.widened() else policy
        // 不铺 gutter 时（嵌套面板/编辑盘）：不加键宽上限，度量按真实格宽算，
        // 避免"按上限缩格"却又不把多出的宽度变成留白。
        val effectivePolicy = when {
            !applyGutter -> basePolicy.copy(maxKeyWidth = Float.MAX_VALUE, minGutter = 0f)
            LocalKeyboardExplicitWidth.current -> basePolicy.copy(maxKeyWidth = Float.MAX_VALUE)
            else -> basePolicy
        }
        // 调用方声明的 horizontalInset 是 gutter 下限；加宽按键时下限同样收到 WideKeysGutter。
        val insetFloor = if (widened) minOf(horizontalInset.value, KeyVisualPolicy.WideKeysGutter)
            else horizontalInset.value
        val metrics = keyboardGridMetrics(
            policy = effectivePolicy,
            availableWidthDp = maxWidth.value,
            availableHeightDp = maxHeight.value,
            columns = columns,
            rows = rows,
            verticalInsetDp = verticalInset.value,
            allowShrink = allowShrink,
            horizontalInsetDp = insetFloor,
            applyGutter = applyGutter,
            widthFraction = widthFraction,
            growthSpacing = growthSpacing,
        )
        val bottomClearance = LocalFloatingBottomClearance.current
        val capInset = LocalFloatingKeyCapInset.current
        if (bottomClearance != null && growthSpacing != null) {
            // 只有下面真正施加在整行外面的 padding 可以用来拖动；键帽内缩处仍是底排按键的触摸区。
            val clearance = metrics.bottomSpaceDp + gridBottomMargin.value
            val capPadding = resolvedKeyInset((growthSpacing.second ?: KeyVisualPolicy.DeclaredDefaultGap).dp, metrics.insetY)
            SideEffect {
                bottomClearance.floatValue = clearance
                capInset?.floatValue = capPadding.value + metrics.extraInsetY
            }
            DisposableEffect(bottomClearance) {
                onDispose { bottomClearance.floatValue = 0f; capInset?.floatValue = 0f }
            }
        }
        CompositionLocalProvider(
            // 符号页等嵌套网格不能覆盖整张键盘的底部留白。
            LocalFloatingBottomClearance provides null,
            LocalFloatingKeyCapInset provides null,
            LocalKeyboardKeyVisualMetrics provides metrics,
            LocalKeyboardKeyContentScale provides KeyboardKeyMetrics.contentScale(metrics.capShortEdge, metrics.capShortEdge),
            // learn-33：这块键区所有键的液态玻璃静止样子共用一张纹理（见 NekoGlassCache.kt）。
            com.kingzcheung.xime.ui.theme.LocalNekoRestSheet provides com.kingzcheung.xime.ui.theme.rememberNekoRestSheet(),
        ) {
            // 调用方声明的 horizontalInset 只作为 gutter 下限（历史参数：曾经只是公式预留）
            val gutter = metrics.gutterX
            content(Modifier.fillMaxSize().padding(start = gutter.dp, end = gutter.dp,
                top = metrics.topSpaceDp.dp, bottom = metrics.bottomSpaceDp.dp))
        }
    }
}

/**
 * 把布局声明的键帽内缩解析成最终视觉。
 *
 * - 声明值 == [KeyVisualPolicy.DeclaredDefaultGap]（2dp，历史默认）→ 用布局策略的每侧 inset；
 * - 声明其他数值 → 视为相邻键帽之间的**总缝**（单侧 = 一半），供 xime.yaml 逐键盘覆盖；
 * - 无策略上下文 → 原样返回声明值。
 */
@Composable
internal fun scaledKeyVisualPadding(padding: PaddingValues = LocalKeyVisualPadding.current): PaddingValues {
    val metrics = LocalKeyboardKeyVisualMetrics.current
    val direction = LocalLayoutDirection.current
    return remember(padding, metrics, direction) {
        PaddingValues(
            start = resolvedKeyInset(padding.calculateStartPadding(direction), metrics.insetX),
            top = resolvedKeyInset(padding.calculateTopPadding(), metrics.insetY) + metrics.extraInsetY.dp,
            end = resolvedKeyInset(padding.calculateEndPadding(direction), metrics.insetX),
            bottom = resolvedKeyInset(padding.calculateBottomPadding(), metrics.insetY) + metrics.extraInsetY.dp,
        )
    }
}

private fun resolvedKeyInset(declared: Dp, policyInset: Float?): Dp = when {
    policyInset == null -> declared
    declared.value == KeyVisualPolicy.DeclaredDefaultGap -> policyInset.dp
    else -> (declared.value / 2f).dp
}
