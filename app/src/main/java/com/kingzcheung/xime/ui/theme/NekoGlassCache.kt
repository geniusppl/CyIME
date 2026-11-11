package com.kingzcheung.xime.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * learn-33 性能（用户 11-11 真机安卓 17：learn-32「还是太卡了」）。
 *
 * learn-32 每个键自己一张离屏图层缓存静止玻璃，缓存键里有动态背景的当前缩放/位移——开着动态背景时
 * 每秒 20 次所有键一起重录、一起跑 KunBox 着色器；键的绘制里还读了动态背景的钟和自己的位置状态，
 * 键盘任何一动几十个键都跟着重画。
 *
 * 这里改成一张键盘一份：[NekoRestSheet] 把同一块键区（KeyboardKeySpacingScope）里所有键的静止玻璃
 * （阴影 + KunBox 光学 + 描边）一次画进同一张离屏图层（GPU 纹理），只有键的位置/尺寸/颜色/主题变了才重画；
 * 动态背景的取样在重画那一刻冻结，不再跟着钟重画。每个键静止时只把这张纹理里属于自己的那一格贴出来，
 * 不跑任何着色器；各格按相邻键之间的缝对半分，阴影只贴一次，不会叠深。
 */

/** 一个键在共用静止玻璃里的登记：位置和尺寸来自布局，样式来自组合；[rec*] 是最近一次录进纹理时的样子。 */
internal class NekoRestEntry {
    var left = 0f
    var top = 0f
    var w = 0f
    var h = 0f
    var radius = 0f
    var tint = 0
    var dark = false
    var opacity = 1f
    var source: LensBackdropSource? = null

    var recorded = false
    var recLeft = 0f
    var recTop = 0f
    var recW = 0f
    var recH = 0f
    /** 这个键负责贴的那一格（本地坐标，含分到的阴影缝）。 */
    var cell = Rect.Zero

    /** 录进纹理时这个键的底色。 */
    var recTint = 0

    /**
     * 返回 true：整张共用纹理需要重录。
     * learn-36：只有底色变了（空格、退格、回车等功能键按下/拂动时底色会变）不再让整块键区重录、所有键重画——
     * 那会在每次按功能键的按下和松开各把几十个键的 KunBox 着色器重跑一遍；这个键自己走每键缓存画新底色（[NekoRestSheet.drawKey] 返回 false）。
     */
    fun setStyle(radius: Float, tint: Int, dark: Boolean, opacity: Float, source: LensBackdropSource): Boolean {
        val structural = this.radius != radius || this.dark != dark || this.opacity != opacity || this.source !== source
        val tintChanged = this.tint != tint
        if (!structural && !tintChanged) return false
        this.radius = radius
        this.tint = tint
        this.dark = dark
        this.opacity = opacity
        this.source = source
        return structural || !recorded
    }

    val drawable: Boolean get() = w >= 1f && h >= 1f && source != null
}

/**
 * 一块键区共用的静止玻璃纹理。只在主线程用。
 * 键在绘制里先 [subscribe]（登记变了才会让各键重画一次），再 [ensure]（脏了才重录，整块键区只录一次），
 * 然后 [drawKey] 贴自己那一格。
 */
@Stable
internal class NekoRestSheet(private val graphics: GraphicsContext) {
    private val entries = ArrayList<NekoRestEntry>()
    private val version = mutableIntStateOf(0)
    private var dirty = true
    private var layer: GraphicsLayer? = null
    private var originX = 0f
    private var originY = 0f
    private val painter = NekoGlassPainter()
    private var released = false

    fun subscribe(): Int = version.intValue

    fun markDirty() {
        if (!dirty) {
            dirty = true
            version.intValue = version.intValue + 1
        }
    }

    fun add(entry: NekoRestEntry) {
        if (entries.none { it === entry }) {
            entries.add(entry)
            markDirty()
        }
    }

