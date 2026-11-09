package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

data class FrostedGlassConfig(
    val enabled: Boolean = false,
    val blurRadiusDp: Float = 10f,
    val backgroundOpacity: Float = 0.8f,
    val keyOpacity: Float = 0.2f,
    /**
     * 只对浅色外观：用户在浅色页亲手拖过「背景不透明度」。拖过后白色玻璃不再补到 88%，
     * 工具栏也不再单独补厚，整块跟着滑条走（用户反馈 11-03：更新后工具栏不跟着透明度调整）。
     */
    val lightPanelCustom: Boolean = false,
    /**
     * iOS 液态玻璃的「透出后面 App」模式（只在运行时由 [com.kingzcheung.xime.ui.theme.iosSeeThrough] 打开，不存盘）：
     * 键盘底色只剩一层淡色、键帽是淡白，靠系统窗口模糊看见后面的应用。
     */
    val seeThrough: Boolean = false,
) {
    fun normalized(defaults: FrostedGlassConfig = FrostedGlassConfig.defaults(true)): FrostedGlassConfig = copy(
        blurRadiusDp = (blurRadiusDp.takeIf { it.isFinite() } ?: defaults.blurRadiusDp).coerceIn(0f, 40f),
        backgroundOpacity = (backgroundOpacity.takeIf { it.isFinite() } ?: defaults.backgroundOpacity).coerceIn(0f, 1f),
        keyOpacity = (keyOpacity.takeIf { it.isFinite() } ?: defaults.keyOpacity).coerceIn(0f, 1f),
    )

    companion object {
        fun defaults(isDark: Boolean, enabled: Boolean = false) = FrostedGlassConfig(
            enabled = enabled, blurRadiusDp = 10f,
            backgroundOpacity = if (isDark) 0.8f else 0.2f,
            keyOpacity = if (isDark) 0.2f else 0.8f,
        )
    }
}

data class FrostedGlassProfiles(val light: FrostedGlassConfig, val dark: FrostedGlassConfig) {
    fun forAppearance(isDark: Boolean): FrostedGlassConfig = if (isDark) dark else light
    fun withEnabled(enabled: Boolean) = copy(light = light.copy(enabled = enabled), dark = dark.copy(enabled = enabled))

    /**
     * 只换 [isDark] 这一套；另一套除了共用的玻璃开关都不动。
     * 原来两套共用一对不透明度、切深浅时互换，调浅色就改了深色（用户反馈 11-03：白天黑夜两个设置不能同时保留）。
     */
    fun withAppearance(config: FrostedGlassConfig, isDark: Boolean): FrostedGlassProfiles {
        val current = config.normalized(FrostedGlassConfig.defaults(isDark))
        val opposite = forAppearance(!isDark).copy(enabled = current.enabled)
        return if (isDark) FrostedGlassProfiles(opposite, current) else FrostedGlassProfiles(current, opposite)
    }
}

/** One material switch; blur and both opacities are stored per appearance (light and dark never overwrite each other). */
object FrostedGlassPreferences {
    const val KEY_ENABLED = "frosted_glass_enabled"
    const val KEY_BLUR_RADIUS = "frosted_glass_blur_radius_dp"
    const val KEY_BACKGROUND_OPACITY = "frosted_glass_background_opacity"
    const val KEY_KEY_OPACITY = "frosted_glass_key_opacity"
    private const val KEY_APPEARANCE_MIGRATED = "frosted_glass_appearance_migrated"
    private const val KEY_PAIRED_DARK_BACKGROUND = "frosted_glass_paired_dark_background_opacity"
    private const val KEY_PAIRED_DARK_KEY = "frosted_glass_paired_dark_key_opacity"
    const val KEY_LIGHT_PANEL_CUSTOM = "frosted_glass_light_panel_custom"
    /** 不透明度已按浅色、深色分开存（11-03 起）；没有它时从旧的一对互换值迁移一次。 */
    const val KEY_OPACITY_SPLIT = "frosted_glass_opacity_split"
    private val parameterKeys = listOf(KEY_BLUR_RADIUS, KEY_BACKGROUND_OPACITY, KEY_KEY_OPACITY)
    private fun appearanceKey(key: String, dark: Boolean) = key + if (dark) "_dark" else "_light"

