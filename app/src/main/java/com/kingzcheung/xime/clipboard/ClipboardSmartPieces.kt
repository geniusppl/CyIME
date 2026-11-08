package com.kingzcheung.xime.clipboard

/**
 * 剪贴板智能识别（自用学习版加的，用户 11-08 要求）：从一整段复制的文字里拎出邮箱、链接、电话、
 * 验证码、账号密码这类「单独要贴」的片段，剪贴板卡片下面和候选栏胶囊里各给一个小按钮，点了只贴那一段。
 *
 * 只看已经读到的文字，本机正则，不联网、不学习。记成密码（sensitive）的记录不拆。
 */
internal object ClipboardSmartPieces {
    /** 最多给几个片段，免得长文章拆出一大排。 */
    const val MAX_PIECES = 6
    private const val MAX_TEXT = 4_000

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    // 手机号（国内 11 位）和带 + 的国际号码；不认普通长数字（订单号、金额）。
    private val phone = Regex("(?<![0-9A-Za-z])(?:1[3-9][0-9]{9}|\\+[0-9]{1,3}[ -]?[0-9][0-9 -]{6,14}[0-9])(?![0-9A-Za-z])")
    // 「账号：xxx」「密码: xxx」「卡密 xxx」这类标了名字的值；英文标签要整词且后面带冒号或等号。
    private val labeled = Regex(
        "(?:(?:账号|帳號|帐号|账户|用户名|用戶名|登录名|密码|密碼|卡密|激活码|兑换码|兌換碼|授权码|邀请码|口令)\\s*[:：=]?|" +
            "\\b(?:account|user(?:name)?|login|password|passwd|pwd|pass|key|code|token)\\s*[:：=])\\s*([^\\s，,；;、|]{4,64})",
        RegexOption.IGNORE_CASE)
    // 卖号常见的「邮箱----密码」「账号|密码」分隔。
    private val separators = Regex("-{3,}|\\s+|[|｜，,；;、]")
    private val leadingLabel = Regex("^[^:：]{0,12}[:：]")
    private val token = Regex("[A-Za-z0-9!@#$%^&*._+=~-]{6,64}")

    fun extract(source: String): List<String> {
        val text = source.trim()
        if (text.length < 6 || text.length > MAX_TEXT) return emptyList()
        val found = LinkedHashSet<String>()
        val links = linkPattern.findAll(text).map { it.value.replace(trailingPunctuation, "") }.toList()
        links.forEach { found += ClipboardLinkChoices.cleanLink(it) }
        email.findAll(text).forEach { m -> if (links.none { m.value in it }) found += m.value }
        phone.findAll(text).forEach { found += it.value.replace(" ", "").replace("-", "") }
        VerificationCodeExtractor.extract(text)?.let { found += it }
        labeled.findAll(text).forEach { m ->
            val value = m.groupValues[1].trimEnd('.', '。')
            if (links.none { value in it }) found += value
        }
        // 没标名字的密码、卡密：字母和数字都有的一串（不是链接、邮箱里的一截）。
        text.split(separators).map { it.replace(leadingLabel, "").trim() }.forEach { word ->
            if (token.matches(word) && word.any { it.isLetter() } && word.any { it.isDigit() } &&
                '@' !in word && links.none { word in it } && !word.contains("://")) found += word
        }
        return found.filter { it.isNotBlank() && it != text }.take(MAX_PIECES)
    }

    private val linkPattern = Regex(
        "(?:https?://|www\\.)[^\\s<>\"'，。、；：！？（）【】「」《》“”‘’]+", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = Regex("[.,;:!?)\\]}]+$")
}