    fun remove(entry: NekoRestEntry) {
        if (entries.removeAll { it === entry }) {
            entry.recorded = false
            markDirty()
        }
    }

    /** 布局回调里调：位置或尺寸真变了才标脏。 */
    fun place(entry: NekoRestEntry, left: Float, top: Float, w: Float, h: Float) {
        if (entry.left == left && entry.top == top && entry.w == w && entry.h == h) return
        entry.left = left
        entry.top = top
        entry.w = w
        entry.h = h
        // 一定让键重画（共用纹理关掉时 dirty 一直是 true，markDirty 不会再动版本号）。
        dirty = true
        version.intValue = version.intValue + 1
    }

    /** 脏了就把所有键的静止玻璃重录进一张图层（坐标是 Compose 根坐标减去 [originX]/[originY]）。 */
    fun ensure(scope: DrawScope) {
        if (!dirty || released) return
        dirty = false
        val list = entries.filter { it.drawable }
        if (list.isEmpty()) return
        with(scope) {
            val margin = ceil(SHADOW_MARGIN_DP * density)
            var minL = Float.MAX_VALUE
            var minT = Float.MAX_VALUE
            var maxR = -Float.MAX_VALUE
            var maxB = -Float.MAX_VALUE
            for (e in list) {
                minL = min(minL, e.left - margin)
                minT = min(minT, e.top - margin)
                maxR = max(maxR, e.left + e.w + margin)
                maxB = max(maxB, e.top + e.h + margin)
            }
            val ox = floor(minL)
            val oy = floor(minT)
            val width = ceil(maxR - ox).toInt().coerceIn(1, MAX_SHEET_PX)
            val height = ceil(maxB - oy).toInt().coerceIn(1, MAX_SHEET_PX)
            for (e in list) e.cell = cellOf(e, list, margin)
            val target = layer ?: graphics.createGraphicsLayer().also {
                it.compositingStrategy = CompositingStrategy.Offscreen
                layer = it
            }
            originX = ox
            originY = oy
            val p = painter
            for (e in list) e.recorded = false
            target.record(size = IntSize(width, height)) {
                for (e in list) {
                    translate(e.left - ox, e.top - oy) {
                        // 冻结取样：不订阅动态背景的钟（否则每秒 20 次重画整块键区）。
                        val input = Snapshot.withoutReadObservation {
                            e.source?.let { p.cache.input(it, Offset(e.left, e.top)).first }
                        }
                        drawNekoRestShadow(e.w, e.h, e.radius, e.dark, p)
                        drawNekoRestGlass(e.w, e.h, input, e.opacity, e.radius, e.tint, e.dark, p)
                    }
                }
            }
            for (e in list) {
                e.recorded = true
                e.recTint = e.tint
                e.recLeft = e.left
                e.recTop = e.top
                e.recW = e.w
                e.recH = e.h
            }
        }
    }

    /**
     * 贴 [entry] 那一格（键本地坐标，键左上角为 (0, 0)）。[keyOnly]：只贴键帽本身（透镜底片用，不带阴影缝）。
     * 还没录进来返回 false，调用方这一帧自己画。
     */
    fun drawKey(scope: DrawScope, entry: NekoRestEntry, keyOnly: Boolean = false): Boolean {
        val l = layer ?: return false
        if (!entry.recorded || released || entry.recTint != entry.tint) return false
        val c = if (keyOnly) Rect(0f, 0f, entry.recW, entry.recH) else entry.cell
        with(scope) {
            clipRect(c.left, c.top, c.right, c.bottom) {
                translate(originX - entry.recLeft, originY - entry.recTop) { drawLayer(l) }
            }
        }
        return true
    }

    fun release() {
        released = true
        try { layer?.let { graphics.releaseGraphicsLayer(it) } } catch (t: Throwable) { NekoGlassGuard.failed("release rest sheet", t) }
        layer = null
        for (e in entries) e.recorded = false
    }

