package com.kingzcheung.xime.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.PersonOutline
import androidx.compose.material.icons.twotone.PrivacyTip
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial
import com.kingzcheung.xime.model.DownloadMirrors
import com.kingzcheung.xime.update.AppUpdateCatalog
import com.kingzcheung.xime.util.FileLogger

data class LicenseItem(
    val name: String,
    val license: String,
    val url: String
)

object AppInfo {
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE
    val gitHash: String = BuildConfig.GIT_HASH
    
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    
    val dependencies = listOf(
        LicenseItem(
            name = "CyIME(由 Cyletix 维护开发)",
            license = "GPL-3.0",
            url = "https://github.com/Cyletix/CyIME/blob/main/LICENSE"
        ),
        LicenseItem(
            name = "librime",
            license = "BSD-3-Clause",
            url = "https://github.com/rime/librime"
        ),
        LicenseItem(
            name = "AndroidX Core",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx"
        ),
        LicenseItem(
            name = "Jetpack Compose",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/compose"
        ),
        LicenseItem(
            name = "Material Design Icons",
            license = "Apache-2.0",
            url = "https://fonts.google.com/icons"
        ),
        LicenseItem(
            name = "Kotlin",
            license = "Apache-2.0",
            url = "https://kotlinlang.org"
        ),
        LicenseItem(
            name = "Navigation Compose",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/compose/navigation"
        ),
        LicenseItem(
            name = "Lifecycle",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/lifecycle"
        ),
        LicenseItem(
            name = "Boost",
            license = "BSL-1.0",
            url = "https://www.boost.org"
        ),
        LicenseItem(
            name = "OpenCC (Open Chinese Convert)",
            license = "Apache-2.0",
            url = "https://github.com/BYVoid/OpenCC"
        ),
        LicenseItem(
            name = "KaTeX",
            license = "MIT",
            url = "https://github.com/KaTeX/KaTeX"
        ),
        LicenseItem(
            name = "Xime(CyIME 基于它开发)",
            license = "GPL-3.0",
            url = "https://github.com/ximeiorg/Xime"
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(
    onBack: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToCredits: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToHandwritingCapture: () -> Unit = {},
    onNavigateToStorageSpace: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var verboseLoggingEnabled by remember {
        mutableStateOf(SettingsPreferences.isVerboseLoggingEnabled(context))
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .settingsVerticalScroll()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App 信息
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "CyIME",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Cyletix 开发的 Android 输入法。拼音、九键、双拼、五笔、仓颉、注音、日语、英文都能打；词库、手写、语音、拍照识字和实时字幕都在手机上运行，不联网也能全部使用。以 GNU GPLv3 开源，致谢与许可见下方。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "版本 ${AppInfo.versionName} (${AppInfo.versionCode})" +
                                BuildConfig.LEARN_BUILD.takeIf { it.isNotEmpty() }?.let { " · 测试版 learn-$it" }.orEmpty(),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "构建: ${AppInfo.gitHash}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 国内打开魔搭上的同一份安装包，海外打开 GitHub 发布页。
                                .clickable { uriHandler.openUri(AppUpdateCatalog.releasesPage(DownloadMirrors.prefersDomestic(context))) },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "查看最新版本",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            
            // 作者与源代码使用同一种完整卡片。
                AboutLinkCard(
                    title = "Cyletix · 维护与开发",
                    subtitle = "github.com/Cyletix",
                    icon = Icons.TwoTone.PersonOutline,
                    onClick = { uriHandler.openUri("https://github.com/Cyletix") },
                )
                AboutLinkCard(
                    title = "CyIME 源代码",
                    subtitle = "github.com/Cyletix/CyIME",
                    icon = Icons.TwoTone.Code,
                    onClick = { uriHandler.openUri("https://github.com/Cyletix/CyIME") },
                )

                AppUpdateSection()

            // 链接项
                SettingsSection(title = "信息与工具") {
                        SettingsItem(
                            icon = Icons.TwoTone.Favorite,
                            title = "鸣谢",
                            onClick = onNavigateToCredits
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.TwoTone.PrivacyTip,
                            title = "隐私策略",
                            onClick = onNavigateToPrivacy
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.TwoTone.Description,
                            title = "开源许可证",
                            onClick = onNavigateToLicenses
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        // 使用文档从设置首页收到这里（内容较旧，少有人点）。
                        SettingsItem(
                            icon = Icons.TwoTone.Description,
                            title = "使用文档",
                            onClick = { uriHandler.openUri("https://github.com/Cyletix/CyIME/blob/main/docs/usage.md") }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.TwoTone.BugReport,
                            title = "日志查看器",
                            onClick = onNavigateToLogViewer
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.TwoTone.Storage,
                            title = "存储空间",
                            onClick = onNavigateToStorageSpace
                        )
                        if (BuildConfig.DEBUG) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            SettingsItem(
                                icon = Icons.TwoTone.BugReport,
                                title = "调试日志",
                                onClick = {},
                                trailing = {
                                    Switch(
                                        checked = verboseLoggingEnabled,
                                        onCheckedChange = { enabled ->
                                            verboseLoggingEnabled = enabled
                                            SettingsPreferences.setVerboseLoggingEnabled(context, enabled)
                                            FileLogger.setVerboseLoggingEnabled(enabled)
                                        }
                                    )
                                }
                            )
                        }
                }
            
            // 设备信息
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "设备信息",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        InfoRow(label = "系统版本", value = AppInfo.androidVersion)
                        InfoRow(label = "设备型号", value = AppInfo.deviceModel)
                    }
                }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyContent(
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("隐私策略") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsGutterScroll(listState)
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "CyIME隐私策略",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        PrivacySection(
                            title = "数据收集",
                            content = "CyIME 默认使用本地输入与识别。主动启用在线语音或其他联网服务时，相应音频或文本会发送给您选择的服务商。手写贡献和滑行练习须同意 CC0 1.0 才能向魔搭上传。手写包含题目、笔迹、时间、识别结果（如有）、机型、版本及随机编号。滑行只在本次开启贡献的练习里发送题目、布局、轨迹及随机编号。贡献样本可公开使用。在「贡献词库」页分享或上传时，只有列表里勾选的词、拼音和次数，用于改进 CyIME 词库。平时手写和打字的原文不会上传。"
                        )
                        
                        PrivacySection(
                            title = "本地存储",
                            content = "CyIME 默认在本地存储设置、词库和剪贴板历史。主动启用剪贴板同步插件时，剪贴板内容会按插件配置传输；备份只导出到您选择的本地文件。您可以随时清除本地数据。"
                        )
                        
                        PrivacySection(
                            title = "网络权限",
                            content = "网络权限用于下载输入方案和模型、检查和下载新版本，以及您主动启用的在线服务。检查更新只向 GitHub 查询版本号，不发送输入内容；只有您点「检查更新」或打开「自动检查更新」时才联网查询，新版本由系统安装程序安装。本地 Rime、手写和离线语音模型在设备上运行。"
                        )
                        
                        PrivacySection(
                            title = "输入内容",
                            content = "本地输入由开源 Rime 引擎处理。在线语音等可选服务的数据处理规则取决于所选服务商，请查看其隐私政策。"
                        )
                        
                        PrivacySection(
                            title = "剪贴板",
                            content = "剪贴板历史默认保存在本地；同步功能由您启用的插件提供。您可以随时查看、删除或清除本地记录。"
                        )
                        
                        PrivacySection(
                            title = "开源",
                            content = "CyIME是开源软件，源代码公开可审计。您可以在 GitHub 上查看完整源代码。"
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "更新日期：2026年9月23日",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, content: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = content,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 20.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesContent(
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("开源许可证") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsGutterScroll(listState)
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(AppInfo.dependencies) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            uriHandler.openUri(item.url)
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.license,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun AboutLinkCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
