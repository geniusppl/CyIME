package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.FrostedGlassConfig
import java.util.function.Consumer

/*
 * iOS 液态玻璃 · 透出后面的应用（用户 11-09：learn-24 按压动效好看，但整体不像玻璃——键盘铺在一张几乎不透明的
 * 浅色渐变上，透镜后面没有东西可以弯；真正的 iOS 玻璃看得见后面的内容）。
 *
 * 玻璃 + 液态玻璃 + iOS 液态玻璃都开着、并且系统窗口模糊可用时：
 * - 键盘底色降到一层淡色（浅色 14%、深色 25%），后面由 ImeGlassBlurWindow 的系统跨窗口模糊打底，
 *   模糊半径至少 28dp（用户设得更大照旧）；
 * - 键帽是淡白（浅色 18%、深色 13%），功能键再厚一档（[IOS_FUNCTION_KEY_SCALE]），见 frostedKeyColor；
 * - 透镜不再取样主题底片（那会在键里画出一圈不透明的「内框」），只留上沿亮边、暗线、斜面、指尖光和鼓起。
 * 系统关了窗口模糊（省电模式、开发者选项、不支持的设备）时一律退回原来的样子，不让键盘透成清晰的应用画面。
 */

internal const val IOS_SEE_THROUGH_MIN_BLUR_DP = 28f
internal const val IOS_SEE_THROUGH_PANEL_LIGHT = .14f
internal const val IOS_SEE_THROUGH_PANEL_DARK = .25f
internal const val IOS_SEE_THROUGH_KEY_LIGHT = .18f
internal const val IOS_SEE_THROUGH_KEY_DARK = .13f
/** 功能键（shift、删除、123、符号、地球、回车）比字母键厚这么多倍，像 iOS 键盘的深一档功能键。 */
internal const val IOS_FUNCTION_KEY_SCALE = 1.5f

/** 换成透出模式的玻璃参数；只在键盘运行时用，不写回设置。 */
internal fun FrostedGlassConfig.iosSeeThrough(isDark: Boolean): FrostedGlassConfig {
    val base = normalized()
    return base.copy(
        blurRadiusDp = maxOf(base.blurRadiusDp, IOS_SEE_THROUGH_MIN_BLUR_DP),
        backgroundOpacity = if (isDark) IOS_SEE_THROUGH_PANEL_DARK else IOS_SEE_THROUGH_PANEL_LIGHT,
        keyOpacity = if (isDark) IOS_SEE_THROUGH_KEY_DARK else IOS_SEE_THROUGH_KEY_LIGHT,
        // 候选栏/工具栏不再单独补厚到按键的厚度（CandidateBar 的 stripBoost），和键盘底色一样透。
        lightPanelCustom = true,
        seeThrough = true,
    )
}

/** 玻璃、液态玻璃、iOS 液态玻璃都开着，且系统此刻允许窗口模糊时，换成透出模式。 */
@Composable
internal fun FrostedGlassConfig.withIosSeeThrough(isDark: Boolean): FrostedGlassConfig {
    val context = LocalContext.current
    // 只在输入法窗口里（后面有 ImeGlassBlurWindow 的模糊）；设置页的预览和设置项读到的仍是原参数。
    if (!enabled || !context.isInputMethodContext() || !LiquidGlass.enabled(context) || !LiquidGlass.rich(context)) return this
    return if (rememberCrossWindowBlurAvailable()) iosSeeThrough(isDark) else this
}

/**
 * 系统跨窗口模糊此刻是否可用（WindowManager.isCrossWindowBlurEnabled，省电模式等会关掉并回调），
 * 或三星 One UI 的窗口模糊可用（ImeGlassBlurWindow 同样会走它）。Android 12 以下为 false。
 */
@Composable
internal fun rememberCrossWindowBlurAvailable(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val context = LocalContext.current
    var available by remember(context) { mutableStateOf(crossWindowBlurEnabled(context)) }
    DisposableEffect(context) {
        val manager = try { context.getSystemService(WindowManager::class.java) } catch (_: RuntimeException) { null }
        val listener = Consumer<Boolean> { available = it }
        val registered = manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && try {
            manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
            true
        } catch (_: RuntimeException) { false }
        onDispose {
            if (registered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { manager?.removeCrossWindowBlurEnabledListener(listener) } catch (_: RuntimeException) {}
            }
        }
    }
    return available || SamsungWindowBlur.usableFor(context)
}

private tailrec fun Context.isInputMethodContext(): Boolean = when (this) {
    is android.inputmethodservice.InputMethodService -> true
    is android.content.ContextWrapper -> {
        val base = baseContext
        if (base == null || base === this) false else base.isInputMethodContext()
    }
    else -> false
}

private fun crossWindowBlurEnabled(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && try {
        context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    } catch (_: RuntimeException) { false }
