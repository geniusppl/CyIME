package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.swipeHandlerFor
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.usesT9CandidateNavigation
import com.kingzcheung.xime.service.highlightedT9Syllable
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 拼音九键（T9）键盘布局。
 *
 * 布局说明：
 * - 左侧候选区：占用右侧第 1/4/7 三个数字键的垂直高度，显示当前音节候选拼音。
 * - 右侧主键区：
 *   第1行：分词(1) | ABC(2) | DEF(3) | 退格
 *   第2行：GHI(4) | JKL(5) | MNO(6) | 重输
 *   第3行：PQRS(7) | TUV(8) | WXYZ(9) | 0
 *   第4行：符 | 123 | 空格(显示方案名) | 中/En(地球图标) | 发送
 * - 按住左下角 !@# 当 Ctrl（同 26 键，用户 11-03）：1 关闭 2 刷新 3 重做 4 全选 5 保存 6 剪切 7 撤销 8 复制 9 粘贴，
 *   回车 = Ctrl+回车（发送）。数字键对应的字母见 [com.kingzcheung.xime.keyboard.CtrlShortcuts.t9Letter]。
 */
@Composable
fun T9KeyboardLayout(
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    t9Controller: T9InputController,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    accentColor: Color = Color(0xFF1A73E8),
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    KeyboardKeySpacingScope(modifier, columns = 5f * 3f / 3.4f,
        policy = KeyVisualPolicy.T9, allowShrink = isFloatingMode, applyGutter = true,
        growthSpacing = keySpacingX?.value to keySpacingY?.value) { bodyModifier ->
    val controller = t9Controller
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun handleDelete() {
        if (callbacks.onPreeditKeyInput?.invoke("delete") == true) return
        // onDeleted 在后台队列中处理（flush 不阻塞 UI），结果通过回调返回（Main 线程）。
        controller.onDeleted { result ->
            when (result) {
                T9InputController.DeleteResult.UNDO_COMMIT -> {
                    controller.clearRimeAndResend()
                }

                T9InputController.DeleteResult.NOT_CONSUMED -> {
                    onKeyPress("delete")
                }

                T9InputController.DeleteResult.DELETED, T9InputController.DeleteResult.UNDO_CHOICE -> {
                }
            }
        }
    }

    T9KeyboardSwipeOverlay(
        modifier = bodyModifier,
        keyboardBackgroundColor = keyboardBackgroundColor,
        keyCornerRadius = keyCornerRadius,
        keyTextColor = keyTextColor,
        isLandscape = isLandscape,
        isFloatingMode = isFloatingMode,
        onKeyPress = onKeyPress,
        callbacks = callbacks,
        uiState = uiState,
        controller = controller,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        bubbleBackgroundColor = bubbleBackgroundColor,
        accentColor = accentColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPressDown = onKeyPressDown,
        onDelete = ::handleDelete,
        specialKeyTextColor = specialKeyTextColor,
        candidateState = candidateState,
        keySpacingX = keySpacingX,
        keySpacingY = keySpacingY,
        onGestureAction = onGestureAction,
    )
    }
}


// ─── 滑动气泡覆盖层（隔离 swipeState 作用域，避免全键盘重组） ────────