    private fun cellOf(e: NekoRestEntry, all: List<NekoRestEntry>, margin: Float): Rect {
        val r = e.left + e.w
        val b = e.top + e.h
        var left = e.left - margin
        var top = e.top - margin
        var right = r + margin
        var bottom = b + margin
        for (o in all) {
            if (o === e) continue
            val oRight = o.left + o.w
            val ob = o.top + o.h
            if (o.top < b && ob > e.top) {
                if (o.left >= r) right = min(right, (r + o.left) / 2f)
                if (oRight <= e.left) left = max(left, (e.left + oRight) / 2f)
            }
            if (o.left < r && oRight > e.left) {
                if (o.top >= b) bottom = min(bottom, (b + o.top) / 2f)
                if (ob <= e.top) top = max(top, (e.top + ob) / 2f)
            }
        }
        return Rect(left - e.left, top - e.top, right - e.left, bottom - e.top)
    }

    private companion object {
        /** 阴影（2.667dp 模糊、下移 .85dp）画到键外的余量。 */
        const val SHADOW_MARGIN_DP = 5f
        const val MAX_SHEET_PX = 4096
    }
}

/** 一块键区（KeyboardKeySpacingScope）共用的静止玻璃；没有时每个键自己一份。 */
internal val LocalNekoRestSheet = staticCompositionLocalOf<NekoRestSheet?> { null }

@Composable
internal fun rememberNekoRestSheet(): NekoRestSheet {
    val graphics = LocalGraphicsContext.current
    val sheet = remember(graphics) { NekoRestSheet(graphics) }
    DisposableEffect(sheet) { onDispose { sheet.release() } }
    // learn-36：键盘出来时在后台线程预编译玻璃/透镜着色器，第一次按键不在主线程现编。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { nekoPrewarmShaders() }
    }
    return sheet
}

/**
 * learn-35 保险（用户 11-11：learn-34 安卓 17 打开键盘直接闪退，拿不到 logcat）：
 * 玻璃绘制任何一步抛错都不许让输入法崩——出错就关掉共用纹理、退回每键一张缓存（learn-32 那条能打开的路），
 * 再不行就画纯底色键。共用纹理第一次用之前落盘记一笔，几秒内进程没了（原生层崩溃抓不到）下次启动就不再用它，
 * 换装新包（安装时间变了）才重新试。
 */
internal object NekoGlassGuard {
    private const val TAG = "CyImeNekoGlass"
    private const val PREFS = "cyime_neko_glass_guard"
    private const val KEY_PENDING = "sheet_pending"
    private const val KEY_BAD_INSTALL = "sheet_bad_install"
    private const val SETTLE_MS = 5000L

    /** 共用静止玻璃纹理是否关掉（本进程）。 */
    @Volatile var sheetOff = false
        private set
    /** 透镜（离屏 + 着色器）是否关掉（本进程）。 */
    @Volatile var lensOff = false
        private set
    /** 连每键缓存都失败：只画纯底色键。 */
    @Volatile var restCacheOff = false
        private set

    private var checked = false
    private var armed = false

    private fun installStamp(context: android.content.Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Throwable) { 0L }

    private fun prefs(context: android.content.Context): android.content.SharedPreferences? = try {
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    } catch (_: Throwable) { null }

