package com.kingzcheung.xime.gloss

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.kingzcheung.xime.R
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.settings.CandidateCommentContent
import com.kingzcheung.xime.settings.CandidateCommentMode
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 候选注释的中英释义：中文候选显示英文，中文键盘里混出的英文候选显示中文。
 *
 * 词典是商店里的可选下载包，只在注释未隐藏且“候选注释内容”选了释义时才映射进来；查表只做在候选
 * 显示层，每屏十几次二分查找，不进逐键解码。词典已加载时查不到释义的候选不显示注释（不退回拼音）。
 * 按住 Shift 时有英文的中文候选中英对调，此时选它上屏英文（[englishFor]）。
 * 只替换显示用的注释：九键选词要用候选数据里的拼音注释定位，候选数据本身不能改。
 */
object CandidateGlossary {
    const val MODEL_ID = "candidate-gloss"
    /** 文件名带版本：v1 有错释义（几号 → heroin），v2 缺大量常用字词，v3 没标出不可靠的单字，v4 没有日常短语，v5 的短语是逐词硬拼，v6 的机器翻译短语一成翻错，v7 还有地名翻错，v8 生僻单字给了一堆没用的释义、常用词组缺得多（v9 未发布），v10 常用实词被过严的筛子挡掉（闲心、小姨），v11 长释义截断、动词加“了”没有英文；装着旧版的设备自动换新。 */
    const val FILE_NAME = "cyime_gloss-v12.tsv"
    private val LEGACY_FILE_NAMES = listOf("cyime_gloss.tsv", "cyime_gloss-v2.tsv", "cyime_gloss-v3.tsv", "cyime_gloss-v4.tsv",
        "cyime_gloss-v5.tsv", "cyime_gloss-v6.tsv", "cyime_gloss-v7.tsv", "cyime_gloss-v8.tsv", "cyime_gloss-v9.tsv", "cyime_gloss-v10.tsv", "cyime_gloss-v11.tsv")
    private const val TAG = "CandidateGlossary"
    private val upgradeStarted = AtomicBoolean(false)