@Composable
private fun T9KeyboardSwipeOverlay(
    modifier: Modifier,
    keyboardBackgroundColor: Color,
    keyCornerRadius: Dp,
    keyTextColor: Color,
    isLandscape: Boolean,
    isFloatingMode: Boolean,
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    controller: T9InputController,
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    accentColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onDelete: () -> Unit,
    specialKeyTextColor: Color = Color.White,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val dragSpace = LocalKeyDragSpace.current
    var dragArea by remember { mutableStateOf(Rect.Zero) }
    val input = LocalKeyboardInputPreferences.current
    val glide = input.t9GlideTyping && input.cursorGesture != CursorGestureMode.KEYBOARD
    val glideKeys = remember(glide, controller, uiState.inputProfile) { if (glide) mutableMapOf<Char, Rect>() else null }
    val trail = remember(glideKeys, controller) { GlideTrail() }
    val glideScope = rememberCoroutineScope()
    val glideContext = LocalContext.current.applicationContext
    // 全拼、双拼九键按词表比对整条线（别的用户 11-03：路过的键都算进去，正确率低）；其他九键方案只取起点、拐弯和终点。
    val glideProfile = uiState.inputProfile
    val glideDoublePinyin = if (glideProfile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN)
        com.kingzcheung.xime.settings.DoublePinyinSchemes.find(uiState.currentSchemaId) else null
    val glideDictionary = glideProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
        (glideProfile.scheme == com.kingzcheung.xime.settings.InputScheme.PINYIN || glideDoublePinyin != null)
    // 提前读入词表，第一次滑行少等一点。
    androidx.compose.runtime.LaunchedEffect(glide, glideDictionary) {
        if (glide && glideDictionary) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.kingzcheung.xime.glide.GlideLexicon.pinyin(glideContext) }
        }
    }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top
            )
        )
    }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    // 按住 !@# 当 Ctrl：数字键和回车登记成目标，和 26 键同一套（用户 11-03）。九键没有 Shift，只有 Ctrl 用它。
    val ctrlTargets = remember(controller, uiState.inputProfile) { ShiftSlideTargets() }


    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius, LocalT9GlideKeys provides glideKeys) {
    Box(
        modifier = modifier
            .t9GlideTyping(glideKeys, trail, { keyboardBounds.topLeft }, callbacks.inputAdmissionTicket) { xs, ys ->
                val keys = glideKeys?.mapValues { (_, rect) ->
                    com.kingzcheung.xime.keyboard.LetterKeyRect(rect.left, rect.top, rect.width, rect.height)
                } ?: return@t9GlideTyping
                if (keys.isEmpty()) return@t9GlideTyping
                // 输入框归属在抬手时确定；识别完成前换了输入框则作废（与 26 键滑行相同）。
                val inputContext = runCatching { callbacks.captureInputContext() }.getOrNull() ?: return@t9GlideTyping
                val dictionary = glideDictionary
                val doublePinyin = glideDoublePinyin
                glideScope.launch {
                    val digits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching {
                            com.kingzcheung.xime.glide.GlideLexicon.t9Digits(glideContext, keys, xs, ys, dictionary, doublePinyin)
                        }.getOrDefault("")
                    }
                    if (digits.isEmpty()) return@launch
                    kotlinx.coroutines.withContext(inputContext) {
                        for (digit in digits) {
                            val key = digit.toString()
                            if (callbacks.onPreeditKeyInput?.invoke(key) != true) controller.onDigitPressed(key)
                        }
                    }
                }
            }
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
                dragArea = dragSpace.bounds(coordinates)
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp)) {
        KeyboardSwipeBubbleOverlay(swipeBubble, bubbleBackgroundColor, keyTextColor, specialKeyTextColor, keyboardBounds.width)
        if (isLandscape) {
            // 横屏：去除原左侧候选大面板（候选已在顶部候选栏展示），
            // 九键布局撑满键盘区域（与全键盘横屏同款 50dp 边距）
            CompositionLocalProvider(
                LocalShiftSlideTargets provides ctrlTargets,
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = KeyboardGridBottomMargin),
                ) {
                    T9KeyboardContent(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        controller = controller,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        accentColor = accentColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        onDelete = onDelete,
                        compactMode = true,
                        candidateState = candidateState,
                        onGestureAction = onGestureAction,
                        dragArea = { dragArea },
                        ctrlTargets = ctrlTargets,
                    )
                }
            }
        } else {
            CompositionLocalProvider(
                LocalShiftSlideTargets provides ctrlTargets,
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(bottom = KeyboardGridBottomMargin),
                ) {
                    T9KeyboardContent(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        controller = controller,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        accentColor = accentColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        onDelete = onDelete,
                        compactMode = false,
                        candidateState = candidateState,
                        onGestureAction = onGestureAction,
                        dragArea = { dragArea },
                        ctrlTargets = ctrlTargets,
                    )
                }
            }
        }
        CandidateDragLine(CandidateDropTargets.shared, { dragArea.topLeft },
            KeyboardThemes.getAccentColor(uiState.themeId, uiState.isDarkTheme), Modifier.matchParentSize())
        ShiftSlideLine(ctrlTargets, { dragArea.topLeft },
            KeyboardThemes.getAccentColor(uiState.themeId, uiState.isDarkTheme), Modifier.matchParentSize())
        if (glide) GlideTrailOverlay(trail, accentColor, Modifier.matchParentSize())
    }
    }
}

// ─── 九键键盘三列主体（竖屏整宽 / 横屏右栏复用） ──────────────────────