    /** 组合时调：上次用共用纹理时进程没了（或这个安装包已判定不行），本进程就不用。 */
    fun sheetAllowed(context: android.content.Context): Boolean {
        if (sheetOff) return false
        if (!checked) {
            checked = true
            try {
                val p = prefs(context)
                if (p != null) {
                    val stamp = installStamp(context)
                    if (p.getLong(KEY_BAD_INSTALL, -1L) == stamp) {
                        sheetOff = true
                    } else if (p.getBoolean(KEY_PENDING, false)) {
                        p.edit().putLong(KEY_BAD_INSTALL, stamp).putBoolean(KEY_PENDING, false).apply()
                        sheetOff = true
                        android.util.Log.w(TAG, "Shared rest glass disabled: process died while it was in use")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Glass guard check failed", t)
            }
        }
        return !sheetOff
    }

    /** 第一次真正录共用纹理之前调：落盘记一笔，几秒后没出事再抹掉。 */
    fun armSheet(context: android.content.Context) {
        if (armed) return
        armed = true
        try {
            val p = prefs(context) ?: return
            // learn-36：apply()（内存立刻生效、后台线程落盘），不在主线程绘制里同步写盘。
            p.edit().putBoolean(KEY_PENDING, true).apply()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { p.edit().putBoolean(KEY_PENDING, false).apply() } catch (_: Throwable) {}
            }, SETTLE_MS)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Glass guard arm failed", t)
        }
    }

    fun sheetFailed(t: Throwable) {
        android.util.Log.e(TAG, "Shared rest glass failed; falling back to per-key cache", t)
        sheetOff = true
    }

    fun lensFailed(t: Throwable) {
        android.util.Log.e(TAG, "Lens glass failed; falling back to plain pill", t)
        lensOff = true
    }

    fun restCacheFailed(t: Throwable) {
        android.util.Log.e(TAG, "Per-key rest glass failed; falling back to plain keys", t)
        restCacheOff = true
    }

    fun failed(what: String, t: Throwable) {
        android.util.Log.e(TAG, "$what failed", t)
    }
}

/** 静止玻璃的缓存键（每键缓存那条退路用）：这些都不变，缓存的纹理就照用。 */
internal data class NekoRestKey(
    val w: Float, val h: Float, val radius: Float, val tint: Int, val dark: Boolean, val opacity: Float,
    val at: Offset?, val source: LensBackdropSource,
)

/** 一个键的图层：透镜两张（按下时才建）；共用纹理关掉时退回每键一张静止玻璃缓存（learn-32）。只在主线程用。 */
internal class NekoKeyLayers(private val graphics: GraphicsContext) {
    val painter = NekoGlassPainter()
    private var rest: GraphicsLayer? = null
    private var restKey: NekoRestKey? = null
    private var glass: GraphicsLayer? = null
    private var content: GraphicsLayer? = null
    private var released = false

    /** 阴影会画到键外：图层四周留这么多 dp。 */
    private val marginDp = 6f

    /** learn-32 的每键缓存：[key] 变了才重新录（[shadow] 再 [glass]，坐标是键自己的 (0, 0, w, h)），否则直接贴。 */
    fun drawRest(scope: DrawScope, key: NekoRestKey, shadow: DrawScope.() -> Unit, glass: DrawScope.() -> Unit) {
        with(scope) {
            if (released) {
                shadow()
                glass()
                return
            }
            val m = ceil(marginDp * density)
            val layer = rest ?: graphics.createGraphicsLayer().also {
                it.compositingStrategy = CompositingStrategy.Offscreen
                rest = it
            }
            if (restKey != key) {
                restKey = null
                val size = IntSize(max(1, ceil(key.w + 2f * m).toInt()), max(1, ceil(key.h + 2f * m).toInt()))
                layer.record(size = size) {
                    translate(m, m) {
                        shadow()
                        glass()
                    }
                }
                restKey = key
            }
            translate(-m, -m) { drawLayer(layer) }
        }
    }

    fun lensGlass(): GraphicsLayer = glass ?: graphics.createGraphicsLayer().also { glass = it }

    fun lensContent(): GraphicsLayer = content ?: graphics.createGraphicsLayer().also { content = it }

    fun release() {
        released = true
        try {
            rest?.let { graphics.releaseGraphicsLayer(it) }
            glass?.let { graphics.releaseGraphicsLayer(it) }
            content?.let { graphics.releaseGraphicsLayer(it) }
        } catch (t: Throwable) {
            NekoGlassGuard.failed("release key layers", t)
        }
        rest = null
        restKey = null
        glass = null
        content = null
    }
}
