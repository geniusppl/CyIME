package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.ToolbarAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.ui.theme.LiquidGlass
import com.kingzcheung.xime.ui.theme.NekoToolbarGlass
import com.kingzcheung.xime.ui.theme.nekoToolbarLensTint
import com.kingzcheung.xime.ui.theme.rememberNekoToolbarLens

/**
 * 工具栏玻璃透镜开着（玻璃 + 液态玻璃 + 浓郁样式）：按钮不再画按下底色和细亮边，开启的按钮只留淡淡的强调色底，
 * 由透镜表示按下、拖动和开启（11-11 第七版：照搬 Nekogram 顶部文件夹标签栏的果冻透镜，见 NekoGlassViews.kt）。
 */
internal val LocalToolbarLens = staticCompositionLocalOf { false }

/** 长按两秒后拖动换位，松手保存；普通点按与横向浏览沿用原行为。 */
@Composable
internal fun ReorderableToolbar(
    actions: List<ToolbarAction>, accent: Color, onReorder: ((List<String>) -> Unit)?,
    modifier: Modifier = Modifier, content: @Composable (ToolbarAction) -> Unit,
) {
    val ids = actions.map { it.item.id }
    var order by remember { mutableStateOf(ids) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val bounds = remember { mutableMapOf<String, Rect>() }
    val latestIds by rememberUpdatedState(ids)
    val save by rememberUpdatedState(onReorder)
    val scroll = rememberScrollState()
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(ids) { if (dragged == null) order = ids }
    fun moveToPointer() {
        val source = dragged ?: return
        // 按位置槽位换序，避免同一帧移动和松手用到尚未重排的旧 id 坐标而反向换回。
        val centers = order.mapNotNull { bounds[it]?.center?.x }.sorted()
        if (centers.size != order.size) return
        val from = order.indexOf(source)
        val to = centers.indices.minByOrNull { abs(centers[it] - pointer.x) } ?: return
        if (from >= 0 && to >= 0 && from != to) order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    LaunchedEffect(dragged) {
        while (dragged != null) {
            val delta = when { pointer.x < rowBounds.left + edge -> -8f; pointer.x > rowBounds.right - edge -> 8f; else -> 0f }
            if (delta != 0f) { scroll.scrollBy(delta); moveToPointer() }
            delay(30)
        }
    }
    // 玻璃透镜（11-09 用户：工具栏要 FlClash 底部浮动导航栏的玻璃和切换动效）。
    val lensOn = LiquidGlass.surfacesOn() && LiquidGlass.rich(LocalContext.current)
    val lens = rememberNekoToolbarLens()
    // FilterTabsView：透镜里的标签染成选中色（这里是工具栏的强调色）。
    val lensTint = accent
    // 停靠：最近点过的按钮开着就停它上面，否则停第一个开着的（听写按钮有自己的音量光圈，不算）；
    // 都没开着时停在最近点过的那格（11-09 第二版：像 FlClash 导航栏一样滑块一直在，不再点完就消失）。
    var lastTouched by remember { mutableStateOf<String?>(null) }
    val actives = order.filter { id -> id != "voice" && actions.firstOrNull { it.item.id == id }?.active == true }
    val restIndex = (lastTouched?.takeIf { it in actives } ?: actives.firstOrNull() ?: lastTouched?.takeIf { it != "voice" })
        ?.let { order.indexOf(it) }?.takeIf { it >= 0 }
    lens.count = order.size
    lens.restIndex = { restIndex }
    val latestOrder by rememberUpdatedState(order)
    val latestActions by rememberUpdatedState(actions)
    var lensPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(lensOn, restIndex, order) {
        if (!lensOn) return@LaunchedEffect
        lens.syncRest(initial = !lensPlaced)
        lensPlaced = true
    }
    BoxWithConstraints(modifier.testTag("toolbar-order-row").onGloballyPositioned { rowBounds = it.boundsInRoot() }
        .pointerInput(onReorder != null) {
            if (save != null) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed) return@awaitEachGesture
                var completed = false
                val timer = scope.launch {
                    delay(2000L)
                    pointer = rowBounds.topLeft + down.position
                    dragged = order.firstOrNull { bounds[it]?.contains(pointer) == true }
                    if (dragged != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (dragged != null) {
                            change.consume()
                            pointer = rowBounds.topLeft + change.position
                            moveToPointer()
                            if (!change.pressed) { completed = true; break }
                        } else if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                    if (completed && order != latestIds) save?.invoke(order)
                    else if (!completed) order = latestIds
                } finally {
                    timer.cancel()
                    dragged = null
                }
            }
        }) {
        // 每个图标占等宽一格，整格可点；放不下时按最小格宽横向滚动。
        val slotWidth = maxOf(maxWidth / order.size.coerceAtLeast(1), 36.dp)
        val density = LocalDensity.current
        val pitch = with(density) { slotWidth.toPx() }
        val pill = toolbarPillSize(slotWidth)
        val pillWidth = with(density) { pill.width.toPx() }
        val pillHeight = with(density) { pill.height.toPx() }
        val leaveMargin = with(density) { 8.dp.toPx() }
        if (lensOn) lens.layout(pitch, pillWidth)
        // 内层 Box 的作用域里不能隐式读 BoxWithConstraints 的 maxWidth，先取出来。
        val viewport = maxWidth
        // 透镜和按钮放在同一个滚动内容里，横向滚动时一起走。
        Box(Modifier.horizontalScroll(scroll).widthIn(min = maxWidth)
            .then(if (lensOn) Modifier.pointerInput(pitch) {
                awaitEachGesture {
                    // 只旁观，不消费：点按、横向滚动和长按两秒换位都照旧。
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.isConsumed) return@awaitEachGesture
                    val startScroll = scroll.value
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    lens.press(down.position, pitch)
                    val downSlot = (down.position.x / pitch).toInt()
                    var left = false
                    var cancelled = false
                    var up: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) { cancelled = true; break }
                        // 长按换位接管（上层已消费）：透镜放下淡出。
                        if (change.isConsumed) { cancelled = true; break }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) { up = change.position; break }
                        val x = change.position.x
                        if (x < downSlot * pitch - leaveMargin || x > (downSlot + 1) * pitch + leaveMargin) left = true
                        lens.slide(change.position, pitch)
                    }
                    if (cancelled || dragged != null) {
                        lens.cancel()
                        return@awaitEachGesture
                    }
                    // 拖离按下那格再松手：像导航栏一样选中松手处那格（原按钮的点按已因移出而取消，不会重复）；
                    // 内容被横向滚动过就只是滚动，不算选中。
                    val wasDragged = lens.dragged
                    val commit = left && wasDragged && scroll.value == startScroll && up != null
                    val index = lens.release(commit, tracker.calculateVelocity().x, pitch)
                    val ids = latestOrder
                    if (index != null && index in ids.indices) {
                        lastTouched = ids[index]
                        latestActions.firstOrNull { it.item.id == ids[index] }?.onClick?.invoke()
                    } else if (!wasDragged && downSlot in ids.indices) {
                        // 轻点交给按钮自己的点击；记下它，开启后透镜停在它上面。
                        lastTouched = ids[downSlot]
                    }
                }
            } else Modifier)) {
            if (lensOn) NekoToolbarGlass(lens, pillHeight, lensTint, Modifier.matchParentSize())
            CompositionLocalProvider(LocalToolbarLens provides lensOn) {
                Row(Modifier.widthIn(min = viewport),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    order.forEachIndexed { index, id ->
                        val action = actions.firstOrNull { it.item.id == id } ?: return@forEachIndexed
                        key(id) {
                            Box(Modifier.width(slotWidth).testTag("toolbar-slot")
                                .onGloballyPositioned { bounds[id] = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                                // 透镜下的图标：裁成透镜形状、随抬起放大、染成主色（prism_ui _LensTint）。
                                .then(if (lensOn) Modifier.nekoToolbarLensTint(lens, index, pitch, pillHeight, lensTint)
                                    else Modifier),
                                contentAlignment = Alignment.Center) {
                                content(action)
                                // 拖动外框沿按钮胶囊描边，与按下、开启时的底色同尺寸。
                                if (dragged == id) Box(Modifier.size(toolbarPillSize(slotWidth)).border(2.dp, accent, CircleShape)
                                    .testTag("toolbar-drag-outline"))
                            }
                        }
                    }
                }
            }
        }
    }
}
