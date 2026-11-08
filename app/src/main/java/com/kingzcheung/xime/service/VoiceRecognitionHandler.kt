package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.speech.SpeechSegment
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState,
    private val getInputConnection: () -> InputConnection?,
    private val onVoiceComplete: () -> Unit = {},
    /** 录音停止即恢复工具栏；最终文本仍允许后台收尾。 */
    private val onRecordingStopped: () -> Unit = {},
    private val onAmplitudeChanged: (Float) -> Unit = {},
    private val onSpectrumChanged: (FloatArray) -> Unit = {},
    /** 语音向输入框写入 composing 文本时回调（标记 composing 区域存在，供 endComposingInputBox 判断）。 */
    private val onComposingWritten: () -> Unit = {},
    /** manager 工厂，供测试注入 mock。 */
    private val managerFactory: (Context) -> SpeechRecognitionManager = { SpeechRecognitionManager(it) },
    /** 超时调度器，供测试注入并手动推进。 */
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /**
     * 隔空输入：开着并且有可发的电脑时返回发送函数（开始说话时取一次）。说完的话交给它发到电脑，
     * 手机输入框里只用 composing 预览，句子说完即清掉，不上屏。
     */
    private val remoteTyping: () -> ((String) -> Unit)? = { null },
) {
    companion object {
        private const val TAG = "VoiceRecognition"

        /**
         * 松手后等待 ASR 引擎最终结果的超时。在线插件 stop() 只发送结束信号
         * （finish-task/最后一包标记），服务端处理完尾点才经 WebSocket 异步回调
         * 最终结果，通常 1~2s；超时仍未收到则回退提交已收到的部分结果。
         */
        private const val FINISH_TIMEOUT_MS = 3000L
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager

    var textBeforeVoiceInput = ""
    var textLengthBeforeVoiceInput = 0

    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = managerFactory(context)
        speechRecognitionManager.segmentedTextCallback = { parts, partial ->
            val raw = parts.joinToString(" ") { it.text }
            if (partial) handlePartialResult(raw, parts) else handleSpeechResult(raw, parts)
        }
        speechRecognitionManager.noticeCallback = { showVoiceNotice(it) }

        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onPartialResult = { text ->
                handlePartialResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error, userVisible ->
                handleSpeechError(error, userVisible)
            },
            onAmplitude = { amplitude ->
                handleAmplitudeUpdate(amplitude)
            },
            onSpectrum = { spectrum ->
                handleSpectrumUpdate(spectrum)
            }
        )

        val providerName = resolveProviderName()

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: $providerName")
        // 本地模型不随键盘预热：首次点麦克风时才按需加载（数百 MB），“保持引擎常驻”只保留用过的模型。
    }

    private val delayedPreStartRunnable = Runnable {
        val language = getState().voiceKeyboardLanguages.recognitionLanguage
        if (::speechRecognitionManager.isInitialized && language != InputLanguage.UNSPECIFIED &&
            (language == InputLanguage.CHINESE || SettingsPreferences.isSttUseLocal(context) ||
                com.kingzcheung.xime.speech.OnlineSpeechPlugin.supports(context, language))) {
            speechRecognitionManager.startPreStart()
        }
    }

    fun startDelayedPreStart(delayMs: Long = 150) {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        mainHandler.postDelayed(delayedPreStartRunnable, delayMs)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.cancelPreStart()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(getState().copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceRecognitionState = RecognitionState.ERROR
            ))
            return
        }

        val languages = getState().voiceKeyboardLanguages
        val language = languages.recognitionLanguage
        if (inputLanguage != null && inputLanguage != language) endInputSession()
        // 上一次会话若还在收尾等待中（快速再次开始），直接废弃收尾状态
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        suppressDuplicateFinal = false
        sessionAbandoned = false
        inputSession = getState().inputSessionId
        inputLanguages = languages
        requestedRecordingLanguage = language
        toolbarSession = getState().voiceSticky
        remoteSink = remoteTyping()
        remoteSent = ""
        toolbarText.reset()
        toolbarSentencePrefix = null
        lastToolbarFinal = ""
        lastPartialText = ""
        recentDictation = ""
        dictationCaret = null

        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length
        voiceStartCursor = cursorOf(getInputConnection())

        val providerName = resolveProviderName()
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition(language)
    }

    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            // 会话已丢弃（换了输入框等）：结果不会再上屏，模型加载中存着的录音也不必再认
            if (sessionAbandoned) speechRecognitionManager.cancelRecognition()
            else speechRecognitionManager.stopRecognition()
        }
        // handleFinalResult is now called from within handleSpeechResult
        // when the final stopRecognition result arrives
    }

    /** Independent of UI flags: pre-start/model loading can own resources before voice UI appears. */
    fun endInputSession() {
        abandonSession()
        cancelPreStart()
        if (::speechRecognitionManager.isInitialized) speechRecognitionManager.cancelRecognition()
    }

    fun release() {
        abandonSession()
        cancelPreStart()
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    private fun resolveProviderName(): String {
        // 用户开启"本地识别"且当前构建支持离线语音时，优先显示本地引擎名
        if (SettingsPreferences.isSttUseLocal(context)) {
            val localName = AsrBackendFactory.getLocalName()
            if (localName != null) return localName
        }
        if (com.kingzcheung.xime.speech.OnlineSpeechApi.isSelected(context) &&
            com.kingzcheung.xime.speech.OnlineSpeechApi.isConfigured(context)) return "在线语音"
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isNotEmpty()) {
            val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
            val selected = enabledPlugins.firstOrNull { it.first == selectedId }
                ?: enabledPlugins.firstOrNull()
            if (selected != null) {
                return ExtensionManager.getAllInstalledPlugins()
                    .firstOrNull { it.id == selected.first }?.name ?: selected.first
            }
        }
        return "未配置"
    }

    private var inputSession: Long? = null
    private var inputLanguages: VoiceKeyboardLanguages? = null
    private var requestedRecordingLanguage: InputLanguage? = null
    private val inputLanguage: InputLanguage? get() = inputLanguages?.recognitionLanguage
    // Keep Chinese dictated-punctuation editing across its English keyboard side.
    // This text convention must never select or constrain the acoustic recognizer.
    private val normalizationLanguage: InputLanguage? get() = inputLanguages?.let {
        if (it.nativeLanguage == InputLanguage.CHINESE) InputLanguage.CHINESE else it.recognitionLanguage
    }

    /** English/native keyboard switches are editing actions within the same voice session. */
    fun onInputLanguageChanged() {
        if (inputLanguages == null || sessionAbandoned) cancelPreStart()
        else if (acceptsInputSession() && !finishing) {
            val next = getState().voiceKeyboardLanguages
            if (requestedRecordingLanguage != next.language && ::speechRecognitionManager.isInitialized) {
                speechRecognitionManager.switchRecognitionLanguage(next.recognitionLanguage)
                requestedRecordingLanguage = next.recognitionLanguage
            }
        }
    }

    private fun acceptsInputSession(): Boolean {
        val state = getState()
        if ((inputSession == null || inputSession == state.inputSessionId) &&
            (inputLanguages?.accepts(state.voiceKeyboardLanguages) != false)) return true
        // restartInput 也可能不经 onFinishInput；不允许旧录音继续作用于新会话。
        if (!sessionAbandoned) {
            abandonSession()
            cancelPreStart()
            if (::speechRecognitionManager.isInitialized) speechRecognitionManager.cancelRecognition()
            onVoiceComplete()
        }
        return false
    }

    private var toolbarSession = false
    /** 本次会话发到电脑（隔空输入）；null 时照常写本机输入框。 */
    private var remoteSink: ((String) -> Unit)? = null
    /** 本次会话已发到电脑的上一句，用来在两句英文之间补一个空格。 */
    private var remoteSent = ""
    /** 手机输入框里正显示着隔空输入的预览。 */
    private var remotePreview = false
    private val toolbarText = StreamingVoiceText()
    private var lastToolbarFinal = ""
    private var toolbarSentencePrefix: String? = null
    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L
    private var smoothedAmplitude = 0f
    private var smoothedSpectrum = FloatArray(16)
    // 抬起时已提交当前识别文本后，置真以忽略随后可能迟到的重复最终结果
    private var suppressDuplicateFinal = false
    // 松手收尾中：已停止送音，等待引擎吐出最终结果（超时由 finishTimeoutRunnable 兜底）
    @Volatile
    private var finishing = false
    // 输入法窗口隐藏等场景：丢弃本会话，迟到结果不得写入任何输入框
    private var sessionAbandoned = false
    private var errorToast: Toast? = null

    private val finishTimeoutRunnable = Runnable { onFinishTimeout() }

    /** 输入法隐藏/切换输入框时调用：丢弃当前会话的未识别文本，忽略迟到的最终结果 */
    fun abandonPendingOnManualInput() { if (finishing) abandonSession() }

    fun abandonSession() {
        // 隔空输入的预览不能留在手机输入框里变成正文。
        clearRemotePreview()
        sessionAbandoned = true
        toolbarText.reset()
        toolbarSentencePrefix = null
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        lastPartialText = ""
    }

    /** 说话开始时的光标位置（读不到为 null），上滑取消时用来算这次写进了多少字。 */
    private var voiceStartCursor: Int? = null

    private fun cursorOf(ic: InputConnection?): Int? = ic?.getExtractedText(ExtractedTextRequest(), 0)
        ?.takeIf { it.startOffset >= 0 && it.selectionEnd >= 0 }?.let { it.startOffset + it.selectionEnd }

    /**
     * 按住空格说话上滑取消：工具栏语音是边说边上屏的，把这次写进输入框的字删掉；组合区里的临时结果清掉；
     * 之后迟到的结果一律丢弃。手指一直按在空格上，这期间用户改不了输入框，新写的字都紧挨在光标前。
     */
    fun discardSession() {
        val ic = getInputConnection()
        // 隔空输入的预览由 abandonSession 清；终端里字已经发出去，不能再发退格去删。
        if (ic != null && remoteSink == null && !TerminalEditor.active) {
            ic.beginBatchEdit()
            try {
                if (toolbarSession) spokenLength(ic).takeIf { it > 0 }?.let { ic.deleteSurroundingText(it, 0) }
                else if (lastPartialText.isNotEmpty()) { ic.setComposingText("", 1); ic.finishComposingText() }
            } finally { ic.endBatchEdit() }
        }
        abandonSession()
    }

    /** 这次说话写进输入框的字数；核对不上就返回 0，宁可留下字也不误删说话前的内容。 */
    private fun spokenLength(ic: InputConnection): Int {
        val before = textBeforeVoiceInput
        val start = voiceStartCursor
        val now = cursorOf(ic)
        if (start != null && now != null) {
            val spoken = now - start
            if (spoken <= 0) return 0
            // 删掉这段后光标前必须正好接回说话前的字。
            val tail = before.takeLast(32)
            val seen = ic.getTextBeforeCursor(spoken + tail.length, 0)?.toString() ?: return 0
            return if (seen.length == spoken + tail.length && seen.startsWith(tail)) spoken else 0
        }
        // 读不到光标位置：说话前光标前的字没读满（已到开头）时，现在光标前 = 原来的字 + 这次说的字。
        if (before.length >= 1000) return 0
        val seen = ic.getTextBeforeCursor(before.length + 4000, 0)?.toString() ?: return 0
        return if (seen.startsWith(before)) seen.length - before.length else 0
    }

    // 语音按钮长按抬起时调用：立即提交当前已识别的文本（不依赖可能被断连竞态吞掉的异步最终结果）
    fun commitPendingOnRelease() {
        if (!acceptsInputSession()) return
        if (sessionAbandoned || suppressDuplicateFinal) return
        remoteSink?.let { sink ->
            // 隔空输入：松手时把预览中的这一句发出去，手机输入框清掉预览。
            val partial = lastPartialText
            if (!toolbarSession && partial.isEmpty()) return
            if (partial.isNotBlank()) sendRemote(sink, partial)
            if (toolbarSession) { toolbarText.reset(); toolbarSentencePrefix = null }
            lastPartialText = ""
            suppressDuplicateFinal = true
            return
        }
        if (toolbarSession) {
            if (lastPartialText.isNotBlank()) {
                getInputConnection()?.let { updateToolbarText(it, lastPartialText) }
            }
            toolbarText.reset()
            toolbarSentencePrefix = null
            lastPartialText = ""
            suppressDuplicateFinal = true
            return
        }
        val ic = getInputConnection()
        val partial = lastPartialText
        Log.d(TAG, "commitPendingOnRelease: ic=${ic != null}, partial='$partial', suppress=$suppressDuplicateFinal")
        if (ic == null) return
        if (partial.isEmpty()) return
        val finalText = partial
        commitFinal(ic, finalText, partial)
        suppressDuplicateFinal = true
        lastPartialText = ""
    }

    /**
     * 松手/点按结束语音的收尾入口：停止送音后等待引擎最终结果，而不是立即提交
     * 部分结果——在线 ASR 需 1~2s 处理尾点，立即提交会把未处理完的语音截断。
     * 收到最终结果正常提交；超时（[FINISH_TIMEOUT_MS]）才回退提交部分结果。
     * 完成路径（最终结果/超时/错误）统一经 onVoiceComplete 通知宿主恢复键盘。
     */
    fun finishRecognition() {
        if (!::speechRecognitionManager.isInitialized) return
        if (finishing) return
        if (sessionAbandoned) {
            speechRecognitionManager.stopRecognition()
            return
        }
        if (!toolbarSession && lastPartialText.isEmpty()) {
            // 无已识别文本（没说话/极短语音）：无内容可等，直接结束；
            // 迟到的最终结果仍走正常提交路径（说了话就该上屏）
            speechRecognitionManager.stopRecognition()
            onVoiceComplete()
            return
        }
        finishing = true
        // 收尾期间保持"正在识别..."显示：引擎 stop 过程中的 IDLE 状态由
        // handleSpeechStateChange 过滤，直到最终结果/超时才结束
        onStateChanged(getState().copy(voiceRecognitionState = RecognitionState.PROCESSING))
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        mainHandler.postDelayed(finishTimeoutRunnable,
            if (SettingsPreferences.isSttUseLocal(context) || com.kingzcheung.xime.speech.OnlineSpeechPlugin.isBatch(context))
                speechRecognitionManager.finishTimeoutMs + 500 else FINISH_TIMEOUT_MS)
        speechRecognitionManager.stopRecognition()
        if (toolbarSession) onRecordingStopped()
    }

    private fun onFinishTimeout() {
        if (!acceptsInputSession()) return
        if (!finishing) return
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        Log.d(TAG, "finish timeout: committing partial result as fallback")
        // 超时未收到最终结果：提交已收到的部分结果兜底（会话已丢弃时内部直接跳过）
        // A transport timeout is not an empty recognition result. Preserve the visible
        // partial; the recognizer language was already validated at session start.
        // commitPendingOnRelease also rejects late finals after this fallback.
        commitPendingOnRelease()
        onVoiceComplete()
    }

    private fun handleSpeechResult(text: String, parts: List<SpeechSegment>? = null) {
        if (!acceptsInputSession()) return
        Log.d(TAG, "Speech result (final): $text")

        if (toolbarSession && (sessionAbandoned || suppressDuplicateFinal)) return
        val wasFinishing = finishing
        if (finishing) {
            // 收尾中收到最终结果：取消超时兜底，正常提交完整结果
            finishing = false
            mainHandler.removeCallbacks(finishTimeoutRunnable)
        }

        if (sessionAbandoned) {
            sessionAbandoned = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        if (suppressDuplicateFinal) {
            // 抬起时已提交，忽略迟到的重复最终结果
            suppressDuplicateFinal = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        val cleanText = normalizeResult(text, parts)
        remoteSink?.let { sink ->
            // 隔空输入：整句发到电脑；规则同本机上屏（错误文字不发，工具栏模式 stop 时重发的上一句只发一次）。
            val error = text.trimStart().startsWith("错误:") || text.trimStart().startsWith("错误：")
            val repeated = toolbarSession && wasFinishing && lastPartialText.isEmpty() && cleanText == lastToolbarFinal
            val piece = when {
                error || repeated -> ""
                cleanText.isNotEmpty() -> cleanText
                // 在线引擎最后给空结果：发已显示的临时结果（本机上屏同样留下它）。
                !SettingsPreferences.isSttUseLocal(context) -> lastPartialText
                else -> ""
            }
            if (piece.isNotBlank()) { sendRemote(sink, piece); lastToolbarFinal = piece }
            else clearRemotePreview()
            if (toolbarSession) { toolbarText.reset(); toolbarSentencePrefix = null }
            lastPartialText = ""
            if (wasFinishing) {
                if (toolbarSession) suppressDuplicateFinal = true
                onVoiceComplete()
            }
            return
        }
        if (toolbarSession) {
            // 部分引擎 stop 时重发上一句 final；没有新 partial 时只保留一次。
            if (cleanText.isNotEmpty() && !text.trimStart().startsWith("错误:") && !text.trimStart().startsWith("错误：") &&
                !(wasFinishing && lastPartialText.isEmpty() && cleanText == lastToolbarFinal)) {
                getInputConnection()?.let { updateToolbarText(it, cleanText) }
                lastToolbarFinal = cleanText
            } else if (cleanText.isEmpty() && lastPartialText.isNotEmpty() && !SettingsPreferences.isSttUseLocal(context)) {
                getInputConnection()?.let { updateToolbarText(it, lastPartialText) }
            }
            if (cleanText.isEmpty() && SettingsPreferences.isSttUseLocal(context)) {
                getInputConnection()?.let { toolbarText.update(it, "") }
            }
            toolbarText.reset()
            toolbarSentencePrefix = null
            lastPartialText = ""
            if (wasFinishing) {
                suppressDuplicateFinal = true
                onVoiceComplete()
            }
            return
        }
        val ic = getInputConnection()
        if (ic != null && cleanText.isEmpty() && SettingsPreferences.isSttUseLocal(context)) {
            ic.setComposingText("", 1); ic.finishComposingText()
        }
        if (ic != null && cleanText.isNotEmpty() && !text.trimStart().startsWith("错误:") && !text.trimStart().startsWith("错误：")) {
            // 终端里临时结果没写进去（见 handlePartialResult），整句按新文字上屏。
            commitFinal(ic, cleanText, if (TerminalEditor.active) "" else lastPartialText)
        }
        lastPartialText = ""

        if (!wasFinishing) {
            // 用户尚未松手就收到 final：流式在线插件按句回调属正常行为，该句已上屏，
            // 会话继续，等松手才结束。绝不能触发 onVoiceComplete——它会把
            // isVoiceMode/voiceRecordingStarted 清零，松手时容器的停止条件
            // （isVoiceMode || isRecording）全部失效，录音线程会一直在后台运行。
            return
        }
        onVoiceComplete()
    }
    
    // 增量语音模式：先结束 composing，再只提交增量，避免重复与整段重写。
    private fun commitFinal(ic: InputConnection, finalText: String, partial: String) {
        com.kingzcheung.xime.stats.TypingStats.onCommit(finalText, isPaste = false,
            source = com.kingzcheung.xime.stats.TypingStatsData.Source.VOICE)
        ic.finishComposingText()
        if (partial.isNotEmpty() && finalText.startsWith(partial)) {
            val remainder = finalText.substring(partial.length)
            if (remainder.isNotEmpty()) {
                ic.commitText(remainder, 1)
            } else {
                Log.d(TAG, "commitFinal: remainder empty, only finished composing")
            }
        } else {
            // 最终结果与部分结果不一致：删除已上屏的部分，再提交完整结果
            if (partial.isNotEmpty()) {
                ic.deleteSurroundingText(partial.length, 0)
            }
            ic.commitText(finalText, 1)
        }
        Log.d(TAG, "commitFinal: final='$finalText', partial='$partial'")
    }
    
    /** 隔空输入：清掉手机输入框里的预览，把这一句发到电脑。 */
    private fun sendRemote(sink: (String) -> Unit, text: String) {
        clearRemotePreview()
        // 两句英文之间补一个空格（电脑上的光标前文读不到，按本次会话上一句判断）；中文不加。
        val gap = if (remoteSent.lastOrNull()?.let { it.isLetterOrDigit() && it.code < 128 || it in ".,!?;:" } == true &&
            text.first().let { it.isLetterOrDigit() && it.code < 128 }) " " else ""
        sink(gap + text)
        remoteSent = text
    }

    private fun clearRemotePreview() {
        if (!remotePreview) return
        remotePreview = false
        getInputConnection()?.let { it.setComposingText("", 1); it.finishComposingText() }
    }

    // 引擎逐句返回时，只在下一句真正开始后插入一个分隔空格；句尾不留空格。
    private fun updateToolbarText(ic: InputConnection, text: String) {
        if (toolbarSentencePrefix == null) {
            val preceding = if (lastToolbarFinal.isNotEmpty())
                ic.getTextBeforeCursor(lastToolbarFinal.length, 0)?.toString() else null
            toolbarSentencePrefix = if (preceding == lastToolbarFinal && !preceding.isNullOrEmpty()) " " else ""
        }
        toolbarText.update(ic, toolbarSentencePrefix.orEmpty() + text)
        recentDictation = toolbarSentencePrefix.orEmpty() + text
        dictationCaret = toolbarText.writeEnd
    }

    /** 工具栏听写最近写进输入框的字和写完后的光标位置：宿主重启输入框后靠它们回到原处接着写。 */
    private var recentDictation = ""
    private var dictationCaret: Int? = null

    /**
     * 宿主在听写中重启了同一个输入框（B 站评论框写满一行时重设文字，光标被放回开头；11-06 用户：说着说着
     * 自己停了、光标飘到最前面）：核对听写写过的字还在原处，就把光标放回去、接上新的输入会话，听写继续。
     * [hostCarets] 是重启前宿主最近报来的光标（新的在前）。核对不上返回 false，由调用方照旧结束听写。
     */
    fun continueAfterHostRestart(hostCarets: List<Int>): Boolean {
        if (!::speechRecognitionManager.isInitialized || inputSession == null || sessionAbandoned || finishing ||
            suppressDuplicateFinal || remoteSink != null || TerminalEditor.active) return false
        val ic = getInputConnection() ?: return false
        val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull() ?: return false
        val text = extracted.text ?: return false
        if (extracted.startOffset < 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0) return false
        // 按住说话的临时结果在组合区里：重启时框架把它定成了正文，要重新标回组合区，否则下一次临时结果会重复写一遍。
        val composing = !toolbarSession && lastPartialText.isNotEmpty()
        val tail = if (composing) lastPartialText else recentDictation.takeIf { it.isNotBlank() }?.takeLast(8).orEmpty()
        val candidates = listOfNotNull(dictationCaret.takeIf { toolbarSession }) + hostCarets
        val at = voiceResumeCaret(text, extracted.startOffset, candidates, tail) ?: return false
        val caretStart = extracted.startOffset + extracted.selectionStart
        val caretEnd = extracted.startOffset + extracted.selectionEnd
        val moved = runCatching {
            ic.beginBatchEdit()
            try {
                if (caretStart != at || caretEnd != at) ic.setSelection(at, at)
                if (composing) { onComposingWritten(); ic.setComposingRegion(at - lastPartialText.length, at) }
            } finally { ic.endBatchEdit() }
        }.isSuccess
        if (!moved) return false
        FileLogger.w(TAG, "host restarted the field during dictation: resumed at=$at caret=$caretStart/$caretEnd " +
            "toolbar=$toolbarSession composing=$composing")
        inputSession = getState().inputSessionId
        return true
    }

    private fun normalizeResult(text: String, parts: List<SpeechSegment>?): String {
        val language = normalizationLanguage ?: getState().inputProfile.language
        val segments = parts ?: listOf(SpeechSegment(text, inputLanguage ?: language, false))
        val automatic = SettingsPreferences.isSttAutoPunctuation(context)
        val localSentences = SettingsPreferences.isSttUseLocal(context) && SettingsPreferences.isSttWholeSentence(context)
        val marks = voiceMarks()
        // 预览和定稿走同一条转换，说到一半出的 300 和说完的 325 是同一种写法，不会中文、数字来回跳。
        val arabicNumbers = SettingsPreferences.isSttArabicNumbers(context)
        // 语音纠错放在最后：词表里写的是用户看到的结果(已转好数字和标点)；预览和定稿同样替换，不会先出错词再跳。
        val tidy = SettingsPreferences.isSttSmartTidy(context)
        val corrections = if (SettingsPreferences.isSttCorrections(context))
            VoiceCorrections.of(SettingsPreferences.getSttCorrectionsText(context)) else null
        return segments.mapIndexed { index, part ->
            val keep = automatic && (part.serverPunctuation || (localSentences && part.language == InputLanguage.CHINESE))
            normalizeVoiceText(part.text, language, keep, if (index == segments.lastIndex) marks else marks.copy(trailing = true),
                arabicNumbers)
        }.filter(String::isNotEmpty).joinToString(" ")
            .let { if (tidy) com.kingzcheung.xime.speech.VoiceTidy.apply(it) else it }
            .let { corrections?.apply(it) ?: it }
    }

    private fun voiceMarks() = VoiceMarks(comma = SettingsPreferences.isSttPunctuationComma(context),
        period = SettingsPreferences.isSttPunctuationPeriod(context), question = SettingsPreferences.isSttPunctuationQuestion(context),
        trailing = SettingsPreferences.isSttPunctuationTrailing(context))

    private fun handlePartialResult(text: String, parts: List<SpeechSegment>? = null) {
        if (!acceptsInputSession()) return
        if (sessionAbandoned || suppressDuplicateFinal) return
        val cleanText = normalizeResult(text, parts)
        if (cleanText == lastPartialText) return
        lastPartialText = cleanText
        Log.d(TAG, "Speech result (partial): $cleanText")

        val ic = getInputConnection()
        if (ic != null && remoteSink != null) {
            // 隔空输入：只在手机输入框里预览（composing），说完发到电脑后清掉。
            if (!TerminalEditor.active) { onComposingWritten(); ic.setComposingText(cleanText, 1); remotePreview = true }
        } else if (ic != null) {
            if (toolbarSession) {
                updateToolbarText(ic, cleanText)
            } else if (!TerminalEditor.active) {
                // 终端收到组合区文字就直接发出去，之后每次改写都会再发一遍：临时结果只显示在键盘上。
                onComposingWritten()
                ic.setComposingText(cleanText, 1)
            }
        }
        onStateChanged(getState().copy(voiceRecognizedText = cleanText))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        if (!acceptsInputSession()) return
        Log.d(TAG, "Speech state changed: $state")
        if (toolbarSession && (sessionAbandoned || suppressDuplicateFinal)) return
        if (state == RecognitionState.LISTENING && !toolbarSession) {
            lastPartialText = ""
            suppressDuplicateFinal = false
            sessionAbandoned = false
        }
        // 收尾等待最终结果期间，引擎 stop 产生的 IDLE 不覆盖"正在识别..."显示
        if (finishing && state == RecognitionState.IDLE) {
            // 本地（包括空结果）都会发 final；在线 IDLE 可能先于 WebSocket final。
            return
        }
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String, userVisible: Boolean) {
        if (!acceptsInputSession()) return
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        val wasFinishing = finishing
        if (toolbarSession || remoteSink != null) commitPendingOnRelease()
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        lastPartialText = ""
        if (!wasFinishing) {
            // 用户尚未松手时引擎报错：UI 经 onVoiceComplete 恢复后松手停止链即失效，
            // 必须在这里显式停止录音（释放麦克风/引擎连接）；置抑制标志丢弃错误后
            // 可能迟到的部分结果，避免键盘恢复后文字继续往外蹦。
            suppressDuplicateFinal = true
            if (::speechRecognitionManager.isInitialized) {
                speechRecognitionManager.stopRecognition()
            }
        }
        if (userVisible && error.isNotBlank()) {
            errorToast?.cancel()
            errorToast = Toast.makeText(context, error, Toast.LENGTH_LONG)
            errorToast?.show()
        }
        onVoiceComplete()
        if (userVisible && error.isNotBlank()) showVoiceNotice(error)
    }

    private val clearVoiceNotice = Runnable { onStateChanged(getState().copy(voiceNotice = null)) }

    /** 键盘上的一行提示：有的手机（如 vivo）会拦下输入法的 Toast，语音出错不能悄无声息。 */
    fun showVoiceNotice(message: String) {
        mainHandler.removeCallbacks(clearVoiceNotice)
        onStateChanged(getState().copy(voiceNotice = message))
        mainHandler.postDelayed(clearVoiceNotice, 5000)
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        smoothedAmplitude = smoothedAmplitude * 0.45f + amplitude * 0.55f
        onAmplitudeChanged(smoothedAmplitude)
    }

    private fun handleSpectrumUpdate(spectrum: FloatArray) {
        val smoothed = smoothedSpectrum
        for (i in spectrum.indices) {
            smoothed[i] = smoothed[i] * 0.5f + spectrum[i] * 0.5f
        }
        onSpectrumChanged(smoothed.copyOf())
    }
}
