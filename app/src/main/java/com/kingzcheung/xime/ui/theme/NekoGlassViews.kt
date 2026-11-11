package com.kingzcheung.xime.ui.theme

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * 把 Nekogram 顶部文件夹标签栏的玻璃（NekoGlass.kt）用到键盘上：
 * - 每个键（含功能键）静止时是一片标签栏同款静止玻璃：取样键盘背景（按背景不透明度），底色按 .85 从键盘底色反解出
 *   这个键自己的颜色、封顶 0x73，上下 0.4dp 描边、2.667dp 阴影；
 * - 按下不画选中底色，直接长出 FlClash 透镜（抬起、四周各长 8dp、可超出键帽，画到邻键上面）；
 *   拖过触摸阈值后按 rubberBand(手指 − 键中心, 节距 × .35) 跟手，松手 700ms 内用 SETTLE 弹簧回中；
 *   透镜第二遍是这个键自己的字/图标，放大 1 + .16 × lift、染成强调色；
 * - 「果冻特效」关（learn-36）：静止玻璃照旧；按下不长透镜、不抬起、不跟手，只在键面叠一层淡色（和 learn-30 以前一样轻）。
 */

/** 按键玻璃。[glassColor] 是键原来的底色（未指定时用键盘底色）。 */
internal fun Modifier.nekoGlassKey(radius: Dp, glassColor: Color): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val keyPalette = LocalKeyboardPalette.current
    val surface = keyPalette?.surface ?: palette.surface
    val accent = keyPalette?.accent ?: palette.accent
    val dark = remember(surface) { nekoIsDark(surface) }
    val jelly = rememberNekoJelly()
    val source = rememberLensBackdropSource(dark, surface)
    val frosted = com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.frostedGlass
    val opacity = frosted.backgroundOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val target = if (glassColor.isSpecified) glassColor else surface
    val tint = remember(surface, target) { nekoGlassTint(surface, target) }
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    // 性能（learn-33，用户 11-11 真机：learn-32「还是太卡了」，果冻关了也卡）：
    // - 静止玻璃整块键区只录一张纹理（NekoRestSheet），每个键只贴自己那一格，不跑着色器、不再一键一张离屏图层；
    // - 绘制里不读动态背景的钟和位置状态（以前开着动态背景时每秒 20 次全部键重画、重录）；
    // - 抬起时画到邻键上面（zIndex）只在摆放阶段读，按下/松开不再让这个键重组。
    val graphics = LocalGraphicsContext.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // learn-36：果冻特效关时按下完全不走透镜（不建离屏图层、不跑透镜着色器、不跟手），只画淡色按下底。
    val jellyOn = LiquidGlass.jelly(context)
    val plainPressed = remember { mutableStateOf(false) }
    // learn-35：共用纹理上次让进程没了（或本进程出过错）就不用，退回每键缓存。
    val useSheet = remember(context) { NekoGlassGuard.sheetAllowed(context) }
    val scoped = LocalNekoRestSheet.current
    val own = remember(graphics, scoped == null) { if (scoped == null) NekoRestSheet(graphics) else null }
    val sheet = scoped ?: own!!
    val layers = remember(graphics) { NekoKeyLayers(graphics) }
    val entry = remember { NekoRestEntry() }
    DisposableEffect(sheet, entry, useSheet) {
        if (useSheet) try { sheet.add(entry) } catch (t: Throwable) { NekoGlassGuard.sheetFailed(t) }
        onDispose { try { sheet.remove(entry) } catch (t: Throwable) { NekoGlassGuard.failed("sheet remove", t) } }
    }
    DisposableEffect(layers, own) {
        onDispose {
            layers.release()
            own?.release()
        }
    }
    SideEffect {
        try { if (entry.setStyle(radiusPx, tint, dark, opacity, source) && useSheet) sheet.markDirty() }
        catch (t: Throwable) { NekoGlassGuard.failed("sheet style", t) }
    }
    val shape = remember(radius) { RoundedCornerShape(radius) }
    this.onGloballyPositioned {
            try {
                val p = it.positionInRoot()
                // 共用纹理关掉时也照样记位置（每键缓存按它取样），变了就让键重画。
                sheet.place(entry, p.x, p.y, it.size.width.toFloat(), it.size.height.toFloat())
            } catch (t: Throwable) {
                NekoGlassGuard.failed("key position", t)
            }
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                // 只在摆放阶段读：抬起/放下只重新摆这个键，不重组。
                placeable.place(0, 0, zIndex = if (jellyOn && jelly.raised) 1f else 0f)
            }
        }
        .then(if (jellyOn) Modifier.pointerInput(jelly) {
            awaitEachGesture {
                // 只旁观，不消费：不影响点击、滑动和长按。
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val w = size.width.toFloat()
                val centre = w / 2f
                val pitch = max(48f * density, w)
                val slop = viewConfiguration.touchSlop
                jelly.setPitch(pitch)
                jelly.setTarget(centre, w, NekoJelly.MODE_TRAVEL)
                jelly.setPressed(true)
                var following = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!jelly.jellyOn) continue
                        if (!following && abs(change.position.x - down.position.x) > slop) following = true
                        if (following) {
                            val x = change.position.x
                            jelly.setTarget(centre + NekoJelly.rubberBand(x - centre, pitch * NekoJelly.OVERDRAG), w,
                                NekoJelly.MODE_FOLLOW)
                        }
                    }
                } finally {
                    if (following) jelly.endFollow()
                    jelly.setPressed(false)
                    jelly.setTarget(centre, w, NekoJelly.MODE_TRAVEL)
                }
            }
        } else Modifier.pointerInput(Unit) {
            // learn-36：果冻特效关——不长透镜、不跟手，只记按没按着（同样只旁观不消费），按着时键面叠一层淡色。
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                plainPressed.value = true
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                    }
                } finally {
                    plainPressed.value = false
                }
            }
        })
        .graphicsLayer {}
        .drawWithCache {
            val painter = layers.painter
            val growth = NEKO_LENS_GROWTH_DP * density
            onDrawWithContent {
                sheet.subscribe()
                jelly.subscribe()
                val w = size.width
                val h = size.height
                if (w < 1f || h < 1f) {
                    drawContent()
                    return@onDrawWithContent
                }
                if (jellyOn && !jelly.raised && !jelly.pressed) jelly.place(w / 2f, w)
                // learn-35：静止玻璃三级退路——共用纹理 → 每键缓存（learn-32）→ 纯底色键；任何一级抛错都不让输入法崩。
                var restDrawn = false
                if (useSheet && !NekoGlassGuard.sheetOff) {
                    try {
                        NekoGlassGuard.armSheet(context)
                        sheet.ensure(this)
                        restDrawn = sheet.drawKey(this, entry)
                    } catch (t: Throwable) {
                        NekoGlassGuard.sheetFailed(t)
                    }
                }
                if (!restDrawn && !NekoGlassGuard.restCacheOff) {
                    try {
                        val at = Offset(entry.left, entry.top)
                        val input = Snapshot.withoutReadObservation { painter.cache.input(source, at).first }
                        layers.drawRest(this, NekoRestKey(w, h, radiusPx, tint, dark, opacity, at, source),
                            shadow = { drawNekoRestShadow(w, h, radiusPx, dark, painter) },
                            glass = { drawNekoRestGlass(w, h, input, opacity, radiusPx, tint, dark, painter) })
                        restDrawn = true
                    } catch (t: Throwable) {
                        NekoGlassGuard.restCacheFailed(t)
                    }
                }
                if (!restDrawn) {
                    try { drawNekoPlainKey(w, h, radiusPx, tint, dark, painter) } catch (t: Throwable) { NekoGlassGuard.failed("plain key", t) }
                }
                if (!jellyOn) {
                    if (plainPressed.value) {
                        try { drawNekoPressedTint(w, h, radiusPx, dark, painter) } catch (t: Throwable) { NekoGlassGuard.failed("pressed tint", t) }
                    }
                    drawContent()
                    return@onDrawWithContent
                }
                val lift = jelly.lift.coerceIn(0f, 1f)
                if (lift <= 0.004f) {
                    drawContent()
                    return@onDrawWithContent
                }
                var contentDrawn = false
                try {
                    val input = Snapshot.withoutReadObservation { painter.cache.input(source, Offset(entry.left, entry.top)).first }
                    val lens = jelly.rect(h / 2f, h, growth)
                    val lensRadius = min(radiusPx + growth * lift, min(lens.width, lens.height) / 2f)
                    val lensPath = painter.lensPath(lens, lensRadius)
                    // 透镜外照常画字（跟手时透镜可能离开键帽一部分）。
                    clipPath(lensPath, clipOp = ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                    contentDrawn = true
                    drawNekoLensShadow(lens, lift, dark, painter)
                    val glass = !NekoGlassGuard.lensOff &&
                        drawNekoLensGlass(layers.lensGlass(), lens, lensRadius, lift, dark, 1f, painter) { region ->
                            drawNekoBackdrop(input, opacity, region, painter)
                            // 透镜底片里的静止玻璃直接贴共用纹理，不再现跑一遍 KunBox 着色器。
                            val pasted = useSheet && !NekoGlassGuard.sheetOff &&
                                try { sheet.drawKey(this, entry, keyOnly = true) } catch (t: Throwable) { NekoGlassGuard.sheetFailed(t); false }
                            if (!pasted) drawNekoRestGlass(w, h, input, opacity, radiusPx, tint, dark, painter)
                        }
                    if (!glass) drawNekoPill(lens, lensRadius, accent, lift, 1f, dark, painter)
                    val cx = w / 2f
                    val cy = h / 2f
                    val content: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit = { magnify ->
                        scale(magnify, magnify, Offset(cx, cy)) { this@onDrawWithContent.drawContent() }
                    }
                    if (!glass || !drawNekoLensContent(layers.lensContent(), lens, lensRadius, lift, dark, accent, 1f, painter, content)) {
                        drawNekoLensContentFallback(lens, lensRadius, lift, accent, 1f, content)
                    }
                } catch (t: Throwable) {
                    NekoGlassGuard.lensFailed(t)
                    if (!contentDrawn) drawContent()
                }
            }
        }
        .clip(shape)
}

