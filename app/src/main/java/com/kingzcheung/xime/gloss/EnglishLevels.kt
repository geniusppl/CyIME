package com.kingzcheung.xime.gloss

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.util.Locale

/**
 * 英文词汇等级（CEFR A1–C2），给生词本标级用。数据来自青简 assets/levels/levels-en.tsv（CEFR-J 1.5 + Octanove C1/C2）。
 * 第一次用时从 assets 读进内存（约 9 千词），只在生词本记录和设置页里查，不进逐键解码。
 */
object EnglishLevels {
    private const val ASSET = "vocab/levels-en.tsv"
    private const val TAG = "EnglishLevels"
    val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")

    @Volatile private var words: Map<String, Int>? = null

    private fun load(context: Context): Map<String, Int> {
        words?.let { return it }
        synchronized(this) {
            words?.let { return it }
            val map = HashMap<String, Int>(12_000)
            try {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val level = LEVELS.indexOf(line.substring(tab + 1).trim())
                        if (level < 0) continue
                        val word = line.substring(0, tab).lowercase(Locale.ROOT)
                        val old = map[word]
                        if (old == null || level < old) map[word] = level
                    }
                }
            } catch (error: Exception) {
                FileLogger.w(TAG, "词汇等级表加载失败：${error.message}")
            }
            words = map
            return map
        }
    }

    /** 英文释义的等级下标（0 = A1 … 5 = C2），查不到为 -1。短语、带 to / a 的释义先整条查，再去掉冠词和 to 查，最后取第一个意思。 */
    fun levelOf(context: Context, english: String): Int {
        val table = load(context)
        for (candidate in candidates(english)) table[candidate]?.let { return it }
        return -1
    }

    fun label(level: Int): String = LEVELS.getOrNull(level).orEmpty()

    internal fun candidates(english: String): List<String> {
        val base = english.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?', '…')
        val first = base.split(';', ',', '/', '；', '，').first().trim()
        val out = LinkedHashSet<String>()
        for (text in listOf(base, first)) {
            if (text.isEmpty()) continue
            out += text
            val stripped = text.removePrefix("to ").removePrefix("a ").removePrefix("an ").removePrefix("the ").trim()
            if (stripped.isNotEmpty()) out += stripped
        }
        return out.toList()
    }
}
