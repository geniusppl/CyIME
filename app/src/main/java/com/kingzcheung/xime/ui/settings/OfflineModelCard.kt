package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.model.*
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelCatalog
import com.kingzcheung.xime.speech.SpeechModelSelection
import com.kingzcheung.xime.speech.SpeechLanguages
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Three language mappings, one correction scope, and one model comparison at a time. */
@Composable
internal fun OfflineModelCard() {
    val context = LocalContext.current
    val manager = remember { AsrModelManager(context) }
    val models by ModelManager.modelsFlow.collectAsState()
    val downloads by ModelManager.downloadStates.collectAsState()
    val selected = remember { mutableStateMapOf<InputLanguage, String>().apply {
        InputLanguage.speech.forEach { put(it, manager.getSelectedModelId(it)) }
    } }
    var editing by remember { mutableStateOf<InputLanguage?>(null) }
    var ready by remember { mutableStateOf(emptySet<String>()) }
    var xasrReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ModelManager.loadFromRemote(context) } }
    LaunchedEffect(models, downloads) {
        ready = withContext(Dispatchers.IO) {
            SpeechModelSelection.primaryIds.filter { runCatching { manager.selection(it).ready }.getOrDefault(false) }.toSet()
        }
        xasrReady = withContext(Dispatchers.IO) { runCatching { manager.isXasrReady() }.getOrDefault(false) }
        // An unchosen SenseVoice default becomes Paraformer + SenseVoice once Paraformer is downloaded.
        InputLanguage.speech.forEach { selected[it] = withContext(Dispatchers.IO) { manager.getSelectedModelId(it) } }
    }
    fun refresh(language: InputLanguage) { selected[language] = manager.getSelectedModelId(language) }
    fun download(id: String) { ModelManager.getModel(id)?.let { ModelManager.downloadModelInBackground(context, it) } }
    val senseReady = SpeechModelCatalog.SENSEVOICE in ready
    var wholeSentence by remember { mutableStateOf(SettingsPreferences.isSttWholeSentence(context)) }
    // Every line below reads the same rule as the :asr service, so no two lines disagree about which model runs.
    fun pipeline(language: InputLanguage) = SpeechModelSelection.pipeline(selected.getValue(language),
        chinese = language == InputLanguage.CHINESE, wholeSentenceOn = wholeSentence, xasrReady = xasrReady)
    // 整句定稿能用的地方（含只用 SenseVoice 的中文）都由 X-ASR 定稿：放在最上面，下载、在不在用一眼可见。
    val chineseMode = selected.getValue(InputLanguage.CHINESE)
    val wholeSentenceAvailable = SpeechModelSelection.wholeSentenceApplies(chineseMode, chinese = true)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection(title = "中文定稿") {
            Row(Modifier.fillMaxWidth().settingsSearchAnchor("X-ASR").testTag("speech-xasr")
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("X-ASR · 中英", style = MaterialTheme.typography.bodyLarge)
                    Text(when {
                        !xasrReady -> "说完把整句中文再认一遍，错字比 SenseVoice 少三分之一，也更快。"
                        pipeline(InputLanguage.CHINESE).finalizer == SpeechModelCatalog.XASR -> "在用：中文说完由它定稿。"
                        !wholeSentenceAvailable -> "未生效：中文主模型选 SenseVoice，或开启下面的中文二次校正。"
                        else -> "未生效：打开下面的「整句定稿」。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!xasrReady) ModelDownloadAction(SpeechModelCatalog.XASR, downloads) { download(SpeechModelCatalog.XASR) }
        }
        SettingsSection(title = "语言 → 主模型") {
            InputLanguage.speech.forEachIndexed { index, language ->
                val mode = selected.getValue(language)
                val primary = SpeechModelSelection.primary(mode)
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Row(Modifier.fillMaxWidth().testTag("speech-language:${language.id}")
                    .clickable { editing = language }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${language.displayName} · ${SpeechModelSelection.displayName(primary).orEmpty()}",
                            style = MaterialTheme.typography.bodyLarge)
                        Text(buildString {
                            append(speechModelFacts(primary))
                            val pipe = pipeline(language)
                            if (pipe.finalizer == SpeechModelCatalog.XASR) append(" → X-ASR 整句定稿")
                            else if (SpeechModelSelection.hasCorrection(mode)) append(" → SenseVoice 校正")
                            if (pipe.wholeSentence && pipe.finalizer != SpeechModelCatalog.XASR) append(" · 整句定稿")
                            if (primary !in ready || SpeechModelSelection.hasCorrection(mode) && !senseReady) append(" · 待下载")
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "选择${language.displayName}模型")
                }
            }
        }
        SettingsSection(title = "二次校正") {
            // 与其他设置项一致：每种语言一行开关，两行都关即不校正。
            Column(Modifier.fillMaxWidth().testTag("speech-correction")) {
                listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH).forEachIndexed { index, language ->
                    val mode = selected.getValue(language)
                    val enabled = SpeechModelSelection.hasCorrection(mode)
                    val canRefine = SpeechModelSelection.supportsCorrection(mode)
                    val available = canRefine && (senseReady || enabled)
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().testTag("speech-correction:${language.id}")
                        .toggleable(value = enabled, enabled = available, role = Role.Switch,
                            onValueChange = { manager.setRefinementEnabled(it, language); refresh(language) })
                        .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${language.displayName} SenseVoice 二次校正", style = MaterialTheme.typography.bodyLarge)
                            Text(when {
                                !canRefine -> "SenseVoice 已作为主模型，无需再次校正。"
                                !senseReady && !enabled -> "先下载 SenseVoice 模型，再为主模型启用二次校正。"
                                enabled && pipeline(language).finalizer == SpeechModelCatalog.XASR ->
                                    "停顿后复核主模型结果；整句定稿开着、X-ASR 已下载，现在由 X-ASR 代替 SenseVoice 定稿。"
                                else -> "停顿后由 SenseVoice 复核主模型结果，会增加内存占用和等待时间。"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = enabled, enabled = available, onCheckedChange = null, colors = settingsSwitchColors())
                    }
                }
                // 整句定稿只对中文生效：中文开着校正，或中文直接用 SenseVoice 时可用（英文的校正不受它影响）。
                val chineseCorrection = SpeechModelSelection.hasCorrection(chineseMode)
                val senseVoiceChinese = SpeechModelSelection.primary(chineseMode) == SpeechModelCatalog.SENSEVOICE
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().settingsSearchAnchor("整句定稿").testTag("speech-whole-sentence")
                    .toggleable(value = wholeSentence, enabled = wholeSentenceAvailable, role = Role.Switch,
                        onValueChange = { wholeSentence = it; SettingsPreferences.setSttWholeSentence(context, it) })
                    .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("整句定稿", style = MaterialTheme.typography.bodyLarge)
                        Text(when {
                            chineseCorrection -> "中文说完一整句(停顿约 1 秒或点结束)再统一校正一次，更准，说完要多等一会儿；关闭时每 6 秒校正一次。只对中文生效。"
                            senseVoiceChinese -> "中文用 SenseVoice 时，说完(点结束)把整段再识别一次，更准，说完要多等一会儿。只对中文生效。"
                            else -> "只对中文生效：先为中文开启上面的 SenseVoice 二次校正。"
                        },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = wholeSentence, enabled = wholeSentenceAvailable, onCheckedChange = null,
                        colors = settingsSwitchColors())
                }
                if (!senseReady) {
                    ModelDownloadAction(SpeechModelCatalog.SENSEVOICE, downloads) { download(SpeechModelCatalog.SENSEVOICE) }
                }
            }
        }
        Text("配置修改在下一次录音生效。录音中切换语言时，兼容同一模型就继续使用，否则保留已识别文字并切换模型，麦克风继续收音。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { language ->
        SpeechModelPicker(language, SpeechModelSelection.primary(selected.getValue(language)), ready, downloads,
            onSelect = { manager.setFirstPassModel(it, language); refresh(language); editing = null },
            onDownload = ::download, onClose = { editing = null })
    }
}