// ---------------------------------------------------------------- 工具栏滑块

/**
 * 工具栏滑块 = FilterTabsView / MainTabsLayout 的果冻透镜：静止停在 [restIndex] 那格（NekoGlass.drawPill），
 * 按下 TRAVEL 到按下那格并抬起；横向拖过阈值后 FOLLOW 跟手（首尾格中心之外按 rubberBand(·, 节距 × .35)），
 * 松手 700ms 内 SETTLE。接口和原来的 ToolbarLensMotion 一样，工具栏的停靠 / lastTouched 逻辑不变。
 */
@Stable
internal class NekoToolbarLens(private val density: Float) {
    val jelly = NekoJelly(density)
    var restIndex: () -> Int? = { null }
    var count = 0
    private var pitch = 0f
    private var pillWidth = 0f
    private var pressedIndex: Int? = null
    private var downIndex = 0
    private var pressX = 0f
    private var dragging = false
    private var down = false
    private var pendingSync = false
    private var pendingInitial = false

    val dragged: Boolean get() = dragging
    private val last: Int get() = max(0, count - 1)

    private fun centre(index: Int) = (index + 0.5f) * pitch
    private fun nearest(x: Float): Int = floor(x / max(pitch, 1f)).toInt().coerceIn(0, last)

    /** 每次组合由工具栏告诉格宽和胶囊宽。 */
    fun layout(pitch: Float, pillWidth: Float) {
        if (pitch <= 0f) return
        val changed = this.pitch != pitch || this.pillWidth != pillWidth
        this.pitch = pitch
        this.pillWidth = pillWidth
        jelly.setPitch(max(48f * density, pitch))
        if (pendingSync) applyRest(pendingInitial)
        else if (changed && !down) applyRest(initial = true)
    }