    val keys: Set<String> = (parameterKeys + parameterKeys.map { appearanceKey(it, true) } +
        parameterKeys.map { appearanceKey(it, false) } +
        listOf(KEY_ENABLED, KEY_APPEARANCE_MIGRATED, KEY_PAIRED_DARK_BACKGROUND, KEY_PAIRED_DARK_KEY,
            KEY_LIGHT_PANEL_CUSTOM, KEY_OPACITY_SPLIT, "keyboard_theme", "dark_mode")).toSet()

    fun isDark(context: Context): Boolean = when (SettingsPreferences.getEffectiveDarkMode(context)) {
        0 -> false
        2 -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        else -> true
    }

    fun read(context: Context, isDark: Boolean = FrostedGlassPreferences.isDark(context)): FrostedGlassConfig =
        readProfiles(context).forAppearance(isDark)

    fun readProfiles(context: Context): FrostedGlassProfiles {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        migrateLegacy(context, prefs)
        migrateOpacityPair(context, prefs)
        migrateOpacitySplit(prefs)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        fun readAppearance(dark: Boolean): FrostedGlassConfig {
            val defaults = FrostedGlassConfig.defaults(dark, enabled)
            return FrostedGlassConfig(
                enabled,
                prefs.getFloat(appearanceKey(KEY_BLUR_RADIUS, dark), defaults.blurRadiusDp),
                prefs.getFloat(appearanceKey(KEY_BACKGROUND_OPACITY, dark), defaults.backgroundOpacity),
                prefs.getFloat(appearanceKey(KEY_KEY_OPACITY, dark), defaults.keyOpacity),
                lightPanelCustom = !dark && prefs.getBoolean(KEY_LIGHT_PANEL_CUSTOM, false),
            ).normalized(defaults)
        }
        return FrostedGlassProfiles(readAppearance(false), readAppearance(true))
    }

