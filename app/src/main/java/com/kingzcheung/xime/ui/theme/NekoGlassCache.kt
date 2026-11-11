package com.kingzcheung.xime.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/*
 * learn-32 性能修复（用户 11-11 真机安卓 17：learn-31「卡顿的没法用」）。
 *
 * learn-31 每个键每次重画都现算 KunBox 静止玻璃着色器、setShadowLayer 模糊阴影和一次非矩形裁剪；
 * 键盘任何一处动（按下的透镜每帧都在动）整块键盘一起重画，几十个键一起现算。
 * 这里把每个键的静止玻璃（阴影 + 玻璃 + 描边）只录一次进一个离屏图层（GPU 缓存成纹理），
 * 键的尺寸、位置、颜色、背景不变就不再录，之后每帧只贴这张纹理；透镜用的两个图层按下时才建。
 */

/** 静止玻璃的缓存键：这些都不变，缓存的纹理就照用。 */
internal data class NekoRestKey(
    val w: Float, val h: Float, val radius: Float, val tint: Int, val dark: Boolean, val opacity: Float,
    val at: Offset?, val source: LensBackdropSource, val motion: Any?, val rootW: Int, val rootH: Int,
)

internal fun nekoRestKey(
    w: Float, h: Float, radius: Float, tint: Int, dark: Boolean, opacity: Float, at: Offset?, source: LensBackdropSource,
): NekoRestKey = NekoRestKey(w, h, radius, tint, dark, opacity, at, source,
    source.shared?.motion?.transform, source.root.width, source.root.height)

/** 一个键的图层：静止玻璃一张（离屏缓存），透镜两张（按下时才建）。只在主线程用。 */
internal class NekoKeyLayers(private val graphics: GraphicsContext) {
    val painter = NekoGlassPainter()
    private var rest: GraphicsLayer? = null
    private var restKey: NekoRestKey? = null
    private var glass: GraphicsLayer? = null
    private var content: GraphicsLayer? = null
    private var released = false

    /** 阴影会画到键外：图层四周留这么多 dp。 */
    private val marginDp = 6f

    /**
     * 画静止玻璃：[key] 变了才重新录（[shadow] 再 [glass]，坐标是键自己的 (0, 0, w, h)），否则直接贴缓存。
     */
    fun drawRest(scope: DrawScope, key: NekoRestKey, shadow: DrawScope.() -> Unit, glass: DrawScope.() -> Unit) {
        with(scope) {
            val m = ceil(marginDp * density)
            if (released) {
                shadow()
                glass()
                return
            }
            val layer = rest ?: graphics.createGraphicsLayer().also {
                it.compositingStrategy = CompositingStrategy.Offscreen
                rest = it
            }
            if (restKey != key) {
                restKey = key
                val size = IntSize(ceil(key.w + 2f * m).toInt(), ceil(key.h + 2f * m).toInt())
                layer.record(size = size) {
                    translate(m, m) {
                        shadow()
                        glass()
                    }
                }
            }
            translate(-m, -m) { drawLayer(layer) }
        }
    }

    fun lensGlass(): GraphicsLayer = glass ?: graphics.createGraphicsLayer().also { glass = it }

    fun lensContent(): GraphicsLayer = content ?: graphics.createGraphicsLayer().also { content = it }

    fun release() {
        released = true
        rest?.let { graphics.releaseGraphicsLayer(it) }
        glass?.let { graphics.releaseGraphicsLayer(it) }
        content?.let { graphics.releaseGraphicsLayer(it) }
        rest = null
        glass = null
        content = null
        restKey = null
    }
}