    /** 开启的按钮或顺序变了：空闲时滑过去；第一次直接摆好。 */
    fun syncRest(initial: Boolean) {
        if (pitch <= 0f) {
            pendingSync = true
            pendingInitial = pendingInitial || initial
            return
        }
        applyRest(initial)
    }

    private fun applyRest(initial: Boolean) {
        pendingSync = false
        pendingInitial = false
        if (down || dragging) return
        val rest = restIndex()?.takeIf { count > 0 && it in 0..last }
        if (rest == null) {
            jelly.setShow(0f)
            return
        }
        if (initial || !jelly.hasValue || jelly.show < 0.05f) {
            jelly.jumpTo(centre(rest), pillWidth)
            if (initial) jelly.jumpShow(1f) else jelly.setShow(1f)
            return
        }
        jelly.setTarget(centre(rest), pillWidth, NekoJelly.MODE_TRAVEL)
        jelly.setShow(1f)
    }

    fun press(at: Offset, pitch: Float) {
        if (count <= 0 || pitch <= 0f) return
        layout(pitch, pillWidth)
        down = true
        dragging = false
        val index = nearest(at.x)
        pressedIndex = index
        downIndex = index
        pressX = at.x
        if (!jelly.hasValue || jelly.show < 0.05f) jelly.jumpTo(centre(index), pillWidth)
        jelly.setShow(1f)
        jelly.setPressed(true)
        jelly.setTarget(centre(index), pillWidth, NekoJelly.MODE_TRAVEL)
    }