    private fun migrateLegacy(context: Context, prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_APPEARANCE_MIGRATED, false) || parameterKeys.none(prefs::contains)) return
        // Keep the original keys as a recoverable record. A known dark preset must
        // never seed the light appearance, even when first read from a light screen.
        val legacy = FrostedGlassConfig(
            blurRadiusDp = prefs.getFloat(KEY_BLUR_RADIUS, 24f),
            backgroundOpacity = prefs.getFloat(KEY_BACKGROUND_OPACITY, 0.55f),
            keyOpacity = prefs.getFloat(KEY_KEY_OPACITY, 0.22f),
        )
        val targetDark = legacy == FrostedGlassConfig.defaults(true) || isDark(context)
        val editor = prefs.edit().putBoolean(KEY_APPEARANCE_MIGRATED, true)
        if (parameterKeys.none { prefs.contains(appearanceKey(it, targetDark)) }) {
            writeAppearance(editor, legacy.normalized(FrostedGlassConfig.defaults(targetDark)), targetDark)
        }
        editor.apply()
    }

    private fun migrateOpacityPair(context: Context, prefs: SharedPreferences) {
        if (prefs.contains(KEY_PAIRED_DARK_BACKGROUND) && prefs.contains(KEY_PAIRED_DARK_KEY)) return
        fun hasOpacity(dark: Boolean) = prefs.contains(appearanceKey(KEY_BACKGROUND_OPACITY, dark)) ||
            prefs.contains(appearanceKey(KEY_KEY_OPACITY, dark))
        if (!hasOpacity(true) && !hasOpacity(false)) return
        // Preserve the currently rendered old pair. If it was never customized,
        // use the other appearance's saved pair; keep all old keys as a recovery record.
        val currentDark = isDark(context)
        val sourceDark = if (hasOpacity(currentDark)) currentDark else !currentDark
        val defaults = FrostedGlassConfig.defaults(sourceDark)
        val source = FrostedGlassConfig(
            backgroundOpacity = prefs.getFloat(appearanceKey(KEY_BACKGROUND_OPACITY, sourceDark), defaults.backgroundOpacity),
            keyOpacity = prefs.getFloat(appearanceKey(KEY_KEY_OPACITY, sourceDark), defaults.keyOpacity),
        ).normalized(defaults)
        val editor = prefs.edit()
        if (!prefs.contains(KEY_PAIRED_DARK_BACKGROUND)) editor.putFloat(KEY_PAIRED_DARK_BACKGROUND,
            if (sourceDark) source.backgroundOpacity else source.keyOpacity)
        if (!prefs.contains(KEY_PAIRED_DARK_KEY)) editor.putFloat(KEY_PAIRED_DARK_KEY,
            if (sourceDark) source.keyOpacity else source.backgroundOpacity)
        editor.apply()
    }

    /**
     * 旧版两套共用一对不透明度（深色存一对，浅色互换着用）。第一次读时把正在显示的两套
     * 原样写进各自的键，之后各存各的；旧的一对留作恢复记录。没有旧值（新装）时什么都不写。
     */
    private fun migrateOpacitySplit(prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_OPACITY_SPLIT, false)) return
        if (!prefs.contains(KEY_PAIRED_DARK_BACKGROUND) && !prefs.contains(KEY_PAIRED_DARK_KEY)) return
        val darkDefaults = FrostedGlassConfig.defaults(true)
        val paired = FrostedGlassConfig(
            backgroundOpacity = prefs.getFloat(KEY_PAIRED_DARK_BACKGROUND, darkDefaults.backgroundOpacity),
            keyOpacity = prefs.getFloat(KEY_PAIRED_DARK_KEY, darkDefaults.keyOpacity),
        ).normalized(darkDefaults)
        prefs.edit()
            .putFloat(appearanceKey(KEY_BACKGROUND_OPACITY, true), paired.backgroundOpacity)
            .putFloat(appearanceKey(KEY_KEY_OPACITY, true), paired.keyOpacity)
            .putFloat(appearanceKey(KEY_BACKGROUND_OPACITY, false), paired.keyOpacity)
            .putFloat(appearanceKey(KEY_KEY_OPACITY, false), paired.backgroundOpacity)
            .putBoolean(KEY_OPACITY_SPLIT, true)
            .apply()
    }

    fun save(context: Context, config: FrostedGlassConfig, isDark: Boolean = FrostedGlassPreferences.isDark(context)) {
        val previous = readProfiles(context)
        saveProfiles(context, previous.withAppearance(config, isDark))
    }

    fun saveProfiles(context: Context, profiles: FrostedGlassProfiles) {
        val enabled = profiles.dark.enabled
        val editor = SettingsPreferences.getPrefsPublic(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_APPEARANCE_MIGRATED, true)
        val dark = profiles.dark.normalized(FrostedGlassConfig.defaults(true))
        val light = profiles.light.normalized(FrostedGlassConfig.defaults(false))
        // 旧的一对仍跟着深色写，装回旧版时深色不变。
        editor.putFloat(KEY_PAIRED_DARK_BACKGROUND, dark.backgroundOpacity)
            .putFloat(KEY_PAIRED_DARK_KEY, dark.keyOpacity)
            .putBoolean(KEY_LIGHT_PANEL_CUSTOM, profiles.light.lightPanelCustom)
            .putBoolean(KEY_OPACITY_SPLIT, true)
        writeAppearance(editor, dark, true)
        writeAppearance(editor, light, false)
        editor.apply()
    }

    private fun writeAppearance(editor: SharedPreferences.Editor, config: FrostedGlassConfig, dark: Boolean) {
        editor.putFloat(appearanceKey(KEY_BLUR_RADIUS, dark), config.blurRadiusDp)
            .putFloat(appearanceKey(KEY_BACKGROUND_OPACITY, dark), config.backgroundOpacity)
            .putFloat(appearanceKey(KEY_KEY_OPACITY, dark), config.keyOpacity)
    }
}
