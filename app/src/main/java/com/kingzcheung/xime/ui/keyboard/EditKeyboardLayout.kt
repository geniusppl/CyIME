package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial

private data class EditorKeyShadow(
    val enabled: Boolean = true,
    val elevation: Dp = 1.dp,
    val shapeRadius: Dp = 8.dp,
)

private val LocalEditorKeyShadow = staticCompositionLocalOf { EditorKeyShadow() }

@Composable
fun EditKeyboardLayout(
    onAction: (String) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    bottomPaddingDp: Int = 0,
    showBackKey: Boolean = true,
    keyCornerRadius: Dp = 8.dp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    /** 回车键动作名（换行、发送、搜索等），与主键盘同一来源。 */
    enterKeyText: String = "回车",
) {
    KeyboardKeySpacingScope(modifier, columns = 5f, rows = 3f, horizontalInset = 4.dp, verticalInset = bottomPaddingDp.dp) { bodyModifier ->
    var selecting by remember { mutableStateOf(false) }
    val latestAction by rememberUpdatedState(onAction)
    // 离开面板只清本地锚点，复制等操作后仍可保留宿主中的选区。
    DisposableEffect(Unit) { onDispose { latestAction("select_reset") } }
    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyCornerRadius,
        LocalEditorKeyShadow provides EditorKeyShadow(shadowEnabled, shadowElevation, shadowShapeRadius),
    ) {
        Column(bodyModifier.fillMaxSize()
            .keyboardPanelBackground(backgroundColor)
            .padding(horizontal = 2.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val cellWidth = maxWidth / 5
                val cellHeight = maxHeight / 3
                // Fill the space between the outer action columns, or the panel height.
                // In near-square panels the corner labels can become the tighter limit.
                val availableDiameter = minOf(cellWidth * 3, maxHeight)
                fun metricsFor(diameter: Dp) = keyVisualMetrics(
                    policy = KeyVisualPolicy.Qwerty.copy(maxKeyWidth = Float.MAX_VALUE, minGutter = 0f),
                    availableWidthDp = cellWidth.value * 5f,
                    availableHeightDp = diameter.value,
                    columns = 5f,
                    rows = 3f,
                    verticalInsetDp = 0f,
                ).let { metrics ->
                    // 键缝有固定上限，平板格子大了就显得窄；按格子短边保持手机上的缝隙比例，手机仍是原值。
                    val proportional = EDITOR_GAP_FRACTION / 2f * minOf(metrics.cellWidthDp, metrics.cellHeightDp)
                    metrics.copy(insetX = metrics.insetX?.let { maxOf(it, proportional) },
                        insetY = metrics.insetY?.let { maxOf(it, proportional) })
                }
                val cornerInset = metricsFor(availableDiameter).insetX ?: 2f
                val cornerScale = KeyboardKeyMetrics.contentScale(
                    cellWidth.value * .92f, availableDiameter.value / 3 * .92f)
                val fontScale = LocalDensity.current.fontScale
                val cornerHalfWidth = maxOf(12f, 10f * fontScale) * cornerScale
                val cornerHalfHeight = (11f + 6f * fontScale) * cornerScale
                // Reserve real breathing room before fitting the disc. Corner
                // labels may move only a little, never all the way to the edge.
                val cornerMargin = maxOf(8f, cornerInset + 2f)
                val shiftX = (cellWidth.value / 2 - cornerHalfWidth - cornerMargin)
                    .coerceIn(0f, cellWidth.value * .2f)
                val shiftY = (cellHeight.value / 2 - cornerHalfHeight - cornerMargin)
                    .coerceIn(0f, cellHeight.value * .15f)
                val clearX = (cellWidth.value + shiftX - cornerHalfWidth).coerceAtLeast(0f)
                val clearY = (cellHeight.value + shiftY - cornerHalfHeight).coerceAtLeast(0f)
                val cornerSafeDiameter = (2 * (hypot(clearX, clearY) - cornerInset)).coerceAtLeast(0f).dp
                val padDiameter = minOf(availableDiameter, cornerSafeDiameter)
                // 编辑盘自成一格：按 pad 自己的格尺寸算度量（不套主体键宽上限/gutter）
                val padMetrics = metricsFor(padDiameter)
                val inset = (padMetrics.insetX ?: 2f).dp
                val glyphScale = KeyboardKeyMetrics.contentScale(cellWidth.value * .92f, padDiameter.value / 3 * .92f)
                val padColor = frostedKeyColor(keyBgColor, textColor,
                    LocalKeyboardInputPreferences.current.frostedGlass)
                @Composable
                fun key(command: String, keyModifier: Modifier, cutout: Shape? = null,
                    offsetX: Dp = 0.dp, offsetY: Dp = 0.dp, compact: Boolean = false) {
                    val (icon, label) = when (command) {
                        "undo" -> Icons.AutoMirrored.Filled.Undo to "撤销"
                        "redo" -> Icons.AutoMirrored.Filled.Redo to "重做"
                        "cut" -> Icons.Default.ContentCut to "剪切"
                        "copy" -> Icons.Default.ContentCopy to "复制"
                        "paste" -> Icons.Default.ContentPaste to "粘贴"
                        "line_start" -> Icons.Default.KeyboardDoubleArrowLeft to "行首"
                        "line_end" -> Icons.Default.KeyboardDoubleArrowRight to "行尾"
                        "home" -> Icons.Default.FirstPage to "段首"
                        "end" -> Icons.Default.LastPage to "段尾"
                        "up" -> Icons.Default.KeyboardArrowUp to "向上"
                        "down" -> Icons.Default.KeyboardArrowDown to "向下"
                        "left" -> Icons.Default.KeyboardArrowLeft to "向左"
                        "right" -> Icons.Default.KeyboardArrowRight to "向右"
                        "delete" -> Icons.AutoMirrored.Filled.Backspace to "删除"
                        "enter" -> actionKeyIcon(enterKeyText) to enterKeyText
                        "select_all" -> Icons.Default.SelectAll to "全选"
                        else -> Icons.Default.TextFields to if (selecting) "取消选择" else "选择"
                    }
                    val arrow = command in listOf("up", "down", "left", "right")
                    val colors = when (command) {
                        "enter" -> LocalEnterKeyColors.current
                        "delete" -> LocalFunctionKeyColors.current
                        else -> null
                    }
                    EditorActionKey(icon, label, {
                        when {
                            command == "select" -> {
                                selecting = !selecting
                                onAction(if (selecting) "select_begin" else "select_end")
                            }
                            arrow -> onAction((if (selecting) "select_" else "") + "arrow_$command")
                            selecting && command == "home" -> onAction("select_paragraph_start")
                            selecting && command == "end" -> onAction("select_paragraph_end")
                            selecting && command in listOf("line_start", "line_end") -> onAction("select_$command")
                            else -> onAction(command)
                        }
                    }, when {
                        command == "select" && selecting -> accentColor
                        arrow -> Color.Transparent
                        else -> colors?.background ?: keyBgColor
                    }, colors?.foreground ?: textColor, keyModifier,
                        repeatable = arrow || command in listOf("delete", "enter"),
                        plain = arrow, visualShape = cutout, compact = compact,
                        glowSize = if (arrow) androidx.compose.ui.unit.DpSize(padDiameter / 3, padDiameter / 3) else null,
                        contentOffsetX = offsetX, contentOffsetY = offsetY,
                        onPastePlain = if (command == "paste") ({ onAction("paste_plain") }) else null)
                }
                CompositionLocalProvider(LocalKeyboardKeyVisualMetrics provides padMetrics,
                    LocalKeyboardKeyContentScale provides glyphScale) {
                    // 行首/行尾在圆盘左右两侧，只在圆盘两边留得出位置时出现（平板、横屏）；手机按原设计不放。
                    val sideWidth = 1.5f * cellWidth.value - padDiameter.value / 2 - 2 * inset.value
                    // 放得下当前倍率的图标和两个字（字宽随系统字号），左右再各留角键同样的边距才出现。
                    val sideScale = minOf(glyphScale,
                        ((cellHeight.value - 2 * inset.value - 12f) / (20f + 14f * fontScale)).coerceAtLeast(.25f))
                    val sideNeeded = maxOf(20f, 20f * fontScale) * sideScale + 2 * maxOf(8f, inset.value + 2f)
                    val rows = listOf(
                        listOf("undo", "home", "", "end", "delete"),
                        listOf("redo", "line_start", "", "line_end", "select_all"),
                        listOf("cut", "copy", "", "paste", "enter"))
                    Column(Modifier.fillMaxSize().testTag("editor-grid")) {
                        rows.forEachIndexed { rowIndex, row ->
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                row.forEachIndexed { columnIndex, command ->
                                    val cellModifier = Modifier.weight(1f).fillMaxHeight()
                                    if (command.isEmpty()) Spacer(cellModifier)
                                    else if (columnIndex == 0 || columnIndex == 4) key(command, cellModifier)
                                    else if (rowIndex == 1 && sideWidth < sideNeeded) Spacer(cellModifier)
                                    else {
                                        val circleX = (2.5f - columnIndex) * cellWidth.value
                                        val circleY = (1.5f - rowIndex) * cellHeight.value
                                        val contentScale = glyphScale
                                        // 两侧键的内容居中在圆盘外露出的那一段里。
                                        val offset = if (rowIndex == 1) Offset((if (columnIndex == 1) -1f else 1f) *
                                            (cellWidth.value / 2 - inset.value - sideWidth / 2), 0f)
                                        else editorCornerContentOffset(cellWidth.value, cellHeight.value,
                                            circleX, circleY, padDiameter.value / 2 + inset.value,
                                            maxOf(12f, 10f * fontScale) * contentScale,
                                            (11f + 6f * fontScale) * contentScale, maxOf(8f, inset.value + 2f))
                                        key(command, cellModifier,
                                            EditorPadCutout(circleX.dp - inset, circleY.dp - inset, padDiameter / 2 + inset),
                                            offset.x.dp, offset.y.dp, compact = true)
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.Center).size(padDiameter).padding(inset)
                        .testTag("editor-direction-pad").clip(CircleShape).background(padColor)
                        .visualMaterial(VisualStyles.current, padDiameter / 2 - inset))
                    Box(Modifier.align(Alignment.Center).size(padDiameter).testTag("editor-pad-controls")) {
                        val labelDistance = padDiameter / 3
                        listOf("up", "right", "down", "left").forEach { direction ->
                            val sector = EditorDirectionSector(direction, inset)
                            key(direction, Modifier.fillMaxSize().clip(sector),
                                cutout = sector,
                                offsetX = when (direction) { "left" -> -labelDistance; "right" -> labelDistance; else -> 0.dp },
                                offsetY = when (direction) { "up" -> -labelDistance; "down" -> labelDistance; else -> 0.dp })
                        }
                        key("select", Modifier.align(Alignment.Center).size(padDiameter / 3))
                    }
                }
            }

            Spacer(Modifier.height(bottomPaddingDp.dp))
        }
    }
    }
}

/** 手机上键缝约为格子短边的 15%；更大的格子按这个比例放宽。 */
private const val EDITOR_GAP_FRACTION = 0.15f

/** Keep labels centred in the usable part of a corner, clear of the circle and outer edge. */
internal fun editorCornerContentOffset(width: Float, height: Float, circleX: Float, circleY: Float,
    radius: Float, halfWidth: Float, halfHeight: Float, margin: Float): Offset {
    val start = Offset(width / 2, height / 2)
    val shiftX = (width / 2 - halfWidth - margin).coerceIn(0f, width * .2f)
    val shiftY = (height / 2 - halfHeight - margin).coerceIn(0f, height * .15f)
    val target = start + Offset(
        if (circleX > width / 2) -shiftX else shiftX,
        if (circleY > height / 2) -shiftY else shiftY)
    fun fits(point: Offset): Boolean {
        val dx = (kotlin.math.abs(point.x - circleX) - halfWidth).coerceAtLeast(0f)
        val dy = (kotlin.math.abs(point.y - circleY) - halfHeight).coerceAtLeast(0f)
        return dx * dx + dy * dy >= radius * radius
    }
    if (fits(start)) return Offset.Zero
    var low = 0f; var high = 1f
    repeat(16) {
        val mid = (low + high) / 2
        if (fits(start + (target - start) * mid)) high = mid else low = mid
    }
    return (target - start) * high
}

/** A 90-degree direction sector, excluding the centre selection key's square. */
internal data class EditorDirectionSector(val direction: String, val inset: Dp = 0.dp) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density): Outline {
        val diameter = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        val radius = (diameter / 2 - with(density) { inset.toPx() }).coerceAtLeast(0f)
        val start = when (direction) { "up" -> 225f; "right" -> 315f; "down" -> 45f; else -> 135f }
        val wedge = Path().apply {
            moveTo(center.x, center.y)
            arcTo(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), start, 90f, false)
            close()
        }
        val halfCenter = diameter / 6
        val hole = Path().apply { addRect(Rect(center.x - halfCenter, center.y - halfCenter,
            center.x + halfCenter, center.y + halfCenter)) }
        return Outline.Generic(Path.combine(PathOperation.Difference, wedge, hole))
    }
}