    fun slide(at: Offset, pitch: Float) {
        if (pressedIndex == null || pitch <= 0f || !jelly.jellyOn) return
        if (!dragging) {
            if (abs(at.x - pressX) < 8f * density) return
            dragging = true
        }
        val lo = centre(0)
        val hi = centre(last)
        val p = max(48f * density, pitch)
        val x = at.x
        val cx = when {
            x < lo -> lo + NekoJelly.rubberBand(x - lo, p * NekoJelly.OVERDRAG)
            x > hi -> hi + NekoJelly.rubberBand(x - hi, p * NekoJelly.OVERDRAG)
            else -> x
        }
        jelly.setTarget(cx, pillWidth, NekoJelly.MODE_FOLLOW)
        pressedIndex = nearest(cx.coerceIn(lo, hi))
    }

    /** 松手：[commit]（拖到别的格松手）落在那格并返回它；轻点落在按下那格；拖了但不算数回停靠位。 */
    @Suppress("UNUSED_PARAMETER")
    fun release(commit: Boolean, velocityX: Float, pitch: Float): Int? {
        down = false
        val pressed = pressedIndex
        val wasDragging = dragging
        pressedIndex = null
        dragging = false
        jelly.setPressed(false)
        if (pressed == null) return null
        if (wasDragging) jelly.endFollow()
        val target = when {
            commit || !wasDragging -> pressed
            else -> restIndex()?.takeIf { it in 0..last } ?: downIndex
        }
        jelly.setTarget(centre(target), pillWidth, NekoJelly.MODE_TRAVEL)
        return if (commit) pressed else null
    }

    /** 放弃这次按压（长按换位开始）：放下并淡出。 */
    fun cancel() {
        pressedIndex = null
        dragging = false
        down = false
        jelly.setPressed(false)
        jelly.setShow(0f)
    }

    /** 透镜（_lensRect）：竖直居中在 [centerY]，比 [maxHeight] 高时压回（FilterTabsView）。[dx] 换到格自己的坐标。 */
    fun rect(centerY: Float, maxHeight: Float, pillHeight: Float, dx: Float = 0f): Rect {
        val r = jelly.rect(centerY, pillHeight, NEKO_LENS_GROWTH_DP * density, dx)
        if (maxHeight > 0f && r.height > maxHeight) {
            val inset = (r.height - maxHeight) / 2f
            return Rect(r.left, r.top + inset, r.right, r.bottom - inset)
        }
        return r
    }
}

@Composable
internal fun rememberNekoToolbarLens(): NekoToolbarLens {
    val density = LocalDensity.current.density
    val lens = remember(density) { NekoToolbarLens(density) }
    lens.jelly.jellyOn = LiquidGlass.jelly(androidx.compose.ui.platform.LocalContext.current)
    androidx.compose.runtime.LaunchedEffect(lens) { lens.jelly.run() }
    return lens
}

