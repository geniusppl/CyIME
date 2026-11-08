package com.kingzcheung.xime.clipboard

/**
 * 候选栏剪贴板胶囊里，带链接的那一条后面再给「干净链接」和 B 站的 BV 号（用户 11-01：
 * 复制 B 站分享是「标题 + 链接」一整段，想只贴链接；链接后面一大串跟踪参数要去掉，分 P、时间留着）。
 *
 * 只用已经读到的文字，不多读剪贴板，不联网：b23.tv 短链接展不开，只给短链接本身。
 */
internal object ClipboardLinkChoices {
    /** 候选栏的一项：[text] 是显示和粘贴的文字，[source] 是它来自哪一条剪贴板记录（标记已用时用原文）。 */
    data class Choice(val source: Int, val text: String, val action: Action = Action.PASTE)

    /** 自用学习版：胶囊里除了贴文字，最新一条还给「整理」（分好行再贴）和「分词」（打开滑动选词）。 */
    enum class Action { PASTE, TIDY, SPLIT }

    /**
     * 带链接的那条先给干净链接（和原文不同才给）、BV 号（B 站视频才给），原文跟在后面：
     * 「标题 + 链接」很长，原文放前面要横滑才看得到干净链接。没有链接的照旧只有原文。
     */
    fun expand(texts: List<String>): List<Choice> = texts.flatMapIndexed { index, text ->
        linkChoices(text).map { Choice(index, it) } + Choice(index, text)
    }

    /** 同 [expand]，但记下的密码（#95）只给原文一项，不拆链接。 */
    fun expandItems(items: List<ClipboardItem>): List<Choice> = items.flatMapIndexed { index, item ->
        if (item.sensitive) listOf(Choice(index, item.text))
        else linkChoices(item.text).map { Choice(index, it) } + Choice(index, item.text) + toolChoices(index, item.text)
    }

    /** 只给最新一条：能拆出片段或有分隔、换行的，加「整理」；字数够多的加「分词」。 */
    private fun toolChoices(index: Int, text: String): List<Choice> {
        if (index != 0 || text.length < 8) return emptyList()
        val tidy = ClipboardSmartPieces.tidy(text)
        return listOfNotNull(
            Choice(index, tidy, Action.TIDY).takeIf { tidy != text.trim() },
            Choice(index, text, Action.SPLIT),
        )
    }

    /** 候选栏上显示的字：密码显示成圆点，点了照常贴原文。 */
    fun shownText(items: List<ClipboardItem>, choice: Choice): String = when {
        choice.action == Action.TIDY -> "✎ 智能整理"
        choice.action == Action.SPLIT -> "拆 分词"
        items.getOrNull(choice.source)?.sensitive == true -> SENSITIVE_MASK
        else -> choice.text
    }

    /** 记下的密码在剪贴板面板和候选栏上显示成这样（#95）。 */
    const val SENSITIVE_MASK = "••••••••"

    /** 一条剪贴板文字能多给的几项（不含原文本身），没有链接时为空。 */
    fun linkChoices(text: String): List<String> {
        val link = firstLink(text)?.let { raw ->
            val clean = cleanLink(raw)
            val bv = bilibiliVideoId(clean)
            listOfNotNull(clean.takeIf { it != text.trim() }, bv?.takeIf { it != clean && it != text.trim() })
        }.orEmpty()
        // 自用学习版：再加邮箱、电话、验证码、账号密码这些片段（ClipboardSmartPieces），候选栏最多多给 4 项。
        return (link + ClipboardSmartPieces.extract(text)).distinct().filter { it != text.trim() }.take(4)
    }

    // 链接到空白、引号、尖括号或中文标点为止；末尾的英文标点（句号、逗号、右括号）不算链接。
    private val linkPattern = Regex(
        "(?:https?://|www\\.|(?<![\\w.])(?:b23\\.tv|bili2233\\.cn)/)[^\\s<>\"'，。、；：！？（）【】「」《》“”‘’]+",
        RegexOption.IGNORE_CASE)
    private val trailing = Regex("[.,;:!?)\\]}]+$")

    fun firstLink(text: String): String? =
        linkPattern.find(text)?.value?.replace(trailing, "")?.takeIf { it.length > 4 }

    private fun host(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()

    private fun isBilibili(host: String) =
        host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" || host == "bili2233.cn"

    private fun isOneOf(host: String, vararg domains: String) = domains.any { host == it || host.endsWith(".$it") }

    /** B 站只留分 P 和时间；别的网站去掉常见的跟踪参数，其余参数原样留着（有的网站要靠它打开）。 */
    private val bilibiliKeep = setOf("p", "t")
    private val trackingExact = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "igshid", "igsh", "mc_cid", "mc_eid",
        "_hsenc", "_hsmi", "yclid", "spm", "spm_id_from", "vd_source", "unique_k", "share_source", "share_medium",
        "share_plat", "share_session_id", "share_tag", "share_from", "share_id", "sharer_shareid", "sharer_shareinfo",
        "sharer_shareinfo_first", "xhsshare", "app_platform", "app_version", "ignoreengage",
    )
    private val trackingPrefixes = listOf("utm_")

    private fun isTracking(name: String, host: String): Boolean {
        val key = name.lowercase()
        if (key in trackingExact || trackingPrefixes.any { key.startsWith(it) }) return true
        // si 是 YouTube、Spotify 分享带的来源标记；别的网站的 si 可能有用，不动。
        return key == "si" && isOneOf(host, "youtube.com", "youtu.be", "spotify.com")
    }

    /** 去掉跟踪参数后的链接；没有可去的就原样返回。锚点（#）照旧留着。 */
    fun cleanLink(url: String): String {
        val fragmentAt = url.indexOf('#')
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val beforeFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val queryAt = beforeFragment.indexOf('?')
        if (queryAt < 0) return url
        val base = beforeFragment.substring(0, queryAt)
        val host = host(base)
        val bilibili = isBilibili(host)
        val kept = beforeFragment.substring(queryAt + 1).split('&').filter { it.isNotEmpty() }.filter { pair ->
            val name = pair.substringBefore('=')
            if (bilibili) name.lowercase() in bilibiliKeep else !isTracking(name, host)
        }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private val bvPattern = Regex("(?<![0-9A-Za-z])BV1[0-9A-Za-z]{9}(?![0-9A-Za-z])")

    /** B 站视频链接里的 BV 号；b23.tv 短链接里没有，返回 null。 */
    fun bilibiliVideoId(url: String): String? {
        if (!isBilibili(host(url))) return null
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
        return bvPattern.find(path)?.value
    }
}