@SuppressLint("SuspiciousIndentation")
@Composable
private fun T9KeyboardContent(
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    controller: T9InputController,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    accentColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    onDelete: () -> Unit,
    compactMode: Boolean = false,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    /** 键盘区在根布局里的位置：0 键拖动超过它宽或高的一半（设置里可调）才算拖动选词。 */
    dragArea: () -> Rect = { Rect.Zero },
    /** 按住 !@# 当 Ctrl 的目标（数字键、回车）。 */
    ctrlTargets: ShiftSlideTargets,
) {
    val t9DigitFontSize = if (compactMode) 13.sp else 16.sp
    val ctrlFontSize = if (compactMode) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val specialKeyTextColor = if (uiState.isDarkTheme) Color.White
        else KeyboardThemes.getSpecialKeyTextColor(uiState.themeId, false)

    // 数字键滑动手势（keyboard.t9.keys，热重载经 configVersion 感知）：
    // 上滑默认直接上屏数字（T9 模式 onKeyPress(数字) 会进拼音数字码组合，须走 onCommitText），
    // 下滑默认绑定快捷编辑动作。提示开关只控制提示显示；组件内上滑触发只看回调绑定，
    // 提示关闭/横屏紧凑时手势仍可用。上滑键面提示尊重 display: bubble（仅气泡不印键面）。
    val configVersion by KeysConfigHelper.configVersion.collectAsState()
    val swipeHints = rememberSwipeHintsEnabled()
    val hintsActive = !compactMode
    val commitDirect: (String) -> Unit =
        { text -> callbacks.onCommitText?.invoke(text) ?: onKeyPress(text) }
    // 按住 !@# 当 Ctrl：和 26 键同一条命令（用户 11-03）；没有手势回调时 !@# 照旧只开符号。
    val onCtrlShortcut: ((Char) -> Unit)? = onGestureAction?.let { action ->
        { letter: Char -> action(GestureAction.COMMAND, com.kingzcheung.xime.keyboard.CtrlShortcuts.command(letter)) }
    }

    fun swipesFor(id: String): T9KeySwipes {
        // 读一次版本号：手势配置热重载时本层重组。以前靠每次按键顺带重组才更新。
        if (configVersion < 0) return T9KeySwipes()
        val gesture = KeysConfigHelper.getT9KeyGesture(id) ?: return T9KeySwipes()
        val upHint = gesture.swipeUp?.let { it.label.ifEmpty { it.value } }
        val downHint = gesture.swipeDown?.let { it.label.ifEmpty { it.value } }
        // display 三态：key=仅键面提示（无气泡）、bubble=仅滑动气泡、both=键面+气泡。
        // 内置数字上滑默认 both：滑动显示预览，松手输入数字。
        // SwipeableKeyButton 键面提示取 swipeUpKeyLabel ?: swipeText（null 回退气泡文本），
        // bubble 模式传空串显式压制键面显示。手势回调独立于提示与 display。
        val swipeUpKeyLabel = when {
            !swipeHints.up || !hintsActive -> ""
            gesture.swipeUp?.display == DisplayMode.BUBBLE -> ""
            else -> upHint
        }
        return T9KeySwipes(
            symbolInputText = gesture.swipeUp?.let { symbolInputValue(it.value.ifEmpty { it.label }, it.action) },
            onSwipeUp = swipeHandlerFor(gesture.swipeUp, commitDirect, onGestureAction),
            onSwipeDown = swipeHandlerFor(gesture.swipeDown, commitDirect, onGestureAction),
            swipeUpText = if (swipeHints.up &&
                gesture.swipeUp?.display != DisplayMode.KEY) upHint else null,
            swipeDownText = if (swipeHints.down &&
                gesture.swipeDown?.display != DisplayMode.KEY) downHint else null,
            swipeUpKeyLabel = swipeUpKeyLabel,
            swipeDownKeyLabel = if (swipeHints.down && hintsActive &&
                gesture.swipeDown?.display != DisplayMode.BUBBLE) downHint else null,
        )
    }

    // 九键字母跟「键帽字母大小写」走，和 26/14 键同一条规则（用户 11-05：选小写九键仍是 ABC）。只改显示。
    val upperLetters = letterLabelsUppercase(LocalKeyboardInputPreferences.current, uiState.isAsciiMode, uiState.inputProfile)
    val density = LocalDensity.current
    val dragSpace = LocalKeyDragSpace.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val candidateShadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, keyBackgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { KeyShapes.current.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(keyBackgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            // 按住 !@# 拖出这块键区松手不发快捷键。
            .onGloballyPositioned { ctrlTargets.keyboardBounds = dragSpace.bounds(it) },
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── 第1列：左侧候选区（拼音候选项） ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.8f).testTag("t9-left-rail"),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(3f)
                    .padding(scaledKeyVisualPadding())
                    .then(candidateShadowModifier)
                    .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                    .background(frostedKeyColor(keyBackgroundColor, keyTextColor, frostedGlass))
            ) {
                val showCandidates = controller.leftPanelState != T9InputController.LeftPanelState.IDLE
                val highlightedSyllable = candidateState.value.highlightedT9Syllable(uiState.inputProfile)
                val currentFirstOptions = controller.firstOptions
                // 空闲态符号列表来自 xime.yaml keyboard.t9.side_symbols（可自定义，>4 滚动）
                val configVersion by KeysConfigHelper.configVersion.collectAsState()
                val customSymbols = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.T9)
                val t9SideSymbols = customSymbols
                    ?: remember(configVersion) { KeysConfigHelper.getT9SideSymbols() }
                val displayItems: List<String> = if (showCandidates) {
                    currentFirstOptions.map { if (it.near) "≈" + it.pinyin else it.pinyin }
                } else {
                    t9SideSymbols
                }
                // One four-slot scale for letters and syllables, independent of option count.
                val metrics = t9CandidateMetrics(maxWidth.value, maxHeight.value,
                    LocalDensity.current.fontScale, LocalKeyboardInputPreferences.current.keyTextScale,
                    keyContentScale(maxWidth.value, maxHeight.value / 4f))
                val sample = rememberTextMeasurer().measure("shuang",
                    TextStyle(fontSize = metrics.fontSizeSp.sp, fontFamily = AppFonts.candidateFontFamily),
                    maxLines = 1, softWrap = false)
                val availableTextWidth = with(LocalDensity.current) { (maxWidth - 8.dp).toPx() }.coerceAtLeast(1f)
                val candidateFontSize = metrics.fontSizeSp * minOf(1f, availableTextWidth / sample.size.width.coerceAtLeast(1))
                // 超出四格时右缘显示滚动条；空闲的符号列末尾放「编辑」，点开这一栏的符号设置。
                val railState = androidx.compose.foundation.lazy.rememberLazyListState()
                // 符号列滑下去后开始打字，拼音列要从第一个起。
                androidx.compose.runtime.LaunchedEffect(showCandidates) { railState.scrollToItem(0) }
                LazyColumn(
                    state = railState,
                    modifier = Modifier.fillMaxSize().testTag("t9-pinyin-options")
                        .railScrollIndicator(railState, keyTextColor.copy(alpha = 0.35f)),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(displayItems) { index, item ->
                        val option = currentFirstOptions.getOrNull(index).takeIf { showCandidates }
                        // 「下一项」换了高亮时，左栏标出高亮候选的第一个音节（用户 10-27：和候选栏、编码一致）。
                        val explicitSelection = controller.leftPanelState == T9InputController.LeftPanelState.SELECTION &&
                            controller.isSelectedOptionInCurrentCandidates()
                        val isSelected = option != null && if (explicitSelection) controller.selectedOption == option
                            else option.pinyin == highlightedSyllable
                        T9CandidateItem(
                            text = item,
                            preserveWidth = !showCandidates && customSymbols != null,
                            onClick = {
                                if (option != null) {
                                    callbacks.onDismissPreeditEditor?.invoke()
                                    controller.onChoiceSelected(option)
                                } else if (customSymbols != null) {
                                    (callbacks.onCommitExactText ?: callbacks.onCommitText ?: onKeyPress)(item)
                                } else commitDirect(item)
                            },
                            onPress = { onKeyPressDown?.invoke(item) },
                            textColor = keyTextColor,
                            backgroundColor = keyBackgroundColor,
                            accentColor = accentColor,
                            fontSize = candidateFontSize.sp,
                            isSelected = isSelected,
                            modifier = Modifier.fillMaxWidth().height(metrics.rowHeightDp.dp)
                                .testTag("t9-pinyin-option:$index")
                        )
                    }
                    if (!showCandidates) item(key = "edit") {
                        SideRailEditItem(SideSymbolTarget.T9, keyTextColor, keyBackgroundColor,
                            iconSize = (candidateFontSize * 1.1f).dp, onPress = { onKeyPressDown?.invoke("edit") },
                            modifier = Modifier.fillMaxWidth().height(metrics.rowHeightDp.dp).testTag("t9-side-symbols-edit"))
                    }
                }
            }

            KeyboardModeKey(
                slot = 1, onKeyPress = onKeyPress,
                backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                modifier = Modifier.fillMaxWidth().weight(1f), onKeyPressDown = onKeyPressDown,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                ctrlTargets = ctrlTargets, onCtrlShortcut = onCtrlShortcut,
            )
        }

        // ── 第2列：数字键区 ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(3.4f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                NineKeyButton(
                    swipes = swipesFor("1"),

                    digit = "1", letters = "分词", flickDigitLongPress = true,
                    ctrlShortcutLetter = com.kingzcheung.xime.keyboard.CtrlShortcuts.t9Letter('1'),
                    onSwipeStateChange = onSwipeStateChange,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("1") != true) controller.onDigitPressed("1") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("1") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("2"),
                    digit = "2", letters = "ABC", longPressItems = listOf("A", "B", "C"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("2") != true) controller.onDigitPressed("2") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("2") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("3"),
                    digit = "3", letters = "DEF", longPressItems = listOf("D", "E", "F"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("3") != true) controller.onDigitPressed("3") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("3") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                T9DigitKey(
                    swipes = swipesFor("4"),
                    digit = "4", letters = "GHI", longPressItems = listOf("G", "H", "I"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("4") != true) controller.onDigitPressed("4") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("4") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("5"),
                    digit = "5", letters = "JKL", longPressItems = listOf("J", "K", "L"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("5") != true) controller.onDigitPressed("5") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("5") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("6"),
                    digit = "6", letters = "MNO", longPressItems = listOf("M", "N", "O"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("6") != true) controller.onDigitPressed("6") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("6") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                T9DigitKey(
                    swipes = swipesFor("7"),
                    digit = "7", letters = "PQRS", longPressItems = listOf("P", "Q", "R", "S"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("7") != true) controller.onDigitPressed("7") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("7") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("8"),
                    digit = "8", letters = "TUV", longPressItems = listOf("T", "U", "V"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("8") != true) controller.onDigitPressed("8") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("8") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
                T9DigitKey(
                    swipes = swipesFor("9"),
                    digit = "9", letters = "WXYZ", longPressItems = listOf("W", "X", "Y", "Z"), uppercaseLetters = upperLetters,
                    onClick = { if (callbacks.onPreeditKeyInput?.invoke("9") != true) controller.onDigitPressed("9") },
                    onLongPressSelect = { letter -> if (callbacks.onPreeditKeyInput?.invoke(letter) != true && !controller.onLetterFlicked(letter)) onKeyPress(letter) },
                    onSwipeStateChange = onSwipeStateChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("9") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    fontSize = t9DigitFontSize,
                )
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                KeyboardModeKey(
                    slot = 2, onKeyPress = onKeyPress,
                    backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                    modifier = Modifier.weight(0.8f), onKeyPressDown = onKeyPressDown,
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    ctrlTargets = ctrlTargets, onCtrlShortcut = onCtrlShortcut,
                )
                T9SpaceKey(
                    schemaName = "拼音", isSttEnabled = uiState.isSttEnabled,
                    voiceSticky = uiState.voiceSticky,
                    onKeyPress = onKeyPress, onKeyPressDown = onKeyPressDown,
                    onVoiceModeChange = callbacks.onVoiceModeChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1.8f),
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
                LanguageKeyButton(
                    icon = rememberVectorPainter(Icons.Default.Language),
                    onClick = { onKeyPress("ime_switch") },
                    backgroundColor = specialKeyBackgroundColor, iconColor = specialKeyTextColor,
                    modifier = Modifier.weight(0.8f),
                    onPress = { onKeyPressDown?.invoke("ime_switch") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
            }
        }

        // ── 第3列：功能键（退格 / 重输 / 确定） ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.8f).testTag("t9-right-rail"),
        ) {
            SwipeableIconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                onClick = { onDelete() },
                onLongClick = { onDelete() },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = Modifier.weight(1f).testTag("t9-delete-key").semantics { contentDescription = "删除" },
                swipeText = if (compactMode) null else "清空",
                onSwipe = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("delete") },
                followSymbolSwipeDirection = true,
                upFeedback = KeyFlickFeedback.DELETE,
                swipeUpLabel = "上滑清空",
                downFeedback = KeyFlickFeedback.UNDO,
                swipeDownLabel = "下滑撤回",
                onSwipeUp = { onKeyPress("clear_all") },
                onSwipeDown = { onKeyPress("undo_clear") },
                distanceDelete = true,
                onSwipeStateChange = { state, bounds ->
                    onSwipeStateChange?.invoke(state, bounds)
                },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            ResetKey(
                onClick = {
                    controller.clearAll()
                    // 重输 = 只清输入态（预编辑/候选/左栏），不动已上屏文本（对标主流输入法 2026-08-11）：
                    //   · 输入态点重输 → 清除输入态内容
                    //   · 空闲态点重输 → 无反应（clear_composition 无可清内容）
                    onKeyPress("clear_composition")
                },
                onPress = { onKeyPressDown?.invoke("clear") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                compactMode = compactMode,
            )
            // 只在「0 键是不是下一项」变化时重组九键整块，每按一个数字只重组候选栏和左栏。
            val nextCandidate by remember(candidateState, uiState.inputProfile) {
                androidx.compose.runtime.derivedStateOf { candidateState.value.usesT9CandidateNavigation(uiState.inputProfile) }
            }
            // 长按“下一项”= 按住 Shift：按住期间候选中英对调，松手就恢复（用户 10-30：松手不恢复就再也变不回去）；
            // 长按那次松手不切下一项。
            var glossHoldUsed by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(nextCandidate) {
                if (!nextCandidate) com.kingzcheung.xime.gloss.CandidateGlossary.t9NextHeld.value = false
            }
            // 读 revision 让词典加载完成后重组到这里。
            val glossHoldEnabled = nextCandidate && com.kingzcheung.xime.gloss.CandidateGlossary.revision
                .let { com.kingzcheung.xime.gloss.CandidateGlossary.isLoaded }
            NineKeyButton(
                digit = "", letters = if (nextCandidate) "下一项" else "0",
                icon = if (nextCandidate) rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight) else null,
                // 箭头只占图标框中间一小块，按退格等功能图标的视觉高度放大（用户 10-24：太小看不清）。
                iconSizeDp = T9NextCandidateIconDp,
                onClick = {
                    if (glossHoldUsed) glossHoldUsed = false
                    else if (nextCandidate) {
                        callbacks.onDismissPreeditEditor?.invoke()
                        callbacks.onNextCandidate(candidateState.value)
                    } else commitDirect("0")
                },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                // 拖出超过键盘一半宽或高（设置里可调）才算拖动选词（画出拖线），拖到候选上松手等于按住 Shift 选它（有英文上屏英文）；
                // 没拖那么远仍是本键的左滑「上一项」等手势（用户 10-25）。拖动选词和 26 键 Shift 拖动一样不看释义词典：
                // 注释是拼音时照样选中文候选（用户 10-31：九键拖不动了）；只有长按对调中英要等词典。
                modifier = Modifier.weight(1f).testTag("t9-zero-key").dragToCandidate(nextCandidate, dragArea,
                    fraction = LocalKeyboardInputPreferences.current.candidateDragPercent / 100f).t9NextShiftHold(glossHoldEnabled,
                    onDown = { glossHoldUsed = false }, onHeld = { glossHoldUsed = true }),
                onSwipeLeft = if (nextCandidate) ({
                    callbacks.onDismissPreeditEditor?.invoke()
                    callbacks.onPreviousCandidate(candidateState.value)
                }) else null,
                onSwipeRight = if (nextCandidate) ({
                    callbacks.onDismissPreeditEditor?.invoke()
                    callbacks.onNextCandidate(candidateState.value)
                }) else null,
                onPress = { onKeyPressDown?.invoke("0") },
                onSwipeStateChange = onSwipeStateChange, swipes = swipesFor("0"),
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            // 按住 !@# 点回车、或从 !@# 拖到回车上松手：Ctrl+回车，发送（同 26 键，用户 11-03）。
            // 位置一定下来就登记（这一列布局后不一定重组，只靠重组会漏登记，拖到回车上没反应）；
            // 目标换了一份时重组再按记下的位置登记一次。id 每个回车键各一个。
            val enterTargetId = remember { Any() }
            var enterBounds by remember { mutableStateOf(Rect.Zero) }
            androidx.compose.runtime.DisposableEffect(ctrlTargets) { onDispose { ctrlTargets.remove(enterTargetId) } }
            androidx.compose.runtime.SideEffect {
                if (enterBounds != Rect.Zero) ctrlTargets.putEnter(enterTargetId, enterBounds)
            }
            ActionKeyButton(
                text = uiState.enterKeyText,
                onClick = { if (!ctrlTargets.ctrlEnter()) onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f).testTag("t9-enter-key")
                    .onGloballyPositioned {
                        enterBounds = dragSpace.bounds(it)
                        ctrlTargets.putEnter(enterTargetId, enterBounds)
                    },
                onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
        }
    }
}