/**
 * 工具栏透镜本体：铺满工具栏内容（随横向滚动一起走）。静止 = drawPill（1 − lift 淡出）；
 * 抬起 = _lensShadows + FlClash 透镜玻璃（底片是键盘背景）。
 */
@Composable
internal fun NekoToolbarGlass(lens: NekoToolbarLens, pillHeight: Float, accent: Color, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val surface = LocalKeyboardPalette.current?.surface ?: palette.surface
    val dark = nekoIsDark(surface)
    // learn-33：位置不放进状态（绘制里读状态会让每次布局都重画），画笔只建一次。
    val origin = remember { arrayOfNulls<Offset>(1) }
    val painter = remember { NekoGlassPainter() }
    val source = rememberLensBackdropSource(dark, surface)
    val opacity = com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.frostedGlass.backgroundOpacity
        .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val layer = rememberGraphicsLayer()
    Box(modifier.onGloballyPositioned { origin[0] = it.positionInRoot() }.graphicsLayer {}.drawWithCache {
        onDrawBehind {
            lens.jelly.subscribe()
            val shown = lens.jelly.show.coerceIn(0f, 1f)
            if (shown <= 0.001f || !lens.jelly.hasValue) return@onDrawBehind
            val rect = lens.rect(size.height / 2f, size.height, pillHeight)
            val r = rect.height / 2f
            val lift = lens.jelly.lift.coerceIn(0f, 1f)
            if (lift < 1f) drawNekoPill(rect, r, accent, 0f, (1f - lift) * shown, dark, painter)
            if (lift > 0.004f) {
                try {
                    drawNekoLensShadow(rect, lift * shown, dark, painter)
                    val input = Snapshot.withoutReadObservation { painter.cache.input(source, origin[0]).first }
                    val drawn = !NekoGlassGuard.lensOff && drawNekoLensGlass(layer, rect, r, lift, dark, shown, painter) { region ->
                        drawNekoBackdrop(input, opacity, region, painter)
                    }
                    if (!drawn) drawNekoPill(rect, r, accent, lift, shown * lift, dark, painter)
                } catch (t: Throwable) {
                    NekoGlassGuard.lensFailed(t)
                }
            }
        }
    })
}

/**
 * 第 [index] 格的图标（_LensTint）：透镜外照常画；抬起时透镜里按 1 + .16 × lift 以本格中心放大、染成 [tint]，
 * 套 lensContent 着色器。静止时照常画（滑块只是胶囊）。
 */
internal fun Modifier.nekoToolbarLensTint(
    lens: NekoToolbarLens, index: Int, pitch: Float, pillHeight: Float, tint: Color,
): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val dark = nekoIsDark(LocalKeyboardPalette.current?.surface ?: palette.surface)
    val layer = rememberGraphicsLayer()
    val painter = remember { NekoGlassPainter() }
    drawWithContent {
        lens.jelly.subscribe()
        val shown = lens.jelly.show.coerceIn(0f, 1f)
        val lift = lens.jelly.lift.coerceIn(0f, 1f)
        if (shown <= 0.001f || lift <= 0.004f || pitch <= 0f || !lens.jelly.hasValue) {
            drawContent()
            return@drawWithContent
        }
        val rect = lens.rect(size.height / 2f, size.height, pillHeight, dx = -index * pitch)
        val magnify = 1f + NEKO_LENS_MAGNIFY * lift
        if (!nekoLensTouches(rect, size.width, size.height, magnify)) {
            drawContent()
            return@drawWithContent
        }
        val r = rect.height / 2f
        clipPath(painter.lensPath(rect, r), clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
        val cx = size.width / 2f
        val cy = size.height / 2f
        val content: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit = { m ->
            scale(m, m, Offset(cx, cy)) { this@drawWithContent.drawContent() }
        }
        val tinted = !NekoGlassGuard.lensOff && try {
            drawNekoLensContent(layer, rect, r, lift, dark, tint, shown, painter, content)
        } catch (t: Throwable) {
            NekoGlassGuard.lensFailed(t)
            false
        }
        if (!tinted) drawNekoLensContentFallback(rect, r, lift, tint, shown, content)
    }
}
