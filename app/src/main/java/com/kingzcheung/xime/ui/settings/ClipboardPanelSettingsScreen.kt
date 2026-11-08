package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kingzcheung.xime.clipboard.ClipboardGroupStore
import com.kingzcheung.xime.clipboard.ClipboardImages
import com.kingzcheung.xime.clipboard.ImageAccessActivity
import com.kingzcheung.xime.settings.PerformanceCosts
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_CARD_MAX_LINES_KEY
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_LIST_LAYOUT_KEY
import com.kingzcheung.xime.ui.menubar.CLIPBOARD_CLOSE_AFTER_PASTE_KEY
import com.kingzcheung.xime.ui.menubar.clipboardCardMaxLines
import com.kingzcheung.xime.ui.menubar.clipboardClosesAfterPaste

/**
 * 剪贴板面板 (用户 11-03：剪贴板的几个小菜单要有一个实体的设置页)：⋯ 菜单里的两个开关 (最近截图、
 * 上屏后自动关闭剪贴板)、分组，和原来在「剪贴板与同步」里的卡片行数、历史记录放在一页。
 * 和面板菜单是同一个值，哪边改都一样。多选、新增、添加图片、拍照识别、同步、清空未固定是对眼前记录的操作，留在菜单里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardPanelSettingsContent(onBack: () -> Unit, onNavigateToGroups: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("剪贴板面板") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).settingsVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ClipboardPanelDisplaySettings(onNavigateToGroups)
            ClipboardPasteSettings()
            ClipboardHistoryLimitsSetting()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ClipboardPanelDisplaySettings(onNavigateToGroups: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE) }
    var lines by remember { mutableStateOf(clipboardCardMaxLines(prefs.getInt(CLIPBOARD_CARD_MAX_LINES_KEY, 4))) }
    var listLayout by remember { mutableStateOf(prefs.getBoolean(CLIPBOARD_LIST_LAYOUT_KEY, true)) }
    val groups by remember(context) { ClipboardGroupStore.getInstance(context) }.state.collectAsState()
    val images = remember(context) { ClipboardImages.getInstance(context) }
    // Same value as 启用/关闭最近截图 in the clipboard's ⋯ menu; the permission answer comes back on resume.
    val screenshotsOn by images.photoAccess.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { images.refresh() }
    SettingsSection(title = "显示", content = {
        SettingsItem(
            icon = Icons.TwoTone.Folder,
            title = "剪贴板分组",
            subtitle = "自带分类，和自己建的常用语、工作等分组",
            onClick = onNavigateToGroups,
            showArrow = true,
            status = groups.groups.size.takeIf { it > 0 }?.let { "$it 个" },
        )
        SettingsToggleItem(
            icon = Icons.TwoTone.ContentPaste,
            title = "列表样式",
            subtitle = "像豆包输入法：一条一行、细线隔开，最多两行；关掉回到卡片",
            checked = listLayout,
            onCheckedChange = { on -> listLayout = on; prefs.edit().putBoolean(CLIPBOARD_LIST_LAYOUT_KEY, on).apply() },
        )
        Column(Modifier.settingsSearchAnchor("卡片显示行数").fillMaxWidth().padding(16.dp)) {
            Text("卡片显示行数", style = MaterialTheme.typography.titleSmall)
            Text("每条记录最多显示几行文字", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(2, 4, 8).forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("clipboard-card-lines:$option")
                    .selectable(lines == option, role = Role.RadioButton) {
                        lines = option
                        prefs.edit().putInt(CLIPBOARD_CARD_MAX_LINES_KEY, option).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = lines == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("$option 行", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        SettingsToggleItem(
            icon = Icons.TwoTone.PhotoLibrary,
            title = "最近截图",
            subtitle = "「全部」和「图片」里显示最近的截图，需要相册权限",
            checked = screenshotsOn,
            onCheckedChange = { on ->
                if (!on) images.enableRecentScreenshots(false)
                else if (images.hasPhotoAccess()) images.enableRecentScreenshots(true)
                // Same permission screen as the menu; it turns the switch on when access is granted.
                else context.startActivity(Intent(context, ImageAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            costItem = PerformanceCosts.Item.RECENT_SCREENSHOTS,
        )
    })
}

@Composable
private fun ClipboardPasteSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE) }
    // Same switch as the clipboard's own ⋯ menu (same preference).
    var closeAfterPaste by remember { mutableStateOf(clipboardClosesAfterPaste(prefs)) }
    SettingsSection(title = "粘贴", content = {
        SettingsToggleItem(
            icon = Icons.TwoTone.ContentPaste,
            title = "上屏后自动关闭剪贴板",
            subtitle = "关掉后留在剪贴板里，可以连续贴多条",
            checked = closeAfterPaste,
            onCheckedChange = {
                closeAfterPaste = it
                prefs.edit().putBoolean(CLIPBOARD_CLOSE_AFTER_PASTE_KEY, it).apply()
            },
        )
    })
}
