package com.kingzcheung.xime.gloss

import android.content.Context
import com.kingzcheung.xime.settings.IncognitoState
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 英语生词本（移植自青简的「生词本」）：打中文时，上屏的词在释义词典里有英文，就把这个英文记下来，
 * 连同带它上屏的中文、次数和一句例句（上屏后光标前的一小段原文）。设置页可以看、搜、按等级筛、标「认识」、删除。
 *
 * 只在本机记录（filesDir/vocab_book.tsv），不联网。无痕模式、密码框、关掉开关时不记。
 * 打字路径上只做一次释义查表和内存里的修改，键盘收起时才在后台写盘。释义词典没加载（候选注释没选「释义」）时什么都不记。
 */
object VocabularyBook {
    private const val FILE_NAME = "vocab_book.tsv"
    private const val TAG = "VocabularyBook"
    const val PREF_ENABLED = "vocab_book_enabled"
    private const val MAX_EXAMPLE = 40
    private const val MAX_WORDS = 4

    data class Entry(
        val english: String,
        val chinese: String,
        val count: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val known: Boolean,
        val example: String,
    )

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "vocab-book").apply { isDaemon = true } }
    @Volatile private var appContext: Context? = null
    @Volatile private var enabled = true
    @Volatile private var loaded = false
    private val entries = LinkedHashMap<String, Entry>()
    private var dirty = false

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        enabled = prefs(app).getBoolean(PREF_ENABLED, true)
        io.execute { ensureLoaded() }
    }

    private fun prefs(context: Context) = SettingsPreferences.getPrefsPublic(context)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        prefs(context).edit().putBoolean(PREF_ENABLED, value).apply()
    }

    /** 中文上屏后调用。[before] 只在这次上屏确实有英文时才调，用来取例句。 */
    fun onCommit(text: String, before: () -> CharSequence?) {
        if (!enabled || appContext == null || IncognitoState.active) return
        val chinese = text.trim()
        if (chinese.isEmpty() || chinese.length > 8 || chinese.none { isHan(it) }) return
        val english = CandidateGlossary.englishFor(chinese)?.trim() ?: return
        if (english.isEmpty() || english.split(' ').size > MAX_WORDS) return
        val example = runCatching { before()?.toString() }.getOrNull().orEmpty()
            .let { exampleAround(it, chinese) }
        val key = english.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            ensureLoadedLocked()
            val old = entries[key]
            entries[key] = if (old == null) Entry(english, chinese, 1, now, now, false, example)
            else old.copy(chinese = chinese, count = old.count + 1, lastSeen = now,
                example = example.ifEmpty { old.example })
            dirty = true
        }
    }

    /** 键盘收起：后台写盘。 */
    fun flush() {
        if (!dirty) return
        io.execute { save() }
    }

    /** 设置页用：按最近见过排序的全部记录。 */
    fun snapshot(context: Context): List<Entry> {
        init(context)
        synchronized(lock) {
            ensureLoadedLocked()
            return entries.values.sortedByDescending { it.lastSeen }
        }
    }

    fun setKnown(context: Context, english: String, known: Boolean) = edit(context) {
        val key = english.lowercase(Locale.ROOT)
        entries[key]?.let { entries[key] = it.copy(known = known) }
    }

    fun delete(context: Context, english: String) = edit(context) { entries.remove(english.lowercase(Locale.ROOT)) }

    fun clear(context: Context) = edit(context) { entries.clear() }

    private fun edit(context: Context, change: () -> Unit) {
        init(context)
        synchronized(lock) {
            ensureLoadedLocked()
            change()
            dirty = true
        }
        io.execute { save() }
    }

    private fun isHan(ch: Char) = ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF

    /** 例句：光标前那一小段，从上一个句号之后开始，最多 [MAX_EXAMPLE] 字；不含这个词就不要。 */
    internal fun exampleAround(before: String, chinese: String): String {
        val end = before.lastIndexOf(chinese).takeIf { it >= 0 }?.plus(chinese.length) ?: return ""
        val head = before.substring(0, end)
        val start = head.indexOfLast { it in "。！？!?\n" } + 1
        val sentence = head.substring(start).trim()
        return if (sentence.length > MAX_EXAMPLE) "…" + sentence.takeLast(MAX_EXAMPLE) else sentence
    }

    private fun file(): File? = appContext?.filesDir?.let { File(it, FILE_NAME) }

    private fun ensureLoaded() = synchronized(lock) { ensureLoadedLocked() }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val source = file() ?: return
        if (!source.isFile) return
        try {
            source.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size < 6 || line.startsWith("#")) return@forEachLine
                val entry = Entry(
                    english = parts[0], chinese = parts[1], count = parts[2].toIntOrNull() ?: 1,
                    firstSeen = parts[3].toLongOrNull() ?: 0L, lastSeen = parts[4].toLongOrNull() ?: 0L,
                    known = parts[5] == "1", example = parts.getOrNull(6).orEmpty(),
                )
                entries[entry.english.lowercase(Locale.ROOT)] = entry
            }
        } catch (error: Exception) {
            FileLogger.w(TAG, "生词本读取失败：${error.message}")
        }
    }

    private fun save() {
        val target = file() ?: return
        val text = synchronized(lock) {
            if (!dirty) return
            dirty = false
            buildString {
                append("# english\tchinese\tcount\tfirst\tlast\tknown\texample\n")
                for (e in entries.values) {
                    append(clean(e.english)).append('\t').append(clean(e.chinese)).append('\t').append(e.count).append('\t')
                        .append(e.firstSeen).append('\t').append(e.lastSeen).append('\t').append(if (e.known) "1" else "0")
                        .append('\t').append(clean(e.example)).append('\n')
                }
            }
        }
        try {
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } catch (error: Exception) {
            FileLogger.w(TAG, "生词本保存失败：${error.message}")
            synchronized(lock) { dirty = true }
        }
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')
}