// ─── 九键数字键（可长按） ──────────────────────────────────────────────

/** 九键数字键的滑动配置：回调 + 提示文本（均受 keyboard.t9.keys 配置与提示开关控制）。 */
internal data class T9KeySwipes(
    val symbolInputText: String? = null,
    val onSwipeUp: (() -> Unit)? = null,
    val onSwipeDown: (() -> Unit)? = null,
    /** 上滑滑动气泡文本（display: key 时不传） */
    val swipeUpText: String? = null,
    /** 下滑滑动气泡文本（display: key 时不传） */
    val swipeDownText: String? = null,
    /** 上滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 不显示） */
    val swipeUpKeyLabel: String? = null,
    val swipeDownKeyLabel: String? = null,
)

/**
 * 九键数字键，复用 [SwipeableKeyButton] 的长按弹出逻辑。
 * - 主体显示字母（ABC），右上角叠加数字浮标。
 * - 点按走 [onClick]，长按走 [onLongPressSelect]，上/下滑走 [onSwipeUp]/[onSwipeDown]。
 */
@Composable
private fun T9DigitKey(
    digit: String,
    letters: String,
    longPressItems: List<String>,
    onClick: () -> Unit,
    onLongPressSelect: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    swipes: T9KeySwipes = T9KeySwipes(),
    /** 键帽字母大写还是小写（[letterLabelsUppercase]）；长按、拂动送出的仍是 [longPressItems] 原值。 */
    uppercaseLetters: Boolean = true,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val shownItems = remember(longPressItems, uppercaseLetters) { t9LetterLabels(longPressItems, uppercaseLetters) }
    val itemValue = remember(longPressItems, shownItems) { { shown: String -> longPressItems.getOrNull(shownItems.indexOf(shown)) ?: shown } }
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentSwipes by rememberUpdatedState(swipes)
    val inputPreferences = LocalKeyboardInputPreferences.current
    // 九键拂动：左上右下依次为各字母，点按仍是九键输入，数字（原上滑动作）改为长按。
    val flick = inputPreferences.t9FlickLetters && currentOnLongPressSelect != null
    val glideKeys = LocalT9GlideKeys.current

    SwipeableKeyButton(
        text = t9LetterLabel(letters, uppercaseLetters),
        onClick = { currentOnClick() },
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSize = fontSize,
        modifier = if (glideKeys == null) modifier else modifier.onGloballyPositioned {
            glideKeys[digit.single()] = it.boundsInRoot()
        },
        onPress = { currentOnPress?.invoke() },
        // 长按弹字母气泡和 26/14 键共用“长按弹出字母气泡”开关，默认关（用户 10-21）；拂动模式长按出数字，不受影响。
        longPressItems = if (flick || inputPreferences.letterLongPressMenu) shownItems else emptyList(),
        onLongPressSelect = { letter -> currentOnLongPressSelect?.invoke(itemValue(letter)) },
        // Four-letter keys: P/S on the left/right, Q/R up/down (user layout, 2026-10-18); three letters go left/up/right.
        flickLetters = if (!flick) null else if (shownItems.size == 4)
            listOf(shownItems[0], shownItems[1], shownItems[3], shownItems[2]) else shownItems,
        onFlickLetter = { letter -> currentOnLongPressSelect?.invoke(itemValue(letter)) },
        onFlickLongPress = currentSwipes.onSwipeUp,
        flickThresholdDp = normalizeLetterSwipeDistance(inputPreferences.fourteenLetterSwipeDp),
        onSwipeStateChange = { state, bounds ->
            // 拂动方向盘已经显示按下的键。其余普通点按由全局气泡开关决定。
            val discPress = flick && inputPreferences.flickDirectionDisc && state.isPressed &&
                !state.isLongPress && state.pressedText != null
            if (!discPress) onSwipeStateChange?.invoke(state, bounds)
        },
        badgeText = digit,
        swipeText = currentSwipes.swipeUpText,
        symbolInputText = currentSwipes.symbolInputText,
        swipeDownText = currentSwipes.swipeDownText,
        swipeUpKeyLabel = currentSwipes.swipeUpKeyLabel,
        swipeDownKeyLabel = currentSwipes.swipeDownKeyLabel,
        onSwipe = currentSwipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = currentSwipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        ctrlShortcutLetter = digit.singleOrNull()?.let(com.kingzcheung.xime.keyboard.CtrlShortcuts::t9Letter),
    )
}