private data class EditorPadCutout(val x: Dp, val y: Dp, val radius: Dp) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density): Outline = with(density) {
        val cell = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
        val pad = Path().apply { addOval(Rect(x.toPx() - radius.toPx(), y.toPx() - radius.toPx(),
            x.toPx() + radius.toPx(), y.toPx() + radius.toPx())) }
        Outline.Generic(Path.combine(PathOperation.Difference, cell, pad))
    }
}

/** 松手/移出/面板销毁都会取消重复任务，长按结束不再补一次点击。 */
@Composable
internal fun EditorActionKey(
    icon: ImageVector,
    label: String,
    onAction: () -> Unit,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    repeatable: Boolean = false,
    plain: Boolean = false,
    visualShape: Shape? = null,
    compact: Boolean = false,
    glowSize: androidx.compose.ui.unit.DpSize? = null,
    contentOffsetX: Dp = 0.dp,
    contentOffsetY: Dp = 0.dp,
    onPastePlain: (() -> Unit)? = null,
) {
    val action by rememberUpdatedState(onAction)
    val plainPaste by rememberUpdatedState(onPastePlain)
    val hasPlainPaste = onPastePlain != null
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val shadow = LocalEditorKeyShadow.current
    val density = LocalDensity.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val shadowModifier = remember(shadow, density, background, plain, frostedGlass.enabled) {
        if (shadow.enabled && !plain && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadow.elevation.toPx() }
            val cornerPx = with(density) { shadow.shapeRadius.toPx() }
            val color = crispShadowColor(background)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx),
                )
            }
        } else Modifier
    }
    BoxWithConstraints(modifier.fillMaxSize()
        // 独立合并每个按键，避免被面板的点击屏障合并成一个无障碍节点。
        .semantics(mergeDescendants = true) {
            contentDescription = label; role = Role.Button; onClick { action(); true }
            if (hasPlainPaste) onLongClick("粘贴纯文本") { plainPaste?.invoke(); true }
        }
        .pointerInput(repeatable, hasPlainPaste) {
            detectTapGestures(
                onLongPress = if (hasPlainPaste) ({ plainPaste?.invoke() }) else null,
                onTap = if (hasPlainPaste) ({ action() }) else null,
                onPress = {
                pressed = true
                var repeated = false
                val timer = if (repeatable) scope.launch {
                    delay(300L)
                    repeated = true
                    while (true) { action(); delay(70L) }
                } else null
                try {
                    val released = tryAwaitRelease()
                    timer?.cancel()
                    if (released && !repeated && !hasPlainPaste) action()
                } finally {
                    timer?.cancel()
                    pressed = false
                }
            })
        }
        .padding(if (plain) PaddingValues(0.dp) else scaledKeyVisualPadding(PaddingValues(2.dp)))
        .keyGlow(Modifier.then(if (visualShape != null) Modifier.clip(visualShape) else Modifier)
        .then(shadowModifier)
        .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
        .background(frostedKeyColor(
            if (plain && pressed) foreground.copy(alpha = 0.18f) else background, foreground, frostedGlass,
            legacyStateColor = if (pressed && !LocalKeyboardInputPreferences.current.keyGlowEnabled) foreground.copy(alpha = 0.18f) else background,
            pressed = pressed,
            opacityScale = if (!plain) 1f else if (pressed) 0.18f else 0f,
        )),
            glassColor = background,
            applyMaterial = !plain, // The shared disc already paints the direction sectors' material.
            particleSize = glowSize,
            particleOffset = if (plain) androidx.compose.ui.unit.DpOffset(contentOffsetX, contentOffsetY) else androidx.compose.ui.unit.DpOffset.Zero), contentAlignment = Alignment.Center) {
        val scale = (LocalKeyboardKeyContentScale.current ?: 1f).let {
            // 角键和外列键同倍率放大（平板上不再比外列小一号），只在格子矮时按高度收小。
            if (compact) minOf(it,
                ((maxHeight.value - 12f) / (20f + 14f * density.fontScale)).coerceAtLeast(.25f)) else it
        }
        val iconSize = (if (compact) 20.dp else 24.dp) * scale
        val labelHeight = with(density) { (14f * scale).sp.toDp() }
        // Direction arrows already describe the action; stacked text can cross
        // the sector's curved edge after the disc is reduced for corner spacing.
        val showLabel = !plain && maxHeight >= iconSize + labelHeight
        val fittedIconSize = iconSize.coerceAtMost(maxHeight)
        val shapeSize = androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val measuredShape = visualShape?.let { RenderedControlShape(it.createOutline(shapeSize,
            androidx.compose.ui.unit.LayoutDirection.Ltr, density), shapeSize) }
        Column(Modifier.offset(contentOffsetX, contentOffsetY).testTag("editor-label-$label")
            .semantics { if (measuredShape != null) this[ControlShape] = measuredShape },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = foreground,
                modifier = Modifier.size(fittedIconSize))
            if (showLabel) Text(label, color = foreground, fontSize = (10f * scale).sp, maxLines = 1,
                lineHeight = (12f * scale).sp)
        }
    }
}
