package com.kingzcheung.xime.speech

/**
 * 语音「智能整理」（自用学习版，用户 11-08 要求，本机规则、不联网）：
 * 去掉口头的嗯、呃、额，句首的「啊」「那个」，连说的重复词（「然后然后」「我我我」），以及多余的空格和重复标点。
 * 叠词（谢谢、看看、哈哈）不动：单字只在连说三次以上时才收成一个。
 */
internal object VoiceTidy {
    private val fillers = Regex("[嗯呃唔]+[，,、\\s]*|(?<![\\u4e00-\\u9fff])额+[，,、\\s]*")
    private val leadingFiller = Regex("(^|[。！？!?，,；;\\s])(?:啊|那个|就是说|然后呢)[，,、\\s]+")
    private val repeatedWord = Regex("([\\u4e00-\\u9fff]{2,4})(?:[，,、\\s]*\\1)+")
    private val repeatedChar = Regex("([\\u4e00-\\u9fff])\\1{2,}")
    private val repeatedMark = Regex("([，,。！？!?、；;])[，,、\\s]*\\1+")
    private val markAfterMark = Regex("[，,、]+([。！？!?])")
    private val spaces = Regex("\\s{2,}")
    private val spaceAroundHan = Regex("(?<=[\\u4e00-\\u9fff，。！？、；：])\\s+(?=[\\u4e00-\\u9fff，。！？、；：])")

    fun apply(text: String): String {
        if (text.isBlank()) return text
        var t = text
        t = fillers.replace(t, "")
        t = leadingFiller.replace(t) { it.groupValues[1] }
        t = repeatedWord.replace(t) { it.groupValues[1] }
        t = repeatedChar.replace(t) { it.groupValues[1] }
        t = repeatedMark.replace(t) { it.groupValues[1] }
        t = markAfterMark.replace(t) { it.groupValues[1] }
        t = spaceAroundHan.replace(t, "")
        t = spaces.replace(t, " ")
        return t.trim().trimStart('，', ',', '、', '。')
    }
}