/** 九键键帽上的字母（ABC、PQRS…）按「键帽字母大小写」显示；"分词"等非字母原样。 */
internal fun t9LetterLabel(letters: String, uppercase: Boolean): String =
    if (letters.isEmpty() || !letters.all { it in 'a'..'z' || it in 'A'..'Z' }) letters
    else if (uppercase) letters.uppercase() else letters.lowercase()

internal fun t9LetterLabels(items: List<String>, uppercase: Boolean): List<String> = items.map { t9LetterLabel(it, uppercase) }

// ─── 子组件 ───────────────────────────────────────────────────────────

@Composable
internal fun T9CandidateItem(
    text: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    textColor: Color,
    backgroundColor: Color = Color.Transparent,
    accentColor: Color = Color(0xFF1A73E8),
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    isSelected: Boolean = false,
    preserveWidth: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isPressed) frostedKeyColor(
                backgroundColor, textColor, LocalKeyboardInputPreferences.current.frostedGlass,
                legacyStateColor = backgroundColor.copy(alpha = 0.7f), pressed = true,
            ) else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        val fittedFontSize = if (text.startsWith("≈")) {
            val measured = rememberTextMeasurer().measure(text, style = TextStyle(fontSize = fontSize,
                fontFamily = AppFonts.candidateFontFamily, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)).size.width
            val usable = with(LocalDensity.current) { (maxWidth - 6.dp).toPx() }.coerceAtLeast(1f)
            fontSize * minOf(1f, usable / measured.coerceAtLeast(1))
        } else fontSize
        if (isSelected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(accentColor.copy(alpha = 0.2f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = if (preserveWidth) text else punctuationKeyLabel(text),
                    color = accentColor,
                    fontSize = fittedFontSize,
                    lineHeight = fittedFontSize * 1.2f,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontFamily = AppFonts.candidateFontFamily
                )
            }
        } else {
            Text(
                text = if (preserveWidth) text else punctuationKeyLabel(text),
                color = textColor,
                fontSize = fittedFontSize,
                    lineHeight = fittedFontSize * 1.2f,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                fontFamily = AppFonts.candidateFontFamily
            )
        }
    }
}