    /** 释义前缀：词典里不可靠的单字释义，显示时去掉，拼整句英文时不用。 */
    private const val APPROX = "~"
    /** 释义前缀：词典里预先翻好的常见短语英文（很有道理 → that makes sense），只显示，不拿来上屏。 */
    private const val PHRASE = "+"
    private const val COMPOSE_MAX_WORD = 6
    /** 只给短语拼英文（用户定：短一点的短语，不做整句）。 */
    private const val COMPOSE_MAX_CHARS = 4
    /**
     * 拼短语时可单独用的字：意思固定、放进短语里不会错的常用字（宁少勿多）。其余单字的释义多义，
     * 拼进去会出错（会 → meet，比 → ratio），一律不用。
     */
    private val COMPOSE_SAFE_CHARS = mapOf(
        '我' to "I", '你' to "you", '他' to "he", '她' to "she", '也' to "also", '不' to "not", '很' to "very",
        '都' to "all", '和' to "and", '又' to "again", '再' to "again", '太' to "too", '最' to "most", '更' to "more",
    )
    private const val COMPOSE_MAX_GLOSS = 40
    /** 拼过的整句释义；换词典时清空。 */
    private val composed = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }

    @Volatile private var table: GlossTable? = null
    @Volatile private var signature: String? = null
    private val loading = AtomicBoolean(false)
    private val revisionState = mutableIntStateOf(0)

    /** 词典加载或释放时递增；界面把它作为 remember 键，加载完成后立即重排注释。 */
    val revision: Int get() = revisionState.intValue

    fun file(context: Context): File = File(ModelStorage.getModelDir(context, MODEL_ID), FILE_NAME)

    /**
     * 私人补充包：按用户自己的输入挑出的释义，只由“装到我的设备”打包放进 APK 资源，不上传、不进公开包。
     * 同一格式（按 UTF-8 字节序的 `词\t释义`），值为 [GlossTable.HIDDEN] 表示这个词不显示释义；有就叠在公开词典上。
     */
    const val PRIVATE_ASSET = "gloss/cyime_gloss-private.tsv"

    private fun openPrivateAsset(context: Context): GlossTable? = try {
        context.assets.open(PRIVATE_ASSET).use { GlossTable.fromBuffer(ByteBuffer.wrap(it.readBytes())) }
    } catch (_: java.io.FileNotFoundException) {
        null
    } catch (error: Exception) {
        FileLogger.w(TAG, "私人释义补充包加载失败：${error.message}")
        null
    }

    fun isInstalled(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    /**
     * 释义来源（自用学习版加的）：CYIME = CyIME 自己的词典（ECDICT + CC-CEDICT，需下载）；
     * QINGJIAN = 青简的英语释义表（LLM 生成、带词性，随 APK 打包，不用下载），中文候选优先查它，
     * 查不到、以及英文候选的中文释义，再退回 CyIME 词典（已下载时）。
     */
    enum class Source { CYIME, QINGJIAN }

    private const val PREF_SOURCE = "candidate_gloss_source"
    const val QINGJIAN_ASSET = "gloss/qingjian_gloss_en.tsv"

    fun source(context: Context): Source =
        SettingsPreferences.getPrefsPublic(context).getString(PREF_SOURCE, null)
            ?.let { stored -> Source.entries.firstOrNull { it.name == stored } } ?: Source.CYIME

    fun setSource(context: Context, value: Source) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(PREF_SOURCE, value.name).apply()
        prepare(context)
    }

    /** 这个 APK 里有没有打包青简释义表。 */
    fun hasQingjian(context: Context): Boolean = try {
        context.assets.open(QINGJIAN_ASSET).use { true }
    } catch (_: Exception) {
        false
    }

    /** 把打包的青简表复制到私有目录（App 更新后重新复制一次），之后内存映射读取。 */
    private fun qingjianFile(context: Context): File? = try {
        val target = File(File(context.filesDir, "gloss"), "qingjian_gloss_en.tsv")
        val stamp = File(target.parentFile, "qingjian_gloss_en.stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!target.isFile || !stamp.isFile || stamp.readText() != version) {
            copyAsset(context, target)
            stamp.writeText(version)
        }
        target
    } catch (error: Exception) {
        FileLogger.w(TAG, "青简释义表准备失败：${error.message}")
        null
    }

    private fun copyAsset(context: Context, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        context.assets.open(QINGJIAN_ASSET).use { input -> temp.outputStream().use { input.copyTo(it) } }
        if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
    }

    /** 按当前设置在后台加载或释放词典；只读偏好和文件信息，可在主线程调用。 */
    fun prepare(context: Context) {
        val source = file(context)
        val wanted = SettingsPreferences.candidateCommentContent(context) == CandidateCommentContent.GLOSS &&
            SettingsPreferences.candidateCommentMode(context) != CandidateCommentMode.HIDE
        val useQingjian = source(context) == Source.QINGJIAN && hasQingjian(context)
        if (wanted && !source.isFile) upgradeFromLegacy(context)
        if (!wanted || (!source.isFile && !useQingjian)) {
            if (table != null) revisionState.intValue++
            table = null
            signature = null
            return
        }
        val current = "${source.absolutePath}:${source.length()}:${source.lastModified()}:qingjian=$useQingjian"
        if (current == signature || !loading.compareAndSet(false, true)) return
        thread(name = "candidate-gloss", isDaemon = true) {
            try {
                val corrections = context.resources.openRawResource(R.raw.candidate_gloss_corrections)
                val privateTable = openPrivateAsset(context)
                val qingjian = if (useQingjian) qingjianFile(context)?.let { GlossTable.open(it) } else null
                val base = if (source.isFile) GlossTable.open(source, readCorrections(corrections)) else null
                // 查表顺序：私人补充包 → 青简（选了时）→ CyIME 词典
                qingjian?.overlay = privateTable
                val opened = base ?: qingjian ?: error("没有可用的释义词典")
                if (base != null) base.overlay = qingjian ?: privateTable
                synchronized(composed) { composed.clear() }
                table = opened
                signature = current
                revisionState.intValue++
            } catch (error: Exception) {
                FileLogger.w(TAG, "释义词典加载失败：${error.message}")
            } finally {
                loading.set(false)
            }
        }
    }

    /** 用户已选释义、装的是旧版词典：后台换成新版（只下载一次），下完删掉旧文件并重新加载。 */
    private fun upgradeFromLegacy(context: Context) {
        val dir = ModelStorage.getModelDir(context, MODEL_ID)
        val legacy = LEGACY_FILE_NAMES.map { File(dir, it) }.filter { it.isFile }
        if (legacy.isEmpty() || !upgradeStarted.compareAndSet(false, true)) return
        val model = com.kingzcheung.xime.model.ModelManager.getModel(MODEL_ID) ?: return
        val app = context.applicationContext
        com.kingzcheung.xime.model.ModelManager.downloadModelInBackground(app, model, onProgress = { state ->
            when (state) {
                is com.kingzcheung.xime.model.ModelDownloadState.Complete -> {
                    legacy.forEach { it.delete() }
                    prepare(app)
                }
                is com.kingzcheung.xime.model.ModelDownloadState.Error -> upgradeStarted.set(false)
                else -> Unit
            }
        })
    }

    /** 词典已加载（释义模式且注释未隐藏）。 */
    val isLoaded: Boolean get() = table != null

    /** 与词典生成器共用的精确词条；已下载的 v12 也在查表时应用。 */
    internal fun readCorrections(source: InputStream): Map<String, String> =
        source.bufferedReader().useLines { lines ->
            lines.associate { line ->
                val (word, gloss) = line.split('\t', limit = 2)
                word to gloss
            }
        }

    /** 物理键盘的 Shift 正被按住；屏幕键盘看 ShiftMode.HELD。按住时候选中英对调。 */
    val physicalShiftHeld = mutableStateOf(false)

    /** 九键正按住“下一项”（代替按住 Shift）：按住期间中英对调，松手或上屏英文时恢复。 */
    val t9NextHeld = mutableStateOf(false)

    /** 任一种“按住 Shift”生效中：屏幕 Shift 键按住（[screenShiftHeld]）、物理 Shift、九键长按“下一项”，或从它拖到候选上。 */
    /** 正从九键“下一项”拖着经过候选：此刻松手就上屏英文，候选先显示成英文。 */
    val t9DragSwap = mutableStateOf(false)

    fun swapHeld(screenShiftHeld: Boolean): Boolean =
        screenShiftHeld || physicalShiftHeld.value || t9NextHeld.value || t9DragSwap.value

    /** 注释换成释义；词典未加载时原样返回，已加载但查不到的候选不显示注释。 */
    fun annotate(texts: List<String>, comments: List<String>): List<String> = annotate(table, texts, comments)

    fun annotate(text: String, comment: String): String = annotate(table, listOf(text), listOf(comment)).first()

    /** 按住 Shift 选这条候选时上屏的英文；没有完整英文时为 null，照常上中文。 */
    fun englishFor(text: String): String? = table?.let { englishFor(it, text) }

    /** 按住 Shift 时的显示：有英文的中文候选显示英文、中文退作注释，其余候选同 [annotate]。 */
    fun swapped(texts: List<String>, comments: List<String>): Pair<List<String>, List<String>> =
        swapped(table, texts, comments)

    internal fun annotate(table: GlossTable?, texts: List<String>, comments: List<String>): List<String> {
        if (table == null) return comments
        return texts.mapIndexed { index, text ->
            val note = comments.getOrNull(index)
            if (note == "时间戳") note else gloss(table, text).orEmpty()
        }
    }

    /**
     * 运行时把任意 2～4 字候选逐词拼英文（[compose]）默认关：候选里大量是说不通的临时组合（对付一口 →
     * handle readily），按字数拼会出错英文。日常短语改由词典预先按词库常见度挑好拼好（[PHRASE] 前缀），App 只查表。
     */
    @Volatile var composeEnabled = false

    /** 候选的释义：词典里有就用（去掉不可靠标记）；没有时只在 [composeEnabled] 打开后按词切开逐词拼英文。 */
    internal fun gloss(table: GlossTable, text: String): String? {
        if (isLetterAbbreviation(text)) return null
        return table.lookup(keyOf(text))?.removePrefix(APPROX)?.removePrefix(PHRASE) ?: derived(table, text)
            ?: if (composeEnabled) compose(table, text) else null
    }

    /**
     * 意思完全确定、词典里却不会逐条收的候选：句尾语气词不改意思（早啊 → morning，好了吗 → done?），
     * 数字加时间或次数单位（九点 → nine o'clock，三天 → three days）。只显示，不拿来上屏（[englishFor] 只查词典）。
     */
    internal fun derived(table: GlossTable, text: String): String? {
        if (text.length < 2 || text.any { it.code < 0x80 }) return null
        val stem = text.dropLast(1)
        val last = text.last()
        val question = last == '吗' || last == '么'   // 你知道么 = 你知道吗
        // 单字加语气词（懒啊、好呀）只在这个字有可靠释义、又不是“是、有、在”和代词这类虚字时套（是啊 不是 “is”）。
        val singleOk = stem.length == 1 && !question && stem[0] !in SINGLE_STEM_EXCLUDED
        if ((stem.length >= 2 || singleOk) && (last in MODAL_PARTICLES || question)) {
            val base = table.lookup(stem)?.takeUnless { it.startsWith(APPROX) }?.removePrefix(PHRASE)?.let { stripPartOfSpeech(it) }
                ?: numberGloss(stem) ?: return null
            return if (question) "${base.trimEnd('?', '!')}?" else base
        }
        return numberGloss(text)
    }

    private const val MODAL_PARTICLES = "啊呀吧呢嘛哦啦呗喽"
    private const val SINGLE_STEM_EXCLUDED = "是有在对我你他她它这那的了就也都还又不没"
    private val NUMBER_UNITS = listOf(
        "分钟" to ("minute" to "minutes"), "小时" to ("hour" to "hours"), "点" to ("o'clock" to "o'clock"),
        "天" to ("day" to "days"), "年" to ("year" to "years"), "次" to ("time" to "times"), "岁" to ("year old" to "years old"),
    )
    private val ENGLISH_ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val ENGLISH_TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** 一到九十九的中文数字（或阿拉伯数字）加单位：九点 → nine o'clock，两个小时 不算（中间有量词）。 */
    internal fun numberGloss(text: String): String? {
        if (text.endsWith("点半")) {
            val hour = chineseNumber(text.dropLast(2))?.takeIf { it in 1..12 } ?: return null
            return "half past ${ENGLISH_ONES[hour]}"
        }
        val (unit, words) = NUMBER_UNITS.firstOrNull { text.endsWith(it.first) } ?: return null
        val value = chineseNumber(text.dropLast(unit.length)) ?: return null
        if (unit == "点" && value !in 1..12) return null
        val number = if (value < 20) ENGLISH_ONES[value] else ENGLISH_TENS[value / 10] + if (value % 10 != 0) "-" + ENGLISH_ONES[value % 10] else ""
        return "$number ${if (value == 1) words.first else words.second}"
    }

    private fun chineseNumber(text: String): Int? {
        if (text.isEmpty() || text.length > 3) return null
        if (text.all { it in '0'..'9' }) return text.toInt().takeIf { it in 1..99 }
        val digits = "零一二三四五六七八九"
        fun digit(ch: Char): Int? = if (ch == '两') 2 else digits.indexOf(ch).takeIf { it >= 0 }
        val ten = text.indexOf('十')
        val value = when {
            ten < 0 -> if (text.length == 1 && text[0] != '零') digit(text[0]) else null
            text.count { it == '十' } > 1 -> null
            else -> {
                val tens = if (ten == 0) 1 else if (ten == 1) digit(text[0])?.takeIf { it >= 2 } else null
                val ones = if (ten == text.length - 1) 0 else if (ten == text.length - 2) digit(text.last())?.takeIf { it >= 1 } else null
                if (tens == null || ones == null) null else tens * 10 + ones
            }
        }
        return value?.takeIf { it in 1..99 }
    }

    /**
     * 单个字母、三个字母以内的英文和全大写缩写不给中文释义：词典把它们当缩写，释义多是生僻全称
     * （s → 标量，abc → 预定章程，KFC 也一样），中文键盘里打的大写字母和缩写没有可信的中文意思。
     */
    internal fun isLetterAbbreviation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.code < 0x80 }) return false
        val letters = trimmed.filter { it.isLetter() }
        return letters.length <= 3 || letters.all { it.isUpperCase() }
    }

    /**
     * 2～4 字的短语候选（我闹钟 → I alarm clock）：从左往右每次取词典里最长的两字以上词，单字只用
     * [COMPOSE_SAFE_CHARS] 里的。有一块切不出来就不显示，宁缺勿错。
     * 结果按文字缓存，候选栏重组时不重复查表。
     */
    internal fun compose(table: GlossTable, text: String): String? {
        if (text.length < 2 || text.any { it.code < 0x80 } || text.length > COMPOSE_MAX_CHARS) return null
        // 缓存只服务当前装载的词典（测试会传别的表）。
        val cache = composed.takeIf { table === this.table }
        cache?.let { synchronized(it) { it[text]?.let { hit -> return hit.ifEmpty { null } } } }
        val parts = ArrayList<String>()
        var start = 0
        var complete = true
        while (start < text.length) {
            var matched = 0
            for (length in minOf(COMPOSE_MAX_WORD, text.length - start) downTo 2) {
                val value = table.lookup(text.substring(start, start + length)) ?: continue
                if (value.startsWith(APPROX) || value.startsWith(PHRASE)) continue
                parts += value.removeSuffix("…").trimEnd()
                matched = length
                break
            }
            if (matched == 0) COMPOSE_SAFE_CHARS[text[start]]?.let { parts += it; matched = 1 }
            if (matched == 0) { complete = false; break }
            start += matched
        }
        val result = if (complete && parts.size > 1) parts.joinToString(" ").let {
            if (it.length > COMPOSE_MAX_GLOSS) it.take(COMPOSE_MAX_GLOSS).trimEnd() + "…" else it
        } else ""
        cache?.let { synchronized(it) { it[text] = result } }
        return result.ifEmpty { null }
    }

    internal fun englishFor(table: GlossTable, text: String): String? {
        // 本身是英文的候选直接上屏它自己；被截断的释义（以 … 结尾）不是完整英文，不拿来上屏；
        // 逐词拼出来的整句英文不是译文，也不上屏，只做显示参考。
        if (text.isBlank() || text.all { it.code < 0x80 }) return null
        return table.lookup(keyOf(text))?.removePrefix(APPROX)
            ?.takeUnless { it.isBlank() || it.endsWith("…") || it.startsWith(PHRASE) }
            ?.let { stripPartOfSpeech(it) }?.takeUnless { it.isBlank() }
    }

    private val PART_OF_SPEECH = Regex("^(?:[a-z]{1,6}\\.\\s+)+")

    /** 青简释义带词性（n. computer）：显示时保留，上屏英文和记生词时去掉。 */
    internal fun stripPartOfSpeech(value: String): String = value.replace(PART_OF_SPEECH, "").trim()

    internal fun swapped(table: GlossTable?, texts: List<String>, comments: List<String>): Pair<List<String>, List<String>> {
        if (table == null) return texts to comments
        val notes = annotate(table, texts, comments)
        val words = ArrayList<String>(texts.size)
        val shown = ArrayList<String>(texts.size)
        texts.forEachIndexed { index, text ->
            val english = englishFor(table, text)
            words += english ?: text
            shown += if (english != null) text else notes[index]
        }
        return words to shown
    }

    internal fun keyOf(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.all { it.code < 0x80 }) trimmed.lowercase(Locale.ROOT) else trimmed
    }
}