@Composable
internal fun SpeechModelPicker(language: InputLanguage, selected: String, ready: Set<String>,
    downloads: Map<String, ModelDownloadState>, onSelect: (String) -> Unit, onDownload: (String) -> Unit,
    onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("${language.displayName}主模型") },
        text = {
            SpeechModelChoices(language, selected, ready, downloads, onSelect, onDownload)
        }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}

@Composable
internal fun SpeechModelChoices(language: InputLanguage, selected: String, ready: Set<String>,
    downloads: Map<String, ModelDownloadState>, onSelect: (String) -> Unit, onDownload: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
        SpeechModelSelection.offeredIds(selected).filter { SpeechLanguages.supports(it, language) }
            .forEachIndexed { index, id ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                SpeechModelChoice(language, id, selected == id, id in ready, downloads[id],
                    onSelect = { onSelect(id) }, onDownload = { onDownload(id) })
            }
    }
}

@Composable
private fun SpeechModelChoice(language: InputLanguage, id: String, checked: Boolean, ready: Boolean,
    state: ModelDownloadState?, onSelect: () -> Unit, onDownload: () -> Unit) {
    val name = SpeechModelSelection.displayName(id).orEmpty()
    val progress = (state as? ModelDownloadState.Downloading)?.progress?.coerceIn(0f, 1f)
    val error = state as? ModelDownloadState.Error
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).testTag("speech-choice:${language.id}:$id")
            .selectable(checked, enabled = ready, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(checked, onClick = null, enabled = ready)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(speechModelFacts(id).replace(" MB", "\u00A0MB"), style = MaterialTheme.typography.bodySmall)
                val status = when {
                    ready -> "已下载"
                    progress != null -> "下载中 ${(progress * 100).toInt()}%"
                    error != null -> error.message
                    else -> "未下载"
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color =
                    if (error != null && !ready) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!ready) {
            if (progress != null) {
                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                val action = if (error != null) "重试" else "下载"
                TextButton(onClick = onDownload, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("speech-download:$id").semantics { contentDescription = "$action$name" },
                    contentPadding = PaddingValues(horizontal = 6.dp)) { Text(action) }
            }
        }
    }
}