/** 0 键「下一项」箭头的基准尺寸。 */
internal const val T9NextCandidateIconDp = 32f

/** 数字 1 分词键，与 [T9DigitKey] 统一使用 [SwipeableKeyButton] 保持角标位置一致 */
@Composable
internal fun NineKeyButton(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipes: T9KeySwipes = T9KeySwipes(),
    icon: androidx.compose.ui.graphics.painter.Painter? = null,
    iconSizeDp: Float = KeyboardKeyMetrics.FunctionIconSize.value,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    /** 九键拂动模式下与 2~9 一致：长按弹出本键数字，松手输入。 */
    flickDigitLongPress: Boolean = false,
    /** 按住 !@# 当 Ctrl 时这个键代表的字母（1 键 = W 关闭）。 */
    ctrlShortcutLetter: Char? = null,
) {
    val digitLongPress = swipes.onSwipeUp.takeIf {
        flickDigitLongPress && digit.isNotEmpty() && LocalKeyboardInputPreferences.current.t9FlickLetters
    }
    SwipeableKeyButton(
        text = letters,
        longPressItems = digitLongPress?.let { listOf(digit) },
        onLongPressSelect = digitLongPress?.let { handler -> { _: String -> handler() } },
        longPressTimeoutMillis = digitLongPress?.let { 300L },
        icon = icon,
        iconSizeDp = iconSizeDp,
        onSwipeLeft = onSwipeLeft,
        swipeLeftText = if (onSwipeLeft != null) "上一项" else null,
        // 有箭头图标时左滑预览也是箭头（左箭头 = 上一项），不出汉字（用户 10-25）。
        swipeLeftIcon = if (onSwipeLeft != null && icon != null)
            rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowLeft) else null,
        // 左滑上一项、右滑下一项成对（用户 10-25）。
        onSwipeRight = onSwipeRight,
        swipeRightText = if (onSwipeRight != null) "下一项" else null,
        swipeRightIcon = if (onSwipeRight != null && icon != null)
            rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight) else null,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        onSwipeStateChange = onSwipeStateChange,
        badgeText = digit,
        swipeText = swipes.swipeUpText,
        symbolInputText = swipes.symbolInputText,
        swipeDownText = swipes.swipeDownText,
        swipeUpKeyLabel = swipes.swipeUpKeyLabel,
        swipeDownKeyLabel = swipes.swipeDownKeyLabel,
        onSwipe = swipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        fontSize = fontSize,
        ctrlShortcutLetter = ctrlShortcutLetter,
    )
}

