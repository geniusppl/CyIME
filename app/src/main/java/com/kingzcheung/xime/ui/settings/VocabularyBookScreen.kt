package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.RadioButtonUnchecked
import androidx.compose.material.icons.twotone.School
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.gloss.CandidateGlossary
import com.kingzcheung.xime.gloss.EnglishLevels
import com.kingzcheung.xime.gloss.VocabularyBook
import com.kingzcheung.xime.settings.CandidateCommentContent
import com.kingzcheung.xime.settings.CandidateCommentMode
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 英语生词本（移植自青简）：打中文时上屏过的词的英文，带等级（CEFR A1–C2）、次数、中文和例句。
 * 可以搜索、按等级或「还不认识」筛选、标认识、删除。只读写本机文件，不联网。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyBookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Pair<VocabularyBook.Entry, Int>>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(VocabularyBook.isEnabled(context)) }
    var glossOn by remember { mutableStateOf(glossEnabled(context)) }
    var glossRequest by remember { mutableIntStateOf(0) }
    var glossSource by remember { mutableStateOf(CandidateGlossary.source(context)) }
    val hasQingjian = remember { CandidateGlossary.hasQingjian(context) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        rows = withContext(Dispatchers.IO) {
            VocabularyBook.snapshot(context).map { it to EnglishLevels.levelOf(context, it.english) }
        }
    }
    val refresh = { reload++ }

    Scaffold(modifier = Modifier.testTag("vocabulary-book"), containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(title = { Text("英语生词本") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }) { padding ->
        val needle = query.trim().lowercase(Locale.ROOT)
        val shown = rows.filter { (entry, level) ->
            when (filter) {
                "全部" -> true
                "不认识" -> !entry.known
                "已认识" -> entry.known
                "无等级" -> level < 0
                else -> EnglishLevels.label(level) == filter
            } && (needle.isEmpty() || entry.english.lowercase(Locale.ROOT).contains(needle) ||
                entry.chinese.contains(needle) || entry.example.contains(needle))
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SummaryCard(rows)
            }
            if (!glossOn) item {
                SettingsSection(title = "先打开候选释义", content = {
                    SettingsItem(Icons.TwoTone.Translate, "在候选旁显示英文释义",
                        if (glossSource == CandidateGlossary.Source.QINGJIAN && hasQingjian)
                            "生词本靠释义词典认出英文；打开后候选旁会显示英文（青简释义已内置，不用下载）"
                        else "生词本靠释义词典认出英文；打开后候选旁会显示英文，第一次要下载词典",
                        onClick = {
                            SettingsPreferences.setCandidateCommentContent(context, CandidateCommentContent.GLOSS)
                            if (SettingsPreferences.candidateCommentMode(context) == CandidateCommentMode.HIDE)
                                SettingsPreferences.setCandidateCommentMode(context, CandidateCommentMode.AUTO)
                            CandidateGlossary.prepare(context)
                            glossOn = true
                            glossRequest++
                        })
                })
            }
            if (glossOn && glossSource == CandidateGlossary.Source.CYIME) item { GlossDictionaryStatus(glossRequest) }
            if (hasQingjian) item {
                SettingsSection(title = "释义来源", content = {
                    SettingsItem(
                        if (glossSource == CandidateGlossary.Source.CYIME) Icons.TwoTone.CheckCircle else Icons.TwoTone.RadioButtonUnchecked,
                        "CyIME 词典", "取自 ECDICT 和 CC-CEDICT，按常用度筛过，偏准、偏精简；需下载",
                        onClick = {
                            glossSource = CandidateGlossary.Source.CYIME
                            CandidateGlossary.setSource(context, glossSource)
                        })
                    HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsItem(
                        if (glossSource == CandidateGlossary.Source.QINGJIAN) Icons.TwoTone.CheckCircle else Icons.TwoTone.RadioButtonUnchecked,
                        "青简释义", "DeepSeek 大模型生成，收词更多、带词性（n. computer）；已内置。查不到的再用 CyIME 词典",
                        onClick = {
                            glossSource = CandidateGlossary.Source.QINGJIAN
                            CandidateGlossary.setSource(context, glossSource)
                        })
                })
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("搜索英文、中文或例句") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("全部", "不认识", "已认识") + EnglishLevels.LEVELS + "无等级") { name ->
                        FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
                    }
                }
            }
            if (shown.isEmpty()) item {
                SettingsNote(if (rows.isEmpty()) "还没有生词。打中文时选了有英文释义的词，这里就会记下它的英文。"
                    else "没有符合条件的词。")
            }
            items(shown, key = { it.first.english.lowercase(Locale.ROOT) }) { (entry, level) ->
                WordRow(entry, level,
                    onToggleKnown = {
                        scope.launch(Dispatchers.IO) { VocabularyBook.setKnown(context, entry.english, !entry.known) }
                        rows = rows.map { if (it.first === entry) it.first.copy(known = !entry.known) to it.second else it }
                    },
                    onDelete = {
                        scope.launch(Dispatchers.IO) { VocabularyBook.delete(context, entry.english) }
                        rows = rows.filterNot { it.first === entry }
                    })
            }
            item {
                SettingsSection(title = "设置", content = {
                    SettingsToggleItem(Icons.TwoTone.School, "记录生词", "关闭后不再记录新词，已有的保留",
                        checked = enabled, onCheckedChange = { enabled = it; VocabularyBook.setEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsItem(Icons.TwoTone.Delete, "清空生词本", "删除全部记录，不能恢复", onClick = { confirmClear = true })
                })
            }
            item {
                SettingsNote("只在本机记录，不联网；无痕模式和密码框不记。键盘收起时保存。" +
                    "等级来自 CEFR-J 词表 1.5 与 Octanove C1/C2 词表（经青简整理）。")
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空生词本？") },
        text = { Text("全部 ${rows.size} 个词都会删除，不能恢复。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch(Dispatchers.IO) { VocabularyBook.clear(context); withContext(Dispatchers.Main) { refresh() } }
            }) { Text("清空") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}

private fun glossEnabled(context: android.content.Context) =
    SettingsPreferences.candidateCommentContent(context) == CandidateCommentContent.GLOSS &&
        SettingsPreferences.candidateCommentMode(context) != CandidateCommentMode.HIDE

@Composable
private fun SummaryCard(rows: List<Pair<VocabularyBook.Entry, Int>>) {
    val known = rows.count { it.first.known }
    SettingsSection(title = "概览", content = {
        Column(Modifier.padding(16.dp)) {
            Text("${rows.size} 个词", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("已认识 $known · 还在学 ${rows.size - known}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                EnglishLevels.LEVELS.forEachIndexed { index, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${rows.count { it.second == index }}", style = MaterialTheme.typography.titleMedium)
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    })
}

@Composable
private fun WordRow(entry: VocabularyBook.Entry, level: Int, onToggleKnown: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.english, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (entry.known) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (level >= 0) Text(EnglishLevels.label(level), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text("${entry.chinese} · 用过 ${entry.count} 次", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.example.isNotEmpty()) Text("「${entry.example}」", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(onClick = onToggleKnown) {
            Icon(if (entry.known) Icons.TwoTone.CheckCircle else Icons.TwoTone.RadioButtonUnchecked,
                if (entry.known) "标为不认识" else "标为认识",
                tint = if (entry.known) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.TwoTone.Delete, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
