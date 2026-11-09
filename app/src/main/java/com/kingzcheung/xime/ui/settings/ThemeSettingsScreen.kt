package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Opacity
import androidx.compose.material.icons.twotone.BlurOn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Crop
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.viewmodel.ThemeSettingsViewModel
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.FrostedGlassProfiles
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.VisualStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsContent(
    onBack: () -> Unit,
    onThemeChanged: () -> Unit = {}
) {
    var showIconSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val subPage = if (showIconSettings) "icons" else null
    SettingsSubPages(subPage, onBack = { showIconSettings = false }) { shown ->
        ThemeSettingsPage(shown, onBack, onThemeChanged,
            openIcons = { showIconSettings = true }, closeSubPage = { showIconSettings = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSettingsPage(
    subPage: String?,
    onBack: () -> Unit,
    onThemeChanged: () -> Unit,
    openIcons: () -> Unit,
    closeSubPage: () -> Unit,
) {
    val viewModel: ThemeSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val glass = FrostedGlassProfiles(
        rememberKeyboardInputPreferences(false).frostedGlass,
        rememberKeyboardInputPreferences(true).frostedGlass,
    )
    if (subPage == "icons") {
        IconSettingsContent(onBack = closeSubPage, onChanged = onThemeChanged)
        return
    }
    
    var previewTheme by remember { mutableStateOf<KeyboardColorScheme?>(null) }
    var showGlassSettings by remember { mutableStateOf(false) }
    KeyboardSettingsScaffold("主题与外观", onBack, preview = previewTheme == null && !showGlassSettings) { modifier ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = modifier.settingsGutterScroll(listState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding()
        ) {
            item {
                Text(
                    text = "显示模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                val currentTheme = uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme)
                Row(
                    modifier = Modifier.settingsSearchAnchor("显示模式", bleed = 6.dp, bleedVertical = 6.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyboardThemeCard(
                        theme = currentTheme,
                        glass = glass,
                        previewAspectRatio = 1.3f,
                        isSelected = uiState.darkMode == 2,
                        onClick = {
                            changeAppearance(context, onThemeChanged) { viewModel.setDarkMode(2) }
                        },
                        modifier = Modifier.weight(1f),
                        title = "跟随系统"
                    )
                    KeyboardThemeCard(
                        theme = currentTheme,
                        glass = glass,
                        previewAspectRatio = 1.3f,
                        title = "浅色",
                        isSelected = uiState.darkMode == 0,
                        previewDark = false,
                        onClick = {
                            changeAppearance(context, onThemeChanged) { viewModel.setDarkMode(0) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    KeyboardThemeCard(
                        theme = currentTheme,
                        glass = glass,
                        previewAspectRatio = 1.3f,
                        title = "深色",
                        isSelected = uiState.darkMode == 1,
                        previewDark = true,
                        onClick = {
                            changeAppearance(context, onThemeChanged) { viewModel.setDarkMode(1) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            item {
                SettingsSection(title = "材质效果") {
                    SettingsToggleItem(icon = Icons.TwoTone.BlurOn, title = "玻璃效果",
                        subtitle = "沿用当前配色，切换主题时保持", checked = glass.dark.enabled,
                        onCheckedChange = { enabled ->
                            changeAppearance(context, onThemeChanged) {
                                FrostedGlassPreferences.saveProfiles(context, glass.withEnabled(enabled))
                                // 液态玻璃建在玻璃上：关玻璃时一起关。
                                if (!enabled) SettingsPreferences.setGlassLiquid(context, false)
                            }
                        }, costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.GLASS)
                    // 液态玻璃不用先开玻璃：打开它就把玻璃一起打开。
                    SettingsToggleItem(icon = Icons.TwoTone.AutoAwesome, title = "液态玻璃",
                        subtitle = "按键加细亮边，按住变亮；图片背景会在边缘折射",
                        checked = glass.dark.enabled && com.kingzcheung.xime.ui.theme.LiquidGlass.enabled(context),
                        onCheckedChange = { enabled ->
                            changeAppearance(context, onThemeChanged) {
                                if (enabled && !glass.dark.enabled) FrostedGlassPreferences.saveProfiles(context, glass.withEnabled(true))
                                SettingsPreferences.setGlassLiquid(context, enabled)
                            }
                        }, costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.LIQUID_GLASS)
                    if (glass.dark.enabled && com.kingzcheung.xime.ui.theme.LiquidGlass.enabled(context)) {
                        SettingsToggleItem(icon = Icons.TwoTone.AutoAwesome, title = "iOS 液态玻璃",
                            subtitle = "键盘透出后面 App 的模糊画面（需系统开启窗口模糊，省电模式下会关闭）；上下沿高光、暗边、按下鼓起回弹，工具栏玻璃滑块" +
                                com.kingzcheung.xime.BuildConfig.LEARN_BUILD.takeIf { it.isNotEmpty() }?.let { "（测试版 learn-$it）" }.orEmpty(),
                            checked = com.kingzcheung.xime.ui.theme.LiquidGlass.rich(context),
                            onCheckedChange = { enabled ->
                                changeAppearance(context, onThemeChanged) { SettingsPreferences.setGlassLiquidRich(context, enabled) }
                            })
                    }
                    if (glass.dark.enabled) {
                        SettingsItem(icon = Icons.TwoTone.Opacity, title = "透明度与模糊",
                            subtitle = "浅色、深色分开设置", showArrow = true,
                            onClick = { showGlassSettings = true })
                        // 「柔化顶部边缘」11-01 并进本页下面的「键盘顶部样式」（圆角／直角／柔化三选一）。
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "配色方案",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.settingsSearchAnchor("配色方案", bleed = 6.dp, bleedVertical = 4.dp).fillMaxWidth().padding(bottom = 6.dp)
                )
            }
            
            item {
                val shownTheme = uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme)
                Text(
                    // 莫奈取色按每台设备的壁纸取色，不能说各设备相同。
                    text = if (shownTheme.isDynamic) "${shownTheme.name}跟随系统壁纸取色。点击可预览。"
                        else "${shownTheme.name}在各设备使用相同配色。点击可预览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            
            uiState.colorThemes.chunked(3).forEach { rowThemes ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowThemes.forEach { theme ->
                            KeyboardThemeCard(
                                theme = theme,
                                glass = glass,
                                isSelected = com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme).id == theme.id,
                                onClick = {
                                    previewTheme = theme
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowThemes.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }
            
            item {
                CustomImageThemeSection(currentThemeId = uiState.colorTheme, onSaved = {
                    viewModel.refreshThemes()
                    changeAppearance(context, onThemeChanged) {
                        viewModel.setColorTheme(com.kingzcheung.xime.ui.theme.CustomImageTheme.ID)
                    }
                }, onDeleted = { baseThemeId ->
                    viewModel.refreshThemes()
                    // 正在用「我的图片」时换回选图时沿用的主题。
                    if (uiState.colorTheme == com.kingzcheung.xime.ui.theme.CustomImageTheme.ID) {
                        changeAppearance(context, onThemeChanged) { viewModel.setColorTheme(baseThemeId) }
                    } else onThemeChanged()
                }, onVeilChanged = {
                    viewModel.refreshThemes()
                    onThemeChanged()
                })
            }

            // 视觉样式只有一组卡片，直接放在本页；按键显示（字号、角标、气泡、底部）也属于外观。
            item {
                VisualStylePicker(VisualStyles.current) { style ->
                    changeAppearance(context, onThemeChanged) { SettingsPreferences.setVisualStyle(context, style) }
                }
            }
            item {
                KeyShapePicker(com.kingzcheung.xime.ui.theme.KeyShapes.current) { shape ->
                    changeAppearance(context, onThemeChanged) { SettingsPreferences.setKeyShape(context, shape) }
                }
            }
            item { KeyboardAppearanceSettings() }
            item {
                SettingsSection(title = "桌面图标") {
                    SettingsItem(icon = Icons.TwoTone.Apps, title = "图标设置",
                        subtitle = if (com.kingzcheung.xime.ui.theme.IconAppearance.linked) "与视觉样式同步" else "独立选择",
                        onClick = openIcons, showArrow = true)
                }
            }
        }

        previewTheme?.let { theme ->
            ThemePreviewSheet(
                theme = theme,
                onApply = {
                    changeAppearance(context, onThemeChanged) {
                        viewModel.setColorTheme(theme.id)
                        previewTheme = null
                    }
                },
                onDismiss = { previewTheme = null },
            )
        }
        if (showGlassSettings) {
            FrostedGlassSettingsSheet(
                theme = uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme),
                onApply = { draftGlass ->
                    changeAppearance(context, onThemeChanged) {
                        FrostedGlassPreferences.saveProfiles(context, draftGlass)
                        showGlassSettings = false
                    }
                },
                onDismiss = { showGlassSettings = false },
            )
        }
    }
}

/**
 * 「我的图片」上压的遮罩（浅色、深色各一条）：越高图片越淡、按键文字越清楚，0% 是原图。
 * 拖动时只改数字，松手才存并刷新键盘；默认 72%，与原来一样。
 */
@Composable
private fun CustomImageVeilSlider(dark: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val title = if (dark) "深色遮罩" else "浅色遮罩"
    var veil by remember { mutableStateOf(com.kingzcheung.xime.ui.theme.CustomImageTheme.veil(context, dark)) }
    Column(Modifier.settingsSearchAnchor(title).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("$title：${kotlin.math.round(veil * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Slider(
            value = veil,
            onValueChange = { veil = kotlin.math.round(it * 20f) / 20f },
            onValueChangeFinished = {
                changeAppearance(context, onChanged) {
                    com.kingzcheung.xime.ui.theme.CustomImageTheme.setVeil(context, dark, veil)
                    com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                }
            },
            valueRange = 0f..com.kingzcheung.xime.ui.theme.CustomImageTheme.MAX_VEIL,
            steps = 17,
            modifier = Modifier.testTag(if (dark) "custom-image-veil-dark" else "custom-image-veil-light"),
        )
    }
}

/**
 * 「用自己的图片」：从相册选一张图，先在调整页拖动、缩放、贴齐边缘，确认后换成「我的图片」主题。
 * 设置过以后可以再调整或删除。
 */
@Composable
private fun CustomImageThemeSection(currentThemeId: String, onSaved: () -> Unit, onDeleted: (String) -> Unit,
    onVeilChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasImage by remember { mutableStateOf(com.kingzcheung.xime.ui.theme.CustomImageTheme.hasImage(context)) }
    // 调整页正在调的图片（刚选的新图或已在用的那张）与要沿用的主题。
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val base = com.kingzcheung.xime.ui.theme.CustomImageTheme.baseFor(context, currentThemeId)
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { com.kingzcheung.xime.ui.theme.CustomImageTheme.importImage(context, uri) }
            }
            imported.onSuccess { editing = it to base }.onFailure {
                com.kingzcheung.xime.util.FileLogger.e("Appearance", "Custom background failed", it)
                android.widget.Toast.makeText(context, "这张图片读不出来，换一张试试", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    SettingsSection(title = "背景图片") {
        SettingsItem(icon = Icons.TwoTone.Image, title = "用自己的图片",
            subtitle = if (hasImage) "已设置，在上面的配色里选「我的图片」使用；点这里换一张"
                else "从相册选一张图片做键盘背景，按键和文字的颜色沿用当前主题",
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            showArrow = true)
        if (hasImage) {
            SettingsItem(icon = Icons.TwoTone.Crop, title = "调整图片",
                subtitle = "拖动、缩放，贴齐键盘的边，或让两边、上下留白",
                onClick = {
                    com.kingzcheung.xime.ui.theme.CustomImageTheme.imageSource(context)?.let { src ->
                        editing = src to com.kingzcheung.xime.ui.theme.CustomImageTheme.baseFor(context,
                            com.kingzcheung.xime.ui.theme.CustomImageTheme.ID)
                    }
                },
                showArrow = true)
            CustomImageVeilSlider(dark = false, onChanged = onVeilChanged)
            CustomImageVeilSlider(dark = true, onChanged = onVeilChanged)
            SettingsItem(icon = Icons.TwoTone.Delete, title = "删除图片",
                subtitle = "删掉这张图片和「我的图片」配色",
                onClick = { confirmDelete = true })
        }
        BackgroundMotionSettingsItems(hasCustomImage = hasImage)
    }
    editing?.let { (src, base) ->
        val existing = src == com.kingzcheung.xime.ui.theme.CustomImageTheme.imageSource(context)
        CustomImageEditorSheet(
            src = src,
            baseThemeId = base,
            initial = if (existing) com.kingzcheung.xime.ui.theme.CustomImageTheme.placement(context)
                else com.kingzcheung.xime.ui.theme.ThemeImagePlacement.DEFAULT,
            onConfirm = { placement ->
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { com.kingzcheung.xime.ui.theme.CustomImageTheme.commit(context, src, base, placement) }
                    }
                    saved.onSuccess {
                        com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                        hasImage = true
                        editing = null
                        onSaved()
                    }.onFailure {
                        com.kingzcheung.xime.util.FileLogger.e("Appearance", "Custom background save failed", it)
                        android.widget.Toast.makeText(context, "图片没有保存成功，请重试", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = {
                editing = null
                scope.launch(Dispatchers.IO) { com.kingzcheung.xime.ui.theme.CustomImageTheme.discard(context, src) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除图片") },
            text = { Text("删掉这张背景图片，「我的图片」配色也一起去掉；正在用它时换回原来的配色。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { com.kingzcheung.xime.ui.theme.CustomImageTheme.delete(context) }
                        }
                        deleted.onSuccess { base ->
                            com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                            hasImage = false
                            onDeleted(base)
                        }.onFailure {
                            com.kingzcheung.xime.util.FileLogger.e("Appearance", "Custom background delete failed", it)
                            android.widget.Toast.makeText(context, "没有删除成功，请重试", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
