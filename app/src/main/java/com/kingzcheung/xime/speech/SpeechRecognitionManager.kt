package com.kingzcheung.xime.speech

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.annotation.RequiresPermission
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.util.FileLogger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class SpeechSegment(val text: String, val language: InputLanguage, val serverPunctuation: Boolean)

class SpeechRecognitionManager(
    private val context: Context,
    private val backendFactory: ((Context) -> AsrBackend?)? = null,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val audioRecordFactory: (() -> AudioRecord?)? = null,
) {

    companion object {
        private const val TAG = "SpeechRecognitionManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_SECONDS = 0.1f
        private const val SPEECH_THRESHOLD = 25

        /** 每次后端调用的停止兜底；等待上一段收尾不占用本段定稿时间。 */
        private const val JOIN_TIMEOUT_MS = 8000L

        /**
         * 停止后延迟释放后端的窗口：在线插件 stop() 只发送结束信号，最终结果由
         * 服务端处理完尾点后经 WebSocket 异步回调（1~2s），需比
         * VoiceRecognitionHandler.FINISH_TIMEOUT_MS 更长，保证回调通道存活。
         */
        private const val BACKEND_RELEASE_DELAY_MS = 3500L
        // 覆盖按需加载本地模型的等待（实测约 2 秒）再留余量
        private const val PRE_START_BUFFER_SECS = 6f
        /** 加载中停止时，松手之后再多留这么长的音，最后一个字的尾音不被剪掉。 */
        private const val STOP_TAIL_MS = 300L
    }

    @Volatile private var backend: AsrBackend? = null
    @Volatile private var backendConfiguration: String? = null
    private fun selectedBackendConfiguration(): String =
        if (SettingsPreferences.isSttUseLocal(context)) "local:${AsrSupport.generation}"
        else "online:${SettingsPreferences.getSttOnlinePluginId(context)}"
    @Volatile private var recordingThread: RecordingThread? = null
    // 屏蔽外放音：开麦时压低或暂停别的应用的声音，关麦恢复（本地和在线语音都从这里的录音线程取音）
    private val audioFocus by lazy { VoiceAudioFocus(context) }
    @Volatile private var released = false
    @Volatile private var recognitionLanguage = InputLanguage.CHINESE

    // 会话序号：用于区分连续语音会话，防止旧会话的回收线程误释放新会话的后端
    @Volatile private var sessionId = 0
    // 待执行的延迟释放所对应的会话序号（-1 表示无待释放）
    @Volatile
    private var pendingReleaseSession = -1
    private val pendingBackendRelease = Runnable {
        val session = pendingReleaseSession
        pendingReleaseSession = -1
        // 主线程只取引用；release() 含跨进程 IPC/断连，放后台线程执行
        val b = synchronized(preloadLock) {
            if (session != -1 && sessionId == session && recordingThread == null &&
                !SettingsPreferences.isSttKeepEngineAlive(context)) {
                val tmp = backend
                backend = null
                tmp
            } else null
        }
        if (b != null) {
            Thread { b.release() }.start()
        }
    }
    // 后台加载 ASR 模型的进行中标记与取消标记
    @Volatile
    private var loadingInProgress = false
    @Volatile
    private var loadingCancelled = false
    // 等模型加载完才开始录音线程：从按需加载开始，到加载完在主线程启动录音（或放弃）为止
    @Volatile private var loadPending = false
    // 模型还在加载时用户已松手或再点停止：加载完把已录下的音认完再结束，不丢
    // （2026-10-31 平板用户：刚唤出键盘第一次说话不出字）
    @Volatile private var stopAfterLoad = false
    @Volatile private var stopRequestedAtNs = 0L
    // 预启动麦克风开始录音的时刻，用来剪掉松手之后多录的部分
    @Volatile private var preStartedAtNs = 0L

    private var resultCallback: ((String) -> Unit)? = null
    internal var segmentedTextCallback: ((List<SpeechSegment>, Boolean) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    @Volatile
    private var currentState: RecognitionState = RecognitionState.IDLE
    private var errorCallback: ((String, Boolean) -> Unit)? = null
    /** 不结束语音的提示（如在线接口失败、这次改用本地）。 */
    @Volatile var noticeCallback: ((String) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null
    private var spectrumCallback: ((FloatArray) -> Unit)? = null

    // 预启动的 AudioRecord：手指按下 150ms 后启动，语音激活时直接交给录音线程
    private var preStartedRecord: AudioRecord? = null
    private val preStartTimeoutRunnable = Runnable { releasePreStart() }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecognition(language: InputLanguage = InputLanguage.CHINESE) {
        if (released) return
        // Never wait for model initialization on the UI thread: hiding the IME must be able to cancel.
        if (recordingThread?.stopRequested == false) {
            FileLogger.w(TAG, "Recognition already running, ignoring start request")
            return
        }
        if (language == InputLanguage.UNSPECIFIED ||
            language != InputLanguage.CHINESE && !SettingsPreferences.isSttUseLocal(context) &&
            !OnlineSpeechPlugin.supports(context, language) && backendFactory == null) {
            releasePreStart()
            errorCallback?.invoke(if (language == InputLanguage.UNSPECIFIED) "当前方案未标注语言，请在「已安装的方案」里给它选语言"
                else "当前在线语音插件尚未声明${language.displayName}支持，请使用本地多语言模型", true)
            setState(RecognitionState.ERROR)
            return
        }
        recognitionLanguage = language

        loadingCancelled = false
        stopAfterLoad = false
        // A pending gesture may already have opened the mic. Quiet media as soon as voice is confirmed.
        synchronized(this) { preStartedRecord?.let { audioFocus.acquire(it) } }
        FileLogger.i(TAG, "Starting speech recognition")
        setState(RecognitionState.PROCESSING)

        if (backend == null || backendConfiguration != selectedBackendConfiguration()) {
            // 按需加载：后台线程加载 ASR 模型，完成后在主线程启动录音，避免阻塞键盘 UI
            FileLogger.i(TAG, "ASR backend not loaded, loading on demand")
            synchronized(preloadLock) {
                if (loadingInProgress) {
                    // 已有加载进行中（如设置开启触发的预加载），等待其完成
                    loadingCancelled = false
                    loadPending = true
                    return
                }
                loadingInProgress = true
                loadingCancelled = false
                loadPending = true
            }
            // 模型按需加载约 2 秒：期间保留预启动的麦克风，加载完由录音线程接着读缓冲，开头不丢。
            mainHandler.removeCallbacks(preStartTimeoutRunnable)
            Thread {
                try {
                    val ok = preload(warmUp = false)
                    if (!ok || synchronized(preloadLock) { backend } == null) {
                        mainHandler.post {
                            loadPending = false
                            stopAfterLoad = false
                            releasePreStart()
                            errorCallback?.invoke("语音引擎没能启动：本地语音模型没下载或加载失败，请到 设置 → 语音转文本 检查模型", true)
                            setState(RecognitionState.ERROR)
                        }
                        return@Thread
                    }
                    if (loadingCancelled || released) {
                        mainHandler.post {
                            loadPending = false
                            stopAfterLoad = false
                            releasePreStart()
                            if (!released) { scheduleBackendRelease(sessionId); setState(RecognitionState.IDLE) }
                        }
                        return@Thread
                    }
                    mainHandler.post {
                        loadPending = false
                        if (recordingThread == null && !loadingCancelled && !released) {
                            startRecording()
                        }
                    }
                } finally {
                    synchronized(preloadLock) {
                        loadingInProgress = false
                        preloadLock.notifyAll()
                    }
                }
            }.start()
            return
        }

        startRecording()
    }

    private fun startRecording() {
        if (released || loadingCancelled) return
        val currentBackend = synchronized(preloadLock) { backend } ?: return
        synchronized(preloadLock) { sessionId++ }

        // 新会话复用后端：取消上一次会话遗留的延迟释放
        mainHandler.removeCallbacks(pendingBackendRelease)
        pendingReleaseSession = -1

        // 预启动的 AudioRecord 已运行 ~250ms，直接交给录音线程
        var preStarted: AudioRecord? = null
        synchronized(this) {
            preStarted = preStartedRecord
            preStartedRecord = null
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)

        // 加载中已经停止：只把麦克风里存着的音（到松手为止）认完就结束，不再接着录
        val drainBytes = if (stopAfterLoad) {
            stopAfterLoad = false
            if (preStarted == null) {
                setState(RecognitionState.IDLE)
                scheduleBackendRelease(sessionId)
                return
            }
            val startedAt = preStartedAtNs
            if (startedAt == 0L) Long.MAX_VALUE
            else (TimeUnit.NANOSECONDS.toMillis(stopRequestedAtNs - startedAt).coerceAtLeast(0L) + STOP_TAIL_MS) * SAMPLE_RATE / 1000 * 2
        } else -1L

        // Capture can resume while the previous result is finishing. Backend ownership remains
        // serialized, so an old stop/cancel can never terminate the new recognition session.
        recordingThread = RecordingThread(currentBackend, preStarted, sessionId, recognitionLanguage, recordingThread, drainBytes)
        recordingThread!!.start()
    }

    /** Re-route subsequent PCM without closing AudioRecord or discarding previous words. */
    fun switchRecognitionLanguage(language: InputLanguage) {
        if (released || language == InputLanguage.UNSPECIFIED || !SettingsPreferences.isSttUseLocal(context)) return
        recognitionLanguage = language
        recordingThread?.takeUnless { it.stopRequested }?.requestedLanguage = language
    }

    /**
     * 延迟释放后端：给在线插件的异步最终结果留出送达窗口。窗口内新会话开始
     * （startRecording）会取消释放并复用后端；释放前校验会话序号，避免误释放
     * 新会话正在使用的后端。
     * "引擎常驻"开启时不安排释放——后端跨会话保留，闲置后再用免重建
     * （重新加载模型/重建 Lua 后端与连接正是"闲置后首次使用慢"的根源）；
     * 关闭设置后自然回退为延迟释放，无需主动清理。
     */
    private fun scheduleBackendRelease(session: Int) {
        if (released || SettingsPreferences.isSttKeepEngineAlive(context)) return
        pendingReleaseSession = session
        mainHandler.removeCallbacks(pendingBackendRelease)
        mainHandler.postDelayed(pendingBackendRelease, BACKEND_RELEASE_DELAY_MS)
    }

    fun stopRecognition() {
        val pendingMic = synchronized(this) { preStartedRecord }
        if (recordingThread == null && loadPending && !released && pendingMic != null) {
            // 模型还在加载：麦克风里已经存着刚才说的话，加载完认完再结束（原来这里直接丢掉，什么都不出）
            stopAfterLoad = true
            stopRequestedAtNs = System.nanoTime()
            audioFocus.release(pendingMic)
            FileLogger.i(TAG, "Stop during model load: recognizing the captured audio once loaded")
            return
        }
        loadingCancelled = true
        releasePreStart()
        Log.d(TAG, "Stopping recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    setState(RecognitionState.IDLE)
                }
            }
            return
        }
        if (thread.stopRequested) return
        thread.stopRequested = true
        // End capture immediately, but let delivery drain captured PCM and finish normally.
        thread.forceStopAudio()
    }

    fun cancelRecognition() {
        // Invalidate callbacks immediately, including results already posted to the UI thread.
        synchronized(preloadLock) { sessionId++ }
        loadingCancelled = true
        stopAfterLoad = false
        releasePreStart()
        // 取消 = 这次语音整个结束（收键盘、换输入框、销毁都走这里）：无条件还原媒体音量、放掉焦点（learn-33）。
        audioFocus.releaseAll()
        Log.d(TAG, "Canceling recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    setState(RecognitionState.IDLE)
                }
            }
            return
        }
        thread.cancelRequested = true
        if (thread.stopRequested) return
        thread.stopRequested = true
        // Release capture immediately; cancellation remains on the backend's owning worker.
        thread.forceStopAudio()
    }

    fun getState(): RecognitionState = currentState
    val finishTimeoutMs: Long get() = maxOf(JOIN_TIMEOUT_MS, backend?.finishTimeoutMs ?: JOIN_TIMEOUT_MS)

    private fun setState(state: RecognitionState) {
        currentState = state
        stateCallback?.invoke(state)
    }

    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (message: String, userVisible: Boolean) -> Unit,
        onAmplitude: ((Float) -> Unit)? = null,
        onSpectrum: ((FloatArray) -> Unit)? = null
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
        amplitudeCallback = onAmplitude
        spectrumCallback = onSpectrum
    }

    fun startPreStart() {
        releasePreStart()
        if (released || recordingThread != null) return
        val record = createAudioRecord(PRE_START_BUFFER_SECS) ?: return
        try {
            record.startRecording()
            preStartedAtNs = System.nanoTime()
            synchronized(this) {
                preStartedRecord = record
                // The toolbar can request voice first, then open this mic while the model loads.
                if (loadPending && !loadingCancelled && !stopAfterLoad) audioFocus.acquire(record)
            }
        } catch (error: Exception) {
            try { record.release() } catch (_: Exception) { }
            Log.w(TAG, "Microphone pre-start failed", error)
            return
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        if (!loadingInProgress) mainHandler.postDelayed(preStartTimeoutRunnable, 2000)
    }

    /**
     * 键盘这边结束语音（松手、换输入框）时调用。模型还在加载时麦克风里存着刚说的话，交给随后的
     * stop/cancel 决定认完还是丢弃，这里不先关掉（原来先关了，加载中松手总是一个字都不出）。
     */
    fun cancelPreStart() {
        if (loadPending) {
            mainHandler.removeCallbacks(preStartTimeoutRunnable)
            return
        }
        releasePreStart()
    }

    private fun releasePreStart() {
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        synchronized(this) {
            val record = preStartedRecord
            preStartedRecord = null
            if (record != null) {
                try { record.stop() } catch (_: Exception) { }
                try { record.release() } catch (_: Exception) { }
                audioFocus.release(record)
            }
        }
    }

    fun release() {
        released = true
        Log.d(TAG, "Releasing speech recognition")
        releasePreStart()
        audioFocus.releaseAll()
        mainHandler.removeCallbacks(pendingBackendRelease)
        pendingReleaseSession = -1
        cancelRecognition()
        val b = synchronized(preloadLock) {
            val tmp = backend
            backend = null
            tmp
        }
        if (b != null) {
            // release() 含跨进程 IPC，放到后台线程执行，避免阻塞主线程（onDestroy 等场景）
            val lastRecording = recordingThread
            Thread {
                lastRecording?.awaitBackendFinished()
                b.release()
            }.start()
        }
    }

    private var isPreloading = false
    private val preloadLock = Object()

    fun preload(warmUp: Boolean = true): Boolean {
        val configuration = selectedBackendConfiguration()
        val previous: AsrBackend?
        synchronized(preloadLock) {
            while (isPreloading && !released) preloadLock.wait()
            if (released) return false
            if (backend != null && backendConfiguration == configuration) return true
            previous = backend
            backend = null
            backendConfiguration = null
            isPreloading = true
        }
        var candidate: AsrBackend? = null
        var retained = false
        try {
            // Retire the exact old backend only after its last recording has finished.
            if (previous != null) {
                recordingThread?.awaitBackendFinished()
                previous.release()
            }
            val created = createBackend() ?: return false
            candidate = created
            created.setCallbacks(
                // Model warmup is not an input session and must never write to the editor.
                onResult = {}, onPartialResult = {}, onStateChange = {},
                onError = { error -> Log.w(TAG, "Speech warmup: $error") }
            )
            val ready = if (warmUp) created.initialize(recognitionLanguage)
                else created.prepareForRecording(recognitionLanguage)
            if (!ready) return false
            synchronized(preloadLock) {
                if (released || configuration != selectedBackendConfiguration()) return false
                backend = created
                backendConfiguration = configuration
                retained = true
            }
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to initialize speech backend", error)
            return false
        } finally {
            if (!retained) try { candidate?.release() } catch (_: Exception) { }
            synchronized(preloadLock) { isPreloading = false; preloadLock.notifyAll() }
        }
    }

    private fun createBackend(): AsrBackend? {
        backendFactory?.let { return it(context) }
        // 用户开启"本地识别"时才使用离线后端，否则走在线插件
        // 默认一律本地模型；只有选了「优先使用在线接口」（测试通过才有这个选项）才先走在线，失败时这段改用本地。
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        return if (useLocal) {
            AsrBackendFactory.create(context) ?: createOnlineAsrBackend()
        } else {
            // 优先在线：内置在线语音或选中的语音插件。整段上传失败时，这段录音改用本地模型并提示。
            createOnlineAsrBackend() ?: AsrBackendFactory.create(context)
        }
    }

    private fun createOnlineAsrBackend(): AsrBackend? {
        // 1.15.40～1.15.50 装过「在线语音」插件的人：设置搬回内置（只搬一次）。
        OnlineSpeechApi.migrateFromPlugin(context)
        if (OnlineSpeechApi.isSelected(context) && OnlineSpeechApi.isConfigured(context)) {
            return OnlineApiAsrBackend(context, localFallback = { AsrBackendFactory.create(context) },
                onNotice = { message -> mainHandler.post { noticeCallback?.invoke(message) } })
        }
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isEmpty()) return null

        val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
        val selected = enabledPlugins.firstOrNull { it.first == selectedId }
            ?: enabledPlugins.firstOrNull()
        val (_, plugin) = selected ?: return null

        val backend = plugin.createBackend(context.applicationContext)
        val pluginName = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == selected?.first }?.name ?: selected?.first ?: "语音识别"
        return PluginAsrBackendAdapter(pluginName, backend, plugin.getCapabilities(),
            localFallback = { AsrBackendFactory.create(context) },
            onNotice = { message -> mainHandler.post { noticeCallback?.invoke(message) } })
    }

    private fun createAudioRecord(bufferSecs: Float = 2.0f): AudioRecord? {
        audioRecordFactory?.let { return it() }
        val bufferSize = (SAMPLE_RATE * bufferSecs).toInt()
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                attachNoiseSuppression(record)
                record
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            null
        }
    }

    // 自用学习版（用户 11-08：背景音吵时认不准）：手机自带的降噪挂到麦克风上，没有就算了。
    // 每次录音一个会话；只留最近两次（预启动那条和正在录的那条），旧的放掉。
    private val noiseEffects = ArrayDeque<android.media.audiofx.AudioEffect>()

    private fun attachNoiseSuppression(record: AudioRecord) {
        try {
            if (!android.media.audiofx.NoiseSuppressor.isAvailable()) return
            val ns = android.media.audiofx.NoiseSuppressor.create(record.audioSessionId) ?: return
            ns.enabled = true
            synchronized(noiseEffects) {
                noiseEffects.addLast(ns)
                while (noiseEffects.size > 2) try { noiseEffects.removeFirst().release() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor unavailable", e)
        }
    }

    private data class CapturedAudio(val pcm: ByteArray, val language: InputLanguage)

    private inner class RecordingThread(
        private val currentBackend: AsrBackend,
        preStarted: AudioRecord? = null,
        private val session: Int,
        private val language: InputLanguage,
        private val previousRecording: RecordingThread?,
        /** ≥ 0：模型加载中已停止，只读麦克风里存着的这么多字节就结束；-1：正常录音直到停止。 */
        private val drainBytes: Long = -1L,
    ) : Thread("AsrRecording") {

        private val spectrumAnalyzer = SpectrumAnalyzer()
        private val requestedAt = System.nanoTime()

        // Capture stops independently of delivery: normal stop drains PCM, cancel discards it.
        // Explicit flags also survive backend/plugin calls that consume thread interrupts.
        @Volatile
        var stopRequested = false
        @Volatile var cancelRequested = false

        // Own the pre-started microphone before Thread.start(): stop may arrive before run().
        private val microphone = RecordingAudioOwner(preStarted)
        // Retain the same owner across handoff so volume changes only once per live capture.
        private val otherAudioOwner = preStarted ?: this
        @Volatile var requestedLanguage = language
        private val audioQueue = ArrayBlockingQueue<CapturedAudio>(100) // At most 10 s / 320 KB of PCM.
        private val backendFinished = CountDownLatch(1)
        @Volatile private var captureEnded = false
        @Volatile private var deliveryThread: Thread? = null
        @Volatile private var backendOperationStartedAt = 0L
        private val shutdownWatched = AtomicBoolean()

        private fun watchShutdown() {
            if (!shutdownWatched.compareAndSet(false, true)) return
            Thread({
                while (!backendFinished.await(250, TimeUnit.MILLISECONDS)) {
                    val started = backendOperationStartedAt
                    if (started != 0L && System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(maxOf(JOIN_TIMEOUT_MS, currentBackend.finishTimeoutMs))) {
                        // Interrupt the actual model/IPC owner, never advertise it as free early.
                        deliveryThread?.interrupt()
                        break
                    }
                }
            }, "AsrShutdownWatch").start()
        }

        fun awaitBackendFinished() {
            var interrupted = false
            while (true) try {
                backendFinished.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        private fun enqueue(chunk: CapturedAudio) {
            if (!audioQueue.offer(chunk)) error("语音引擎准备时间过长，已停止录音，请重试")
        }

        /** Each recognizer segment owns its callbacks; late results cannot overwrite a new segment. */
        private inner class SegmentCallbacks(val prefix: List<SpeechSegment>, val language: InputLanguage) {
            @Volatile var active = true
            @Volatile var switching = false
            @Volatile var parts = prefix
            /** The backend's own reason (e.g. model not downloaded); a failed start reports it instead of a generic line. */
            @Volatile var reportedError: String? = null
            fun receive(text: String, partial: Boolean) {
                if (!active) return
                val segment = SpeechSegment(text.trim(), language, !partial && currentBackend.resultKeepsPunctuation)
                val result = if (segment.text.isEmpty()) prefix else prefix + segment
                parts = result
                if (switching) return
                postForSession(session) {
                    if (!active || cancelRequested) return@postForSession
                    val segmented = segmentedTextCallback
                    if (segmented != null) segmented(result, partial)
                    else (if (partial) partialResultCallback else resultCallback)?.invoke(result.joinToString(" ") { it.text })
                }
            }
            fun bind() = currentBackend.setCallbacks(
                onResult = { receive(it, partial = false) },
                onPartialResult = { receive(it, partial = true) },
                onStateChange = { state ->
                    if (active && !switching) postForSession(session) { if (active && !switching && !cancelRequested) setState(state) }
                },
                onError = { error ->
                    if (error.isNotBlank()) reportedError = error
                    if (active) postForSession(session) { if (active && !cancelRequested) errorCallback?.invoke(error, true) }
                },
            )
        }

        private fun reportFailure(error: Exception, duringDelivery: Boolean = false) {
            if (!cancelRequested && !released && (duringDelivery || !stopRequested)) mainHandler.post {
                if (!released && session == sessionId) {
                    errorCallback?.invoke("录音已停止：${error.message.orEmpty()}", true)
                    setState(RecognitionState.ERROR)
                }
            }
        }

        /** Only this worker touches the backend; the microphone never waits for model/IPC work. */
        private fun deliverAudio() {
            var attempted = false
            try {
                while (previousRecording?.backendFinished?.await(50, TimeUnit.MILLISECONDS) == false) {
                    if (cancelRequested || released) return
                }
                if (cancelRequested || released) return
                var callbacks = SegmentCallbacks(emptyList(), language).also { it.bind() }
                var activeLanguage = language
                attempted = true
                backendOperationStartedAt = System.nanoTime()
                // The later failure line replaces the backend's toast/notice, so it must carry the backend's reason.
                check(currentBackend.start(language)) { callbacks.reportedError ?: "启动语音引擎失败" }
                backendOperationStartedAt = 0L
                FileLogger.i(TAG, "ASR backend ready session=$session elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAt)}")
                while (!cancelRequested && !released) {
                    val chunk = audioQueue.poll(50, TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        backendOperationStartedAt = System.nanoTime()
                        if (chunk.language != activeLanguage) {
                            if (!currentBackend.canContinueIn(chunk.language)) {
                                FileLogger.i(TAG, "Switching recognizer ${activeLanguage.id} -> ${chunk.language.id}; microphone retained")
                                callbacks.switching = true
                                currentBackend.stop()
                                callbacks.active = false
                                val prefix = callbacks.parts
                                if (cancelRequested || released) break
                                callbacks = SegmentCallbacks(prefix, chunk.language).also { it.bind() }
                                check(currentBackend.start(chunk.language)) {
                                    callbacks.reportedError ?: "无法切换到${chunk.language.displayName}语音，请检查该语言模型"
                                }
                            }
                            activeLanguage = chunk.language
                        }
                        currentBackend.processAudioChunk(chunk.pcm)
                        backendOperationStartedAt = 0L
                    }
                    else if (captureEnded && audioQueue.isEmpty()) break
                }
            } catch (error: Exception) {
                reportFailure(error, duringDelivery = true)
                cancelRequested = true
                forceStopAudio()
            } finally {
                if (attempted) try {
                    backendOperationStartedAt = System.nanoTime()
                    if (cancelRequested || released) currentBackend.cancel() else currentBackend.stop()
                } catch (error: Exception) {
                    Log.w(TAG, "Speech backend stop failed after microphone release", error)
                    reportFailure(error, duringDelivery = true)
                }
                // A cancelled queued recording still represents its predecessor's ownership.
                // A third immediate start must not jump past the original unfinished stop.
                if (!attempted) previousRecording?.awaitBackendFinished()
                backendOperationStartedAt = 0L
                audioQueue.clear()
                backendFinished.countDown()
                completeRecording()
            }
        }

        private fun completeRecording() {
            mainHandler.post {
                if (recordingThread === this) {
                    recordingThread = null
                    // 认完/出错都到这里：没有别的录音也没有预启动的麦克风时，无条件还原媒体音量（learn-33）。
                    val micPending = synchronized(this@SpeechRecognitionManager) { preStartedRecord != null }
                    if (!micPending && !loadPending) audioFocus.releaseAll()
                    if (!released) {
                        if (currentState != RecognitionState.ERROR) setState(RecognitionState.IDLE)
                        scheduleBackendRelease(sessionId)
                    }
                }
            }
        }

        override fun run() {
            var deliveryStarted = false
            try {
                val audioRecord = microphone.acquire { createAudioRecord() }
                    ?: if (stopRequested) return else error("麦克风打不开：请检查 CyIME 的录音权限，或关掉正在用麦克风的应用")
                if (stopRequested) return
                // 麦克风启动失败不能悄无声息地结束：报给用户（键盘上会显示）。
                val started = microphone.start {
                    if (drainBytes < 0 && !stopRequested) audioFocus.acquire(otherAudioOwner)
                }
                if (!started) error("麦克风没能开始录音：可能被其他应用占用，或没有录音权限")
                FileLogger.i(TAG, "Microphone ready session=$session elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAt)}")
                deliveryThread = Thread(::deliverAudio, "AsrAudioDelivery").also { it.start() }
                deliveryStarted = true
                val draining = drainBytes >= 0
                if (!draining) mainHandler.post {
                    if (!released && !stopRequested && recordingThread === this) setState(RecognitionState.LISTENING)
                }
                var drained = 0L

                val buffer = ShortArray((SAMPLE_RATE * BUFFER_SIZE_SECONDS).toInt())
                val byteBuffer = ByteArray(buffer.size * 2)
                var speechDetected = false
                // 语音前缓冲：保存检测到语音前的若干块，检测到后一起送入 ASR，
                // 避免"你/觉"等弱开头的语音块因音量低于阈值被当作静音丢弃
                val preSpeechBuffer = ArrayDeque<CapturedAudio>()
                val maxPreSpeechChunks = 4  // 0.4s 语音前缓冲

                while (!interrupted() && !stopRequested) {
                    val want = if (draining) minOf(buffer.size.toLong(), (drainBytes - drained) / 2).toInt() else buffer.size
                    if (want <= 0) break
                    // 收尾：不等新声音，存着的读完（或读到松手那一刻）就停
                    val nread = if (draining) audioRecord.read(buffer, 0, want, AudioRecord.READ_NON_BLOCKING)
                        else audioRecord.read(buffer, 0, buffer.size)
                    if (nread > 0) {
                        drained += nread * 2L
                        var peak = 0
                        for (i in 0 until nread) {
                            val s = buffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            val abs = if (s < 0) -s else s
                            if (abs > peak) peak = abs
                        }
                        // 归一化振幅（0~1）与频段频谱，驱动频谱可视化
                        val normalized = (peak / 32768f).coerceIn(0f, 1f)
                        val spectrum = spectrumAnalyzer.analyze(buffer, nread)
                        mainHandler.post {
                            if (!released && session == sessionId) {
                                amplitudeCallback?.invoke(normalized)
                                spectrumCallback?.invoke(spectrum)
                            }
                        }
                        val chunk = CapturedAudio(byteBuffer.copyOf(nread * 2), requestedLanguage)
                        if (!speechDetected) {
                            preSpeechBuffer.addLast(chunk)
                            // 缓冲满仍未检测到语音：放弃 VAD，直接开始识别，
                            // 保证整段弱音内容也能送入 ASR（开头静音已被缓冲丢弃）
                            if (preSpeechBuffer.size >= maxPreSpeechChunks) {
                                speechDetected = true
                                while (preSpeechBuffer.isNotEmpty()) {
                                    enqueue(preSpeechBuffer.removeFirst())
                                }
                            } else if (isSpeech(chunk.pcm)) {
                                speechDetected = true
                                // 把语音前缓冲的块按顺序送入 ASR，保证开头不丢失
                                while (preSpeechBuffer.isNotEmpty()) {
                                    enqueue(preSpeechBuffer.removeFirst())
                                }
                            }
                        } else {
                            enqueue(chunk)
                        }
                    } else if (nread < 0 || draining) {
                        break
                    }
                }
                // 收尾时整段都不到语音前缓冲的长度：剩下的也送去认，不因没过音量门槛丢掉
                if (draining) while (preSpeechBuffer.isNotEmpty()) enqueue(preSpeechBuffer.removeFirst())
            } catch (error: Exception) {
                reportFailure(error)
                cancelRequested = true
            } finally {
                stopRequested = true
                microphone.close()
                audioFocus.release(otherAudioOwner)
                captureEnded = true
                watchShutdown()
                if (deliveryStarted) {
                    // A timed-out join must not advertise the backend as free while native work
                    // still owns it. Cancellation already releases capture and discards queued PCM.
                    awaitBackendFinished()
                } else {
                    previousRecording?.awaitBackendFinished()
                    backendFinished.countDown()
                    completeRecording()
                }
                Log.d(TAG, "Recognition thread ended; microphone released")
            }
        }

        fun forceStopAudio() {
            stopRequested = true
            microphone.close()
            audioFocus.release(otherAudioOwner)
            watchShutdown()
        }

        private fun isSpeech(chunk: ByteArray): Boolean {
            var peak = 0
            for (i in 0 until chunk.size / 2) {
                val low = chunk[i * 2].toInt() and 0xFF
                val high = chunk[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
            }
            return peak > SPEECH_THRESHOLD
        }
    }

    private fun postForSession(token: Int, action: () -> Unit) {
        mainHandler.post {
            if (!released && token == sessionId) action()
        }
    }

    private fun bindSessionCallbacks(currentBackend: AsrBackend, token: Int, acceptsCallback: () -> Boolean) {
        currentBackend.setCallbacks(
            onResult = { text -> postForSession(token) { if (acceptsCallback()) resultCallback?.invoke(text) } },
            onPartialResult = { text -> postForSession(token) { if (acceptsCallback()) partialResultCallback?.invoke(text) } },
            onStateChange = { state -> postForSession(token) { if (acceptsCallback()) setState(state) } },
            onError = { error -> postForSession(token) { if (acceptsCallback()) errorCallback?.invoke(error, true) } },
        )
    }
}
