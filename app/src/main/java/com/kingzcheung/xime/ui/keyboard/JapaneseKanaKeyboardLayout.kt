package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 日本手机十二键：点按あ段，向左/上/右/下选择い/う/え/お段，松手提交。 */
@Composable
fun JapaneseKanaKeyboardLayout(
    onKanaAction: (JapaneseKanaAction) -> Unit,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    specialKeyTextColor: Color = keyTextColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    bottomPaddingDp: Int = 0,
    hasKanaInput: Boolean = false,
    isFloatingMode: Boolean = false,
    numberMode: Boolean = false,
) {
    KeyboardKeySpacingScope(modifier, columns = 5f, verticalInset = bottomPaddingDp.dp, gridBottomMargin = 0.dp,
        policy = KeyVisualPolicy.T9, allowShrink = isFloatingMode, applyGutter = true,
        growthSpacing = keySpacingX?.value to keySpacingY?.value) { bodyModifier ->
    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyCornerRadius,
        LocalKeyVisualPadding provides PaddingValues(horizontal = (keySpacingX ?: 2.dp), vertical = (keySpacingY ?: 2.dp)),
    ) {
        Row(bodyModifier.fillMaxSize()
            .background(if (LocalKeyboardInputPreferences.current.frostedGlass.enabled) Color.Transparent else keyboardBackgroundColor)
            .padding(bottom = bottomPaddingDp.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (numberMode) {
                    val utilityKey = KanaFlickKey(listOf("+", "-", "*", "/", "=").map { KanaChoice(it, it) })
                    KanaFlickButton(utilityKey,
                        { if (it is JapaneseKanaAction.Input) onKeyPress(it.romaji) }, specialKeyBackgroundColor, specialKeyTextColor,
                        Modifier.weight(1f).testTag("kana-operators"),
                        onPress = { onKeyPressDown?.invoke(utilityKey.center.romaji) },
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                } else CompositionLocalProvider(LocalKeyboardPunctuation provides null) {
                    // One 無変換 hint, triggered like the nine-key delete key's 清空 (follows the corner-swipe direction setting).
                    SwipeableKeyButton(text = "変換", onClick = { onKeyPress("japanese_convert") },
                        backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f).testTag("kana-convert"),
                        swipeText = "無変換", onSwipe = { onKeyPress("japanese_undo") },
                        followSymbolSwipeDirection = true,
                        onPress = { onKeyPressDown?.invoke("japanese_convert") },
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                }
                SwipeableIconKeyButton(icon = rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowLeft),
                    onClick = { onKeyPress("japanese_left") }, onLongClick = { onKeyPressDown?.invoke("japanese_left"); onKeyPress("japanese_left") },
                    backgroundColor = specialKeyBackgroundColor, iconColor = specialKeyTextColor, modifier = Modifier.weight(1f).testTag("kana-left"),
                    onPress = { onKeyPressDown?.invoke("japanese_left") }, shadowEnabled = shadowEnabled)
                // Mode names are UI labels, not literals affected by full-/half-width input.
                CompositionLocalProvider(LocalKeyboardPunctuation provides null) {
                    KeyButton(if (numberMode) "あいう" else "123", { onKeyPress(if (numberMode) "abc" else "mode_change_number") }, specialKeyBackgroundColor, specialKeyTextColor,
                        Modifier.weight(1f).testTag("kana-number"), onPress = { onKeyPressDown?.invoke("number") }, fontSize = 16.sp, shadowEnabled = shadowEnabled)
                    KeyButton("!@#", { onKeyPress("symbol") }, specialKeyBackgroundColor, specialKeyTextColor,
                        Modifier.weight(1f).testTag("kana-symbol"), fontSize = 16.sp, onPress = { onKeyPressDown?.invoke("symbol") }, shadowEnabled = shadowEnabled)
                }
            }
            Column(Modifier.weight(3f).fillMaxHeight()) {
                for (row in 0..2) Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (column in 0..2) {
                        if (numberMode) {
                            val digit = (row * 3 + column + 1).toString()
                            KeyButton(digit, { onKeyPress(digit) }, keyBackgroundColor, keyTextColor,
                                Modifier.weight(1f).testTag("kana-digit:$digit"), onPress = { onKeyPressDown?.invoke(digit) },
                                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        } else {
                            val key = japaneseKanaKeys[row * 3 + column]
                            KanaFlickButton(key, onKanaAction, keyBackgroundColor, keyTextColor,
                                Modifier.weight(1f).testTag("kana-key:${key.center.romaji}"),
                                onPress = { onKeyPressDown?.invoke(key.center.label) },
                                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (numberMode) KeyButton(".", { onKeyPress(".") }, keyBackgroundColor, keyTextColor,
                        Modifier.weight(1f).testTag("kana-decimal"), onPress = { onKeyPressDown?.invoke(".") },
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    else if (hasKanaInput) KeyButton("小゛゜", { onKanaAction(JapaneseKanaAction.Modify) }, specialKeyBackgroundColor, specialKeyTextColor,
                        Modifier.weight(1f).testTag("kana-modifier"), onPress = { onKeyPressDown?.invoke("japanese_modify") }, fontSize = KeyboardKeyMetrics.LabelSize,
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    else KanaFlickButton(japaneseKanaKeys.last(), onKanaAction, keyBackgroundColor, keyTextColor,
                        Modifier.weight(1f).testTag("kana-punctuation"), onPress = { onKeyPressDown?.invoke("symbol") },
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    if (numberMode) KeyButton("0", { onKeyPress("0") }, keyBackgroundColor, keyTextColor,
                        Modifier.weight(1f).testTag("kana-digit:0"), onPress = { onKeyPressDown?.invoke("0") },
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    else KanaFlickButton(japaneseKanaKeys[9], onKanaAction, keyBackgroundColor, keyTextColor,
                        Modifier.weight(1f).testTag("kana-key:wa"), onPress = { onKeyPressDown?.invoke("wa") },
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    LanguageKeyButton(icon = rememberVectorPainter(Icons.Default.Language), onClick = { onKeyPress("ime_switch") },
                        backgroundColor = specialKeyBackgroundColor, iconColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f).testTag("kana-language"),
                        onPress = { onKeyPressDown?.invoke("ime_switch") }, shadowEnabled = shadowEnabled)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SwipeableIconKeyButton(icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onKeyPress("delete") }, onLongClick = { onKeyPress("delete") }, distanceDelete = true,
                    // 清空、撤回同九键删除键（11-05「部分页面的删除键功能缺失」）。
                    followSymbolSwipeDirection = true,
                    upFeedback = KeyFlickFeedback.DELETE, swipeUpLabel = "上滑清空",
                    downFeedback = KeyFlickFeedback.UNDO, swipeDownLabel = "下滑撤回",
                    onSwipeUp = { onKeyPress("clear_all") }, onSwipeDown = { onKeyPress("undo_clear") },
                    backgroundColor = specialKeyBackgroundColor, iconColor = specialKeyTextColor,
                    modifier = Modifier.weight(1f).testTag("kana-delete"), onPress = { onKeyPressDown?.invoke("delete") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                SwipeableIconKeyButton(icon = rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight),
                    onClick = { onKeyPress("japanese_right") }, onLongClick = { onKeyPressDown?.invoke("japanese_right"); onKeyPress("japanese_right") },
                    backgroundColor = specialKeyBackgroundColor, iconColor = specialKeyTextColor, modifier = Modifier.weight(1f).testTag("kana-right"),
                    onPress = { onKeyPressDown?.invoke("japanese_right") }, shadowEnabled = shadowEnabled)
                SpaceKeyButton(onClick = { onKeyPress("space") }, backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor, schemaName = "日本語", modifier = Modifier.weight(1f).testTag("kana-space"), onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                ActionKeyButton(text = "回车", onClick = { onKeyPress("enter") }, backgroundColor = specialKeyBackgroundColor,
                    textColor = specialKeyTextColor, modifier = Modifier.weight(1f).testTag("kana-enter"), onPress = { onKeyPressDown?.invoke("enter") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
            }
        }
    }
    }
}

@Composable
internal fun KanaFlickButton(
    key: KanaFlickKey,
    onAction: (JapaneseKanaAction) -> Unit,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val currentAction by rememberUpdatedState(onAction)
    val currentPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    var flickPreview by remember { mutableStateOf<KeyFlickPreview?>(null) }
    val density = LocalDensity.current
    val suppressCursorMove = LocalSuppressCursorMove.current
    val inputPreferences = LocalKeyboardInputPreferences.current
    val frostedGlass = inputPreferences.frostedGlass
    val shadow = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, background, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) Modifier.drawBehind {
            drawRoundRect(crispShadowColor(background), topLeft = Offset(0f, shadowElevation.toPx()),
                size = size, cornerRadius = CornerRadius(KeyShapes.current.radius(shadowShapeRadius).toPx()))
        } else Modifier
    }
    val textScale = inputPreferences.keyTextScale
    fun choiceAction(direction: KanaFlickDirection): (() -> Unit)? = key.choice(direction)?.let { choice ->
        { currentAction(JapaneseKanaAction.Input(choice.romaji)) }
    }
    // Same recognizer as the nine-key flick letters: shared distance setting, free re-aim, release decides.
    val threshold = normalizeLetterSwipeDistance(inputPreferences.fourteenLetterSwipeDp)
    val gestureActions = KeyGestureActions(
        text = key.center.label,
        onTap = { currentAction(JapaneseKanaAction.Input(key.center.romaji)) },
        onPress = { pressed = true; suppressCursorMove.value = true; currentPress?.invoke() },
        onRelease = { pressed = false; suppressCursorMove.value = false },
        onPreview = { state ->
            // Kana labels sit at the middle of each edge, not in the corner-hint positions.
            flickPreview = state.keyFlick?.let { preview -> when (preview.origin) {
                KeyFlickOrigin.TOP_END -> preview.copy(origin = KeyFlickOrigin.TOP)
                KeyFlickOrigin.BOTTOM_START -> preview.copy(origin = KeyFlickOrigin.BOTTOM)
                else -> preview
            } }
        },
        leftText = key.choice(KanaFlickDirection.LEFT)?.label, onLeft = choiceAction(KanaFlickDirection.LEFT),
        upText = key.choice(KanaFlickDirection.UP)?.label,
        onUp = choiceAction(KanaFlickDirection.UP)?.let { action -> { _: String -> action() } },
        rightText = key.choice(KanaFlickDirection.RIGHT)?.label, onRight = choiceAction(KanaFlickDirection.RIGHT),
        downText = key.choice(KanaFlickDirection.DOWN)?.label,
        onDown = choiceAction(KanaFlickDirection.DOWN)?.let { action -> { _: String -> action() } },
        horizontalThresholdDp = threshold,
        verticalThresholdDp = threshold,
        reserveHorizontalSwipe = true,
        freeDirection = true,
        inlineVerticalPreview = true,
        inlineHorizontalPreview = true,
    )
    val currentActions by rememberUpdatedState(gestureActions)
    BoxWithConstraints(
        modifier.fillMaxSize()
            .semantics {
                contentDescription = key.center.label
                role = Role.Button
                onClick { currentAction(JapaneseKanaAction.Input(key.center.romaji)); true }
                customActions = key.choices.drop(1).filterNotNull().map { choice ->
                    CustomAccessibilityAction(choice.label) { currentAction(JapaneseKanaAction.Input(choice.romaji)); true }
                }
            }
            .pointerInput(Unit) { detectExclusiveKeyGestures { currentActions } }
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.then(shadow)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(frostedKeyColor(
                background, foreground, frostedGlass,
                legacyStateColor = if (pressed) background.copy(alpha = 0.7f) else background,
                pressed = pressed,
                opacityScale = glassFunctionKeyScale(background),
                functionDepth = glassFunctionKeyDepth(background),
            )).keyHeldHighlight(pressed), glassColor = background),
        contentAlignment = Alignment.Center,
    ) {
        val contentScale = keyContentScale(maxWidth.value, maxHeight.value)
        val fontSp = keyLabelSizeSp(key.center.label, KeyboardKeyMetrics.LabelSize.value,
            maxWidth.value, maxHeight.value, density.fontScale, textScale)
        val disc = inputPreferences.flickDirectionDisc
        val flickFade = rememberKeyFlickFade(flickPreview.takeIf { !disc })
        val direction = flickPreview?.takeIf { it.progress >= 1f }?.let { preview ->
            when (preview.origin) {
                KeyFlickOrigin.LEFT -> KanaFlickDirection.LEFT
                KeyFlickOrigin.RIGHT -> KanaFlickDirection.RIGHT
                KeyFlickOrigin.TOP, KeyFlickOrigin.TOP_END -> KanaFlickDirection.UP
                KeyFlickOrigin.BOTTOM, KeyFlickOrigin.BOTTOM_START -> KanaFlickDirection.DOWN
            }
        } ?: KanaFlickDirection.TAP
        if (pressed && disc) KanaDirectionIndicator(direction, MaterialTheme.colorScheme.primary.copy(alpha = .6f))
        val keyWidthDp = maxWidth.value
        val keyHeightDp = maxHeight.value
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - flickFade.strongest },
            contentAlignment = Alignment.Center) {
        if (inputPreferences.kanaDirectionHints) {
            val centerSp = keyLabelSizeSp(key.center.label, KeyboardKeyMetrics.LabelSize.value,
                keyWidthDp * .5f, keyHeightDp * .52f, density.fontScale, textScale)
            val hintSp = minOf(centerSp * .46f, (keyHeightDp - 4f).coerceAtLeast(1f) * .22f / density.fontScale)
            KanaDirectionLabels(key, foreground, centerSp, hintSp)
        } else Text(punctuationKeyLabel(key.center.label), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = foreground, fontSize = fontSp.sp, lineHeight = (fontSp * 1.2f).sp,
            maxLines = 1, softWrap = false, fontFamily = AppFonts.keyFontFamily)
        }
        // Default: the chosen kana grows from its side into the key, as 26-key symbols do.
        KeyFlickLabels(flickFade, foreground, keyWidthDp, keyHeightDp, 10f * contentScale, fontSp)
        if (pressed && disc) KanaFlickPreview(key, direction, background, foreground, contentScale)
    }
}