/** Factual file sizes and output timing; do not invent cross-device latency, RAM or accuracy scores. */
internal fun speechModelFacts(id: String): String = when (id) {
    SpeechModelCatalog.PARAFORMER -> "中英 · 边说边出字 · 226 MB"
    SpeechModelCatalog.SENSEVOICE -> "中英日粤韩 · 停顿出字 · 228 MB"
    SpeechModelCatalog.ZIPFORMER -> "中文 · 边说边出字 · 133 MB"
    SpeechModelCatalog.ZIPFORMER_JA_INT8 -> "日语 · 说话中先出预览 · 153 MB"
    SpeechModelCatalog.ZIPFORMER_JA -> "日语 · 说话中先出预览 · 586 MB"
    SpeechModelCatalog.XASR -> "中英 · 说完整句定稿 · 136 MB"
    else -> ""
}

@Composable
private fun ModelDownloadAction(id: String, downloads: Map<String, ModelDownloadState>, onDownload: () -> Unit) {
    when (val state = downloads[id]) {
        is ModelDownloadState.Downloading -> {
            LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("下载中 ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        }
        else -> {
            TextButton(onClick = onDownload) { Text(if (state is ModelDownloadState.Error) "重试下载" else "下载 ${speechModelFacts(id).substringAfterLast(" · ")}") }
            if (state is ModelDownloadState.Error) Text(state.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SpeechPunctuationSettings() {
    val context = LocalContext.current
    var autoPunctuation by remember { mutableStateOf(SettingsPreferences.isSttAutoPunctuation(context)) }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().settingsSearchAnchor("自动加标点").testTag("speech-auto-punctuation")
        .toggleable(value = autoPunctuation, role = Role.Switch,
            onValueChange = { autoPunctuation = it; SettingsPreferences.setSttAutoPunctuation(context, it) })
        .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("自动加标点", style = MaterialTheme.typography.bodyLarge)
            Text("保留在线接口或中文本地整句定稿返回的标点。关闭后停顿留空格，口述的标点照常输入。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = autoPunctuation, onCheckedChange = null,
            colors = settingsSwitchColors())
    }
    // 只带一部分标点：自动加标点开着时逐项一行开关，默认全开；关掉的那类在停顿处留空格。
    if (autoPunctuation) listOf(
        Triple("逗号、顿号、分号", SettingsPreferences::isSttPunctuationComma, SettingsPreferences::setSttPunctuationComma),
        Triple("句号", SettingsPreferences::isSttPunctuationPeriod, SettingsPreferences::setSttPunctuationPeriod),
        Triple("问号、感叹号", SettingsPreferences::isSttPunctuationQuestion, SettingsPreferences::setSttPunctuationQuestion),
        Triple("末尾的标点", SettingsPreferences::isSttPunctuationTrailing, SettingsPreferences::setSttPunctuationTrailing),
    ).forEach { (title, read, write) ->
        var kept by remember(title) { mutableStateOf(read(context)) }
        HorizontalDivider(modifier = Modifier.padding(start = 32.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().testTag("speech-auto-punctuation:$title")
            .toggleable(value = kept, role = Role.Switch, onValueChange = { kept = it; write(context, it) })
            .padding(start = 32.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(when {
                    title == "末尾的标点" -> if (kept) "整段话最后保留句号、问号等标点。" else "整段话最后不带标点，中间的照留；口述照样出。"
                    kept -> "保留模型打的$title。"
                    else -> "不带$title，停顿处留空格；口述照样出。"
                },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = kept, onCheckedChange = null, colors = settingsSwitchColors())
        }
    }
    var smartTidy by remember { mutableStateOf(SettingsPreferences.isSttSmartTidy(context)) }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().settingsSearchAnchor("智能整理").testTag("speech-smart-tidy")
        .toggleable(value = smartTidy, role = Role.Switch,
            onValueChange = { smartTidy = it; SettingsPreferences.setSttSmartTidy(context, it) })
        .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("智能整理", style = MaterialTheme.typography.bodyLarge)
            Text("去掉嗯、呃、句首的那个，和连说的重复词（然后然后、我我我）。只在本机处理，不联网。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = smartTidy, onCheckedChange = null, colors = settingsSwitchColors())
    }
}

/** 数字转阿拉伯数字：别人提的需求，默认关(VoiceNumbers)。 */
@Composable
internal fun SpeechNumberSettings() {
    val context = LocalContext.current
    var arabic by remember { mutableStateOf(SettingsPreferences.isSttArabicNumbers(context)) }
    Row(Modifier.fillMaxWidth().settingsSearchAnchor("数字转阿拉伯数字").testTag("speech-arabic-numbers")
        .toggleable(value = arabic, role = Role.Switch,
            onValueChange = { arabic = it; SettingsPreferences.setSttArabicNumbers(context, it) })
        .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("数字转阿拉伯数字", style = MaterialTheme.typography.bodyLarge)
            Text("中文说的数字写成 325、3.5、50%；一个、第一、一心一意这类不变。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = arabic, onCheckedChange = null, colors = settingsSwitchColors())
    }
}