/** 按 UTF-8 字节序排好的 `词\t释义` 行；内存映射，堆上只留每行起点。 */
internal class GlossTable private constructor(
    private val buffer: ByteBuffer,
    private val starts: IntArray,
    private val corrections: Map<String, String>,
) {
    val size: Int get() = starts.size

    /** 叠在上面的私人补充包：先查它，查到 [HIDDEN] 就不显示释义。 */
    @Volatile var overlay: GlossTable? = null

    fun lookup(key: String): String? {
        if (key.isEmpty()) return null
        overlay?.lookup(key)?.let { return it.takeUnless { value -> value == HIDDEN } }
        corrections[key]?.let { return it }
        val wanted = key.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val order = compareKey(starts[mid], wanted)
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> return value(starts[mid] + wanted.size + 1)
            }
        }
        return null
    }

    private fun compareKey(start: Int, wanted: ByteArray): Int {
        var i = 0
        while (true) {
            val stored = buffer.get(start + i).toInt() and 0xff
            val ended = stored == TAB
            if (i == wanted.size) return if (ended) 0 else 1
            if (ended) return -1
            val expected = wanted[i].toInt() and 0xff
            if (stored != expected) return stored - expected
            i++
        }
    }

    private fun value(start: Int): String? {
        var end = start
        while (end < buffer.limit() && buffer.get(end).toInt() != NEWLINE) end++
        if (end <= start) return null
        val bytes = ByteArray(end - start)
        for (i in bytes.indices) bytes[i] = buffer.get(start + i)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        /** 私人补充包里“这个词不显示释义”的值。 */
        const val HIDDEN = "-"
        private const val TAB = '\t'.code
        private const val NEWLINE = '\n'.code
        private const val COMMENT = '#'.code

        fun open(file: File, corrections: Map<String, String> = emptyMap()): GlossTable {
            val buffer = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            return fromBuffer(buffer, corrections)
        }

        fun fromBuffer(buffer: ByteBuffer, corrections: Map<String, String> = emptyMap()): GlossTable {
            var starts = IntArray(1 shl 16)
            var count = 0
            var lineStart = 0
            val limit = buffer.limit()
            while (lineStart < limit) {
                var end = lineStart
                var hasTab = false
                while (end < limit) {
                    val b = buffer.get(end).toInt()
                    if (b == NEWLINE) break
                    if (b == TAB) hasTab = true
                    end++
                }
                if (hasTab && buffer.get(lineStart).toInt() != COMMENT) {
                    if (count == starts.size) starts = starts.copyOf(count * 2)
                    starts[count++] = lineStart
                }
                lineStart = end + 1
            }
            return GlossTable(buffer, starts.copyOf(count), corrections)
        }
    }
}