/**
 * 重输键刷新图标相对退格图标的放大倍数：Material 刷新字形高 16/24，退格字形高 18/24，
 * 按 18/16 放大后两个图标字形一样高（用户 11-03：两个图标不一样大）。
 */
internal const val ResetIconGlyphScale = 18f / 16f

/**
 * 重输键（九键、笔画共用）—— 文本在上、图标在下，尺寸规则与旁边的退格键（[SwipeableIconKeyButton]）一致：
 * 同一基准、同一缩放、同样大小的提示字；「功能键不显示提示字」开着时和退格的「清空」一起藏（用户 11-03）。
 */
@Composable
internal fun ResetKey(
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    compactMode: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(KeyShapes.current.radius(shadowShapeRadius))
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, backgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { KeyShapes.current.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            }
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.then(shadowModifier)
            .clip(shape)
            .background(frostedKeyColor(
                backgroundColor, textColor, frostedGlass,
                legacyStateColor = if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor,
                pressed = isPressed,
            )), glassColor = backgroundColor), contentAlignment = Alignment.Center
    ) {
        val contentScale = keyContentScale(maxWidth.value, maxHeight.value)
        // 与退格键同一套：提示字 10 号、图标基准 FunctionIconSize（紧凑模式也不另缩小）。
        val hintSize = 10f * adaptiveHintScale(contentScale)
        val backspaceIconSize = keyIconSizeDp(maxWidth.value, maxHeight.value)
        val hintOffset = KeyboardKeyMetrics.hintOffsetDp(maxHeight.value, hintSize, density.fontScale, contentScale)
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "重输",
            tint = textColor,
            modifier = Modifier.size((backspaceIconSize * ResetIconGlyphScale).dp)
        )

        // 键太矮放不下时只留图标，文字不能压在图标上。字形与退格同高，按退格的图标框判断，两键同时藏或同时显示。
        if (!compactMode && !LocalKeyboardInputPreferences.current.hideFunctionKeyHints &&
            KeyboardKeyMetrics.hintClearsIcon(hintOffset, hintSize, density.fontScale, backspaceIconSize)) {
            Text(
                text = "重输",
                color = keyHintColor(textColor, frostedGlass, 0.5f, LocalKeyboardInputPreferences.current.cornerHintOpacity),
                fontSize = hintSize.sp,
                lineHeight = (hintSize * 1.2f).sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = -hintOffset.dp),
                fontFamily = AppFonts.keyLabelFontFamily
            )
        }
    }
}

/** T9 空格键 */
@Composable
private fun T9SpaceKey(
    schemaName: String,
    isSttEnabled: Boolean,
    voiceSticky: Boolean = false,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    onVoiceModeChange: ((Boolean) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {

    SpaceKeyButton(onClick = { onKeyPress("space") }, backgroundColor = backgroundColor,
        textColor = textColor, schemaName = schemaName, modifier = modifier,
        onPress = { onKeyPressDown?.invoke("space") }, voiceSticky = voiceSticky,
        isSttEnabled = isSttEnabled, onVoiceModeChange = onVoiceModeChange,
        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
}
