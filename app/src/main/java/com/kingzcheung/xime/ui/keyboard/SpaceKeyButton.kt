package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 全键盘、九键、笔画和分栏布局共用的空格交互。 */
@Composable
fun SpaceKeyButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    schemaName: String = "",
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    isVoiceMode: Boolean = LocalKeyboardInputActions.current.isVoiceMode,
    voiceSticky: Boolean = LocalKeyboardInputActions.current.voiceSticky,
    isSttEnabled: Boolean = LocalKeyboardInputActions.current.isSttEnabled,
    onVoiceModeChange: ((Boolean) -> Unit)? = LocalKeyboardInputActions.current.onVoiceModeChange,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: TextUnit = 14.sp,
) {
    val settings = LocalKeyboardInputPreferences.current
    val actions = LocalKeyboardInputActions.current
    val onMove by rememberUpdatedState(actions.onCursorMove)
    val onMoveVertical by rememberUpdatedState(actions.onCursorMoveVertical)
    val onCursorMode by rememberUpdatedState(actions.onCursorModeChange)
    val onVoiceToggle by rememberUpdatedState(actions.onVoiceToggle)
    val onVoiceHoldEnd by rememberUpdatedState(actions.onVoiceHoldEnd)
    val onVoiceHoldCancel by rememberUpdatedState(actions.onVoiceHoldCancel)
    val voiceOn by rememberUpdatedState(isVoiceMode)
    val holdAction = settings.effectiveSpaceHold
    val cursorUnitPx = rememberCursorUnitPx()
    DisposableEffect(Unit) { onDispose { onCursorMode?.invoke(false) } }
    val currentClick by rememberUpdatedState(onClick)
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    var cursorActive by remember { mutableStateOf(false) }
    // 按住空格说话且开了「上滑取消」：键面写「上滑取消」；手指上滑过线后变成删除色、写「松手取消」。
    var voiceHolding by remember { mutableStateOf(false) }
    var voiceCancelArmed by remember { mutableStateOf(false) }
    val frostedGlass = settings.frostedGlass
    val shadow = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, backgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) Modifier.drawBehind {
            drawRoundRect(crispShadowColor(backgroundColor), topLeft = Offset(0f, shadowElevation.toPx()),
                size = size, cornerRadius = CornerRadius(KeyShapes.current.radius(shadowShapeRadius).toPx()))
        } else Modifier
    }
    val keyFill = frostedKeyColor(
        backgroundColor, textColor, frostedGlass,
        legacyStateColor = if (pressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor,
        pressed = pressed,
    )
    // 上滑取消的提示只在麦克风真开着时显示（语音总开关关着时长按不会开麦）。
    val showVoiceCancel = voiceHolding && isVoiceMode
    // 过线后用删除键上滑清空同一套删除色，任何主题、玻璃下都认得出。
    val cancelColors = if (showVoiceCancel && voiceCancelArmed) keyFlickFeedbackColors(keyFill, textColor,
        com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.accent ?: androidx.compose.material3.MaterialTheme.colorScheme.primary,
        KeyFlickFeedback.DELETE, 1f, frostedGlass.enabled) else null
    BoxWithConstraints(
        modifier.fillMaxSize().testTag("space-key")
            .pointerInput(holdAction, settings.cursorStepDp, settings.cursorSwipeLines, cursorUnitPx, settings.spaceHoldDelayMs,
                settings.voiceSwipeCancel) {
                val step = settings.cursorStepDp * cursorUnitPx
                val tapTolerance = maxOf(viewConfiguration.touchSlop, 24.dp.toPx())
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    // 刚移完光标的这一下不输入空格，也就不该有按键音和振动（按住照常进入移动光标）。
                    val guarded = SpaceAfterCursorGuard.blocksSpace(down.uptimeMillis)
                    if (!guarded) currentPress?.invoke()
                    var held = false
                    var movedBeforeHold = false
                    // 按住说话（用户 10-30：长按开了语音松手不停，和点麦克风是两回事）：这次长按开的语音，松手就结束并上屏。
                    var startedVoice = false
                    var lastX = down.position.x
                    var lastY = down.position.y
                    val cursorSteps = CursorStepAccumulator(step)
                    // A line is a much larger edit than a character. Keep its threshold
                    // independent of fast horizontal settings; coalesced events never skip rows.
                    val verticalSteps = CursorStepAccumulator(maxOf(48f * cursorUnitPx, step * 5f))
                    val timer = scope.launch {
                        delay(settings.spaceHoldDelayMs)
                        held = true
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        when (holdAction) {
                            SpaceHoldAction.CURSOR -> {
                                cursorActive = true
                                SpaceCursorShift.begin()
                                CursorLineMetrics.request()
                                onCursorMode?.invoke(true)
                            }
                            SpaceHoldAction.REPEAT -> while (true) { currentClick(); delay(70L) }
                            SpaceHoldAction.VOICE_TOGGLE -> {
                                // 工具栏麦克风已经在听时，长按只把它结束，松手不再动。
                                startedVoice = !voiceOn
                                onVoiceToggle?.invoke()
                                voiceHolding = startedVoice && settings.voiceSwipeCancel
                            }
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (held && holdAction == SpaceHoldAction.REPEAT &&
                                (change.position.x < 0f || change.position.x > size.width ||
                                    change.position.y < 0f || change.position.y > size.height)) break
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            // 上滑取消：手指高过空格顶边一个键高才算，左右挪不管；滑回来就恢复松手上屏。
                            if (voiceHolding) {
                                val armed = change.position.y < -size.height.toFloat()
                                if (armed != voiceCancelArmed) {
                                    voiceCancelArmed = armed
                                    if (armed) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                            // 拇指点空格常带几毫米横移：在约 4 mm 内仍算一次点击（三星、Gboard 偏移 3.6 mm 仍上屏），
                            // 超出才算滑动。系统 touchSlop 只有约 8 dp，曾让 2.4 mm 的偏移把空格吞掉（1.12.36 实测）。
                            if (!held && (abs(change.position.x - down.position.x) > tapTolerance ||
                                abs(change.position.y - down.position.y) > tapTolerance)) {
                                movedBeforeHold = true
                                timer.cancel()
                            }
                            if (cursorActive) {
                                cursorSteps.stepPx = lineCursorStepPx(view.rootView.width / 2f, settings.cursorSwipeLines,
                                    CursorLineMetrics.charsPerLine, step, cursorUnitPx)
                                val steps = cursorSteps.move(dx)
                                if (steps != 0) onMove?.invoke(steps)
                                val rows = verticalSteps.move(dy).coerceIn(-1, 1)
                                if (rows != 0) onMoveVertical?.invoke(rows)
                                if (steps != 0 || rows != 0) SpaceAfterCursorGuard.mark(change.uptimeMillis)
                            }
                            change.consume()
                            if (!change.pressed) {
                                if (!held && !movedBeforeHold && !guarded) {
                                    currentClick()
                                }
                                break
                            }
                        }
                    } finally {
                        if (cursorActive) {
                            SpaceAfterCursorGuard.mark(android.os.SystemClock.uptimeMillis())
                            SpaceCursorShift.end()
                        }
                        timer.cancel()
                        if (startedVoice) {
                            if (voiceHolding && voiceCancelArmed) onVoiceHoldCancel?.invoke() else onVoiceHoldEnd?.invoke()
                        }
                        voiceHolding = false
                        voiceCancelArmed = false
                        pressed = false
                        cursorActive = false
                        onCursorMode?.invoke(false)
                        if (!guarded) currentRelease?.invoke()
                    }
                }
            }
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.then(shadow)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(cancelColors?.background ?: keyFill), glassColor = cancelColors?.background ?: keyFill),
        contentAlignment = Alignment.Center
    ) {
        val iconSize = keyIconSizeDp(maxWidth.value, maxHeight.value)
        val labelHeight = (maxHeight.value - iconSize).coerceAtLeast(1f)
        val labelSize = minOf(keyLabelSizeSp(schemaName, 10f,
            maxWidth.value, maxHeight.value, density.fontScale, settings.keyTextScale),
            labelHeight / (density.fontScale * 1.4f)).coerceAtLeast(1f)
        if (showVoiceCancel) {
            val hintSize = (maxHeight.value * 0.32f / density.fontScale).coerceIn(9f, 16f)
            Text(if (voiceCancelArmed) "松手取消" else "上滑取消", color = cancelColors?.foreground ?: textColor,
                fontSize = hintSize.sp, lineHeight = (hintSize * 1.2f).sp, maxLines = 1, softWrap = false,
                fontFamily = AppFonts.keyFontFamily, modifier = Modifier.testTag("space-voice-cancel-hint"))
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // 键太矮时方案名会被压成看不清的小点，这时只留空格图标。
            // 「功能键不显示提示字」开着时空格只留图标（GitHub #11）。
            if (!settings.hideFunctionKeyHints && schemaName.isNotBlank() && schemaName != "空格" && labelSize >= 8f) Text(
                schemaName.replace("拼音九键", "拼音"), color = textColor,
                fontSize = labelSize.sp, lineHeight = (labelSize * 1.2f).sp, maxLines = 1, softWrap = false,
                fontFamily = AppFonts.keyFontFamily)
            Icon(Icons.Default.SpaceBar, contentDescription = "空格", tint = textColor,
                modifier = Modifier.size(iconSize.dp))
        }
    }
}
