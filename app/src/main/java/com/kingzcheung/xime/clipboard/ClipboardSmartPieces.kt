package com.kingzcheung.xime.clipboard

/**
 * 剪贴板智能识别（自用学习版，用户 11-08 要求）：从一整段复制的文字里拎出邮箱、链接、电话、
 * 验证码、账号密码这类「单独要贴」的片段，剪贴板卡片下面和候选栏胶囊里各给一个小按钮，点了只贴那一段。
 *
 * 只看已经读到的文字，本机正则，不联网、不学习。记成密码（sensitive）的记录不拆。
 * 11-08 第二版：值只认不含汉字的一串（「账号密码从一整段话里…」不再被当成密码）；
 * 「邮箱|密码|长令牌」这种竖线、----、换行分隔的卖号格式，每段都拆，长令牌不限 64 字。
 */
internal object ClipboardSmartPieces {
    enum class Kind(val label: String) { EMAIL("邮箱"), LINK("链接"), PHONE("电话"), CODE("验证码"),
        ACCOUNT("账号"), PASSWORD("密码"), KEY("卡密"), TOKEN("令牌") }

    data class Piece(val kind: Kind, val value: String)

    /** 最多给几个片段，免得长文章拆出一大排。 */
    const val MAX_PIECES = 5
    private const val MAX_TEXT = 8_000

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    // 手机号（国内 11 位）和带 + 的国际号码；不认普通长数字（订单号、金额）。
    private val phone = Regex("(?<![0-9A-Za-z.])(?:1[3-9][0-9]{9}|\\+[0-9]{1,3}[ -]?[0-9][0-9 -]{6,14}[0-9])(?![0-9A-Za-z.])")
    // 值：不含汉字、空白和中文标点的一串。
    private const val VALUE = "([\\x21-\\x7E&&[^,;|，。；、]]{4,128})"
    private val accountLabel = Regex(
        "(?:账号|帳號|帐号|账户|用户名|用戶名|登录名|(?<![A-Za-z])(?:account|user(?:name)?|login|id))\\s*[:：=]\\s*$VALUE",
        RegexOption.IGNORE_CASE)
    private val passwordLabel = Regex(
        "(?:密码|密碼|口令|(?<![A-Za-z])(?:password|passwd|pwd|pass))\\s*[:：=]\\s*$VALUE", RegexOption.IGNORE_CASE)
    private val keyLabel = Regex(
        "(?:卡密|激活码|兑换码|兌換碼|授权码|邀请码|(?<![A-Za-z])(?:key|token|code|license))\\s*[:：=]\\s*$VALUE",
        RegexOption.IGNORE_CASE)
    private val emailThenSecret = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}[ \\t]+([\\x21-\\x7E&&[^|]]{6,4096})")
    // 卖号格式的分隔：竖线、三个以上减号、换行、制表符。
    private val fieldSeparator = Regex("\\s*(?:-{3,}|[|｜\\t\\n\\r])\\s*")
    private val field = Regex("[\\x21-\\x7E]{6,4096}")

    fun extract(source: String): List<String> = pieces(source).map { it.value }

    fun pieces(source: String): List<Piece> {
        val text = source.trim()
        if (text.length < 6 || text.length > MAX_TEXT) return emptyList()
        val found = LinkedHashMap<String, Kind>()
        fun add(kind: Kind, value: String) { if (value.isNotBlank() && value != text && value !in found) found[value] = kind }
        val links = linkPattern.findAll(text).map { it.value.replace(trailingPunctuation, "") }.toList()
        fun inLink(v: String) = links.any { v in it && v != it }
        links.forEach { add(Kind.LINK, ClipboardLinkChoices.cleanLink(it)) }
        val emails = email.findAll(text).map { it.value }.filterNot(::inLink).toList()
        emails.forEach { add(Kind.EMAIL, it) }
        // 「邮箱 空格 密码」：紧跟在邮箱后面、隔一个空格的一串当密码。
        emailThenSecret.findAll(text).forEach { m ->
            val v = m.groupValues[1]
            if (!email.matches(v) && v.any { it.isDigit() || it.isUpperCase() }) add(if (v.length > 40) Kind.TOKEN else Kind.PASSWORD, v)
        }
        phone.findAll(text).forEach { add(Kind.PHONE, it.value.replace(" ", "").replace("-", "")) }
        VerificationCodeExtractor.extract(text)?.let { add(Kind.CODE, it) }
        accountLabel.findAll(text).forEach { add(Kind.ACCOUNT, it.groupValues[1].trimEnd('.')) }
        passwordLabel.findAll(text).forEach { add(Kind.PASSWORD, it.groupValues[1].trimEnd('.')) }
        keyLabel.findAll(text).forEach { add(Kind.KEY, it.groupValues[1].trimEnd('.')) }
        // 「邮箱|密码|令牌」：整段只由几段不含汉字的串组成时，每段都给。
        val fields = text.split(fieldSeparator).filter { it.isNotEmpty() }
        if (fields.size in 2..6 && fields.all { field.matches(it) }) {
            fields.forEach { f ->
                when {
                    f in found -> Unit
                    email.matches(f) -> add(Kind.EMAIL, f)
                    f.length > 40 -> add(Kind.TOKEN, f)
                    else -> add(if (found.isEmpty()) Kind.ACCOUNT else Kind.PASSWORD, f)
                }
            }
        }
        return found.entries.map { Piece(it.value, it.key) }.take(MAX_PIECES)
    }

    /**
     * 「智能整理」：竖线、----、制表符分开的字段各占一行，链接去掉跟踪参数，每行去掉首尾空白，
     * 连续空行只留一个。普通句子里的标点不动。
     */
    fun tidy(source: String): String {
        val lines = source.trim().split(Regex("\\s*(?:-{3,}|[|｜\\t])\\s*|\\r?\\n"))
            .map { line -> linkPattern.replace(line.trim()) { ClipboardLinkChoices.cleanLink(it.value.replace(trailingPunctuation, "")) +
                it.value.substring(it.value.replace(trailingPunctuation, "").length) } }
        val out = mutableListOf<String>()
        for (line in lines) if (line.isNotEmpty() || out.lastOrNull()?.isNotEmpty() == true) out += line
        return out.joinToString("\n").trim()
    }

    /** 按钮上显示的字：太长的只留头尾。 */
    fun shortLabel(value: String, max: Int = 18): String =
        if (value.length <= max) value else value.take(max - 7) + "…" + value.takeLast(6)

    private val linkPattern = Regex(
        "(?:https?://|www\\.)[^\\s<>\"'，。、；：！？（）【】「」《》“”‘’]+", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = Regex("[.,;:!?)\\]}]+$")
}
