package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.glassTextHalo

/**
 * 液态玻璃（主题与外观 › 材质效果，默认关；打开时把玻璃一起打开）。
 *
 * 保守版（用户 11-02「代价挺大，做得保守一点」）：给按键画一道细亮边，按住时边缘变亮、键面略亮。
 * 不跟手指、不做动画；高光路径按尺寸缓存，键盘不动不重画，按下只多画一帧（按键本来就会因按下重画）。
 * 输入法读不到背后应用的像素（模糊由系统在 ImeGlassBlurWindow 里做），所以透明背景下做不出弯折背后文字。
 * 背景是自己的图片（「我的图片」开玻璃时不透明）时像素在手里：Android 13 以上按键边缘折射这张图
 * （用户 11-02「自定义键盘背景的时候可以把按键做成这种玻璃效果」）。
 */
internal object LiquidGlass {
    private val on = mutableStateOf(false)
    private val rich = mutableStateOf(true)

    /** 浓郁样式（见 [SettingsPreferences.KEY_GLASS_LIQUID_RICH]）；可在组合和绘制里读。 */
    fun rich(context: Context): Boolean {
        if (listener == null) bind(context.applicationContext ?: context)
        return rich.value
    }
    // SharedPreferences 只弱引用监听器，这里必须强引用住。
    @Volatile private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 可在组合里读：设置页改了，键盘和预览立刻跟。 */
    fun enabled(context: Context): Boolean {
        if (listener == null) bind(context.applicationContext ?: context)
        return on.value
    }

    /**
     * 键盘里除按键以外的面板件（工具栏开启/按下的胶囊、剪贴板预览条、剪贴板卡片、布局/方案卡片）也画同一道细亮边。
     * 跟按键同一个条件：当前外观开着玻璃、液态玻璃开着。设置页的同款卡片没有键盘偏好（玻璃视为关），不画。
     */
    @androidx.compose.runtime.Composable
    fun surfacesOn(): Boolean =
        com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.frostedGlass.enabled &&
            enabled(androidx.compose.ui.platform.LocalContext.current)

    /** 测试换了 Application 后重新读开关并监听新的偏好。 */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun rebind(context: Context) {
        val app = context.applicationContext ?: context
        listener?.let { SettingsPreferences.getPrefsPublic(app).unregisterOnSharedPreferenceChangeListener(it) }
        listener = null
        bind(app)
    }

    @Synchronized
    private fun bind(context: Context) {
        if (listener != null) return
        val prefs = SettingsPreferences.getPrefsPublic(context)
        on.value = SettingsPreferences.glassLiquid(context)
        rich.value = SettingsPreferences.glassLiquidRich(context)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SettingsPreferences.KEY_GLASS_LIQUID) on.value = SettingsPreferences.glassLiquid(context)
            if (key == null || key == SettingsPreferences.KEY_GLASS_LIQUID_RICH) rich.value = SettingsPreferences.glassLiquidRich(context)
        }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }
}

/**
 * 替代按键上的视觉样式材质：[cap] 是按键原来的键帽（裁剪 + 底色），亮边画在底色上面、文字下面。
 */
internal fun Modifier.liquidGlassCap(cap: Modifier, radius: Dp): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val dark = palette.surface.luminance() < .5f
    val held = remember { mutableStateOf(false) }
    val observed = pointerInput(Unit) {
        awaitEachGesture {
            // 只旁观，不消费：不影响点击、滑动和长按。
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            held.value = true
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            held.value = false
        }
    }
    val rich = LiquidGlass.rich(androidx.compose.ui.platform.LocalContext.current)
    if (!rich) return@composed observed.then(liquidRefraction(radius)).then(cap).then(liquidRim(radius, dark, held))
    // iOS 样式：玻璃着色器和按压手感照 FlClash / prism_ui（GPL-3.0，chen08209）的浮动导航栏透镜移植，与工具栏透镜共用
    // （[GlassKeyPress]、[drawGlassLens]）。11-09 第二版：learn-23 走 Kyant drawBackdrop（图层 + RenderEffect），
    // 真机上按键和以前一模一样；现在着色器当画笔直接画在键帽底色上面、文字下面，底片是键盘背景本身
    // （共用背景图 / 主题渐变 / 底色），Android 12 及以下手画同样的边缘、暗线和高光。
    val press = rememberGlassKeyPress()
    val keyPalette = LocalKeyboardPalette.current
    val source = rememberLensBackdropSource(dark, keyPalette?.surface ?: palette.surface)
    val origin = remember { mutableStateOf<Offset?>(null) }
    // 11-09 第三版：透出模式（后面是系统模糊的应用）不取样主题底片，键帽本身是一层淡白（frostedKeyColor），
    // 字和图标后面垫一块很软的反色托底，后面换成深色/浅色内容时也读得清。
    val seeThrough = com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.frostedGlass.seeThrough
    val labelHalo = if (seeThrough) com.kingzcheung.xime.ui.keyboard.GlassTextHalo(
        if (dark) Color.Black else Color.White, if (dark) IOS_SEE_THROUGH_HALO_DARK else IOS_SEE_THROUGH_HALO_LIGHT) else null
    // 位置在变形之前量，按下放大、拖动时底片不跟着跑；press.modifier 的缩放包住键帽和玻璃。
    observed.onGloballyPositioned { origin.value = it.positionInRoot() }
        .then(press.modifier)
        .then(cap)
        .drawWithCache {
            val lens = LensPaintCache()
            val radiusPx = radius.toPx()
            onDrawWithContent {
                drawGlassLens(source, origin.value, radiusPx, dark, press.strength(), 1f,
                    press.glowAt, press.glowStrength(), press.glowReach(), Color.Transparent, lens, refract = !seeThrough)
                drawContent()
            }
        }
        .then(if (labelHalo != null) Modifier.glassTextHalo(labelHalo) else Modifier)
}

/** 透出模式里键面字后面的软托底浓度（浅色白晕、深色黑晕）；后面本来就同色时看不出来。 */
internal const val IOS_SEE_THROUGH_HALO_LIGHT = .55f
internal const val IOS_SEE_THROUGH_HALO_DARK = .45f

/** 在按键自己的坐标里画共用键盘背景（和 [liquidRefraction33] 同一套对位）；没有背景图时铺主题底色。 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSharedBackdropForKey(
    shared: SharedKeyboardBackdrop?, at: Offset?,
) {
    shared ?: return
    at ?: return
    val frame = shared.frame
    val image = shared.image
    val width = frame.size.width.toFloat()
    val height = frame.size.height.toFloat()
    // 纯色/渐变主题没有图可弯：铺底色，暗线、高光和指尖光才有东西可压暗、可叠。
    if (image == null || width <= 0f || height <= 0f || image.width <= 0 || image.height <= 0) {
        if (shared.fallback.alpha > 0f) drawRect(shared.fallback)
        return
    }
    val offset = frame.origin + shared.windowToRoot - at
    val motion = shared.motion?.transform ?: BackgroundMotionTransform.Identity
    val left = offset.x + width * ((1f - motion.scale) / 2f + motion.shiftX)
    val top = offset.y + height * ((1f - motion.scale) / 2f + motion.shiftY)
    drawImage(image,
        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize((width * motion.scale).toInt(), (height * motion.scale).toInt()),
        alpha = shared.opacity.coerceIn(0f, 1f),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
}

/**
 * iOS 样式（用户 11-08：彩虹边那版不好看，要 iOS 26 的液态玻璃）：
 * - 不加彩色、不加奶白：键面保持通透，只靠系统窗口模糊打底；
 * - 边缘一圈「厚度」：内侧几 dp 的明暗过渡（上沿亮、下沿略暗），像透镜边把光聚起来；
 * - 两道月牙高光：左上一道亮的、右下一道弱的回光，中间断开，不是整圈描边；
 * - 按住时键面略亮，鼓起由外面的弹簧动画做。
 * 背后应用的画面输入法读不到，真正的透镜弯折做不了。
 */
private fun liquidRichRim(radius: Dp, dark: Boolean, held: androidx.compose.runtime.State<Boolean>): Modifier =
    Modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val stroke = 1.1f * density
        val inset = stroke / 2f
        val corner = (radius.toPx() - inset).coerceIn(0f, size.minDimension / 2f)
        val bounds = Rect(inset, inset, w - inset, h - inset)
        val visible = bounds.width > 0f && bounds.height > 0f
        val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(corner))) }
        val band = (4f * density).coerceAtMost(size.minDimension / 4f)
        // 边缘厚度：只画在内侧一条带里（宽 2×band 的描边，被裁掉外半）。
        val thickness = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) .16f else .30f),
            .45f to Color.White.copy(alpha = 0f),
            .75f to Color.Black.copy(alpha = 0f),
            1f to Color.Black.copy(alpha = if (dark) .14f else .07f))
        // 月牙高光：沿对角线，左上亮、中段断开、右下弱回光。
        val specular = Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) .55f else .85f),
            .35f to Color.White.copy(alpha = 0f),
            .75f to Color.White.copy(alpha = 0f),
            1f to Color.White.copy(alpha = if (dark) .22f else .35f),
            start = Offset.Zero, end = Offset(w, h))
        // 浅色背景上外沿一道极淡的暗线，玻璃才有轮廓。
        val outline = Color.Black.copy(alpha = if (dark) .0f else .035f)
        val heldFill = Color.White.copy(alpha = if (dark) .08f else .14f)
        onDrawWithContent {
            if (visible) clipPath(path) {
                if (held.value) drawPath(path, heldFill)
                if (outline.alpha > 0f) drawPath(path, outline, style = Stroke(stroke * .8f))
                drawPath(path, specular, style = Stroke(if (held.value) stroke * 1.6f else stroke))
            }
            drawContent()
        }
    }

/**
 * 图片背景的边缘折射：只在键帽外圈约 5dp 取样并弯折，保留轻微色散，键中央直接透出键盘已画好的背景。
 * 没有图片、背景半透明（再画会叠深）、Android 12 及以下都不画。
 * 不另存图片（直接用共用背景那张），按住时不鼓起，键盘不动就不重画。
 */
private fun Modifier.liquidRefraction(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) liquidRefraction33(radius) else this

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.liquidRefraction33(radius: Dp): Modifier = composed {
    val root = LocalView.current.rootView
    val backdrop = LocalSharedKeyboardBackdrop.current?.takeIf { it.windowRoot === root && it.opacity >= .99f }
        ?: return@composed this
    val image = backdrop.image ?: return@composed this
    val shader = LiquidRefractionShader.shared ?: return@composed this
    var origin by remember { mutableStateOf<Offset?>(null) }
    onGloballyPositioned { origin = it.positionInRoot() }.drawWithCache {
        val bitmap = image.asAndroidBitmap()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val imageShader = BitmapShader(bitmap, android.graphics.Shader.TileMode.DECAL, android.graphics.Shader.TileMode.DECAL)
            .apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
        onDrawBehind {
            val at = origin ?: return@onDrawBehind
            val frame = backdrop.frame
            if (frame.size.width <= 0 || frame.size.height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return@onDrawBehind
            val offset = frame.origin + backdrop.windowToRoot - at
            val motion = backdrop.motion?.transform ?: BackgroundMotionTransform.Identity
            val width = frame.size.width.toFloat()
            val height = frame.size.height.toFloat()
            // Same centre and translation as withSharedBackdropMotion. Both the frame and the key
            // are relative to the Compose root, including while the IME window slides into place.
            val left = offset.x + width * ((1f - motion.scale) / 2f + motion.shiftX)
            val top = offset.y + height * ((1f - motion.scale) / 2f + motion.shiftY)
            LiquidRefractionShader.bind(shader, imageShader, size.width, size.height, radius.toPx(), 5f * density, 9.5f * density,
                left, top, bitmap.width / (width * motion.scale), bitmap.height / (height * motion.scale))
            paint.shader = shader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
            paint.shader = null
        }
    }
}

/**
 * 全进程只编译一次；各键在主线程上依次设参数再画，每次改参数系统都会生成新的着色器快照，所以共用安全。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LiquidRefractionShader {
    private const val SOURCE = """
        uniform shader image;
        uniform float2 iSize;
        uniform float radius;
        uniform float bevel;
        uniform float strength;
        uniform float2 imageOrigin;
        uniform float2 pixelsPerUnit;
        half4 main(float2 p) {
            float2 c = iSize * 0.5;
            float r = min(radius, min(c.x, c.y));
            float2 rel = p - c;
            float2 q = abs(rel) - c + r;
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            // The unbent centre already contains the actual backdrop, including slide crossfades.
            if (d > 0.5 || d <= -bevel) return half4(0.0);
            float2 g = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
            float2 n = g * sign(rel);
            float e = 1.0 - clamp(-d / bevel, 0.0, 1.0);
            float2 uv = (p - imageOrigin) * pixelsPerUnit;
            float2 off = -n * (strength * e * e * e) * pixelsPerUnit;
            half4 red = image.eval(uv + off);
            half4 green = image.eval(uv + off * 1.03);
            half4 blue = image.eval(uv + off * 1.06);
            half coverage = min(red.a, min(green.a, blue.a));
            return half4(min(half3(red.r, green.g, blue.b), half3(coverage)), coverage)
                * half(e * clamp(0.5 - d, 0.0, 1.0));
        }
    """

    val shared: RuntimeShader? by lazy { try { RuntimeShader(SOURCE) } catch (_: Throwable) { null } }

    fun bind(shader: RuntimeShader, image: android.graphics.Shader, width: Float, height: Float,
        radius: Float, bevel: Float, strength: Float, left: Float, top: Float, pixelsPerX: Float, pixelsPerY: Float) {
        shader.setInputShader("image", image)
        shader.setFloatUniform("iSize", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("imageOrigin", left, top)
        shader.setFloatUniform("pixelsPerUnit", pixelsPerX, pixelsPerY)
    }
}

/**
 * 一道细亮边：朝光的左上亮，对角右下有一道弱一些的回光，中间几乎没有。不画厚度渐变和阴影
 * （第一版画了，看着像塑料按钮）。
 */
private fun liquidRim(radius: Dp, dark: Boolean, held: androidx.compose.runtime.State<Boolean>): Modifier =
    Modifier.drawWithCache {
        val rim = liquidRimGeometry(radius, dark)
        val heldFill = Color.White.copy(alpha = if (dark) .04f else .08f)
        onDrawWithContent {
            val down = held.value
            if (rim.visible) clipPath(rim.path) {
                if (down) drawPath(rim.path, heldFill)
                drawPath(rim.path, rim.brush, style = Stroke(if (down) rim.stroke * 1.6f else rim.stroke))
            }
            drawContent()
        }
    }

/**
 * 面板件上的同一道细亮边（不含按住变亮）：画在底色上面、内容下面，[radius] 大于半高时就是胶囊。
 * 路径和渐变按尺寸缓存，只在本来就要重画时跟着画；[alpha] 在绘制阶段读，跟随按下淡出也不多组合一次。
 * 调用方先用 [LiquidGlass.surfacesOn] 判断，没开时原来的描边一点不变。
 */
internal fun Modifier.liquidGlassEdge(radius: Dp, alpha: () -> Float = { 1f }): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val dark = palette.surface.luminance() < .5f
    drawWithCache {
        val rim = liquidRimGeometry(radius, dark)
        onDrawWithContent {
            val shown = alpha().coerceIn(0f, 1f)
            if (rim.visible && shown > 0f) clipPath(rim.path) {
                drawPath(rim.path, rim.brush, alpha = shown, style = Stroke(rim.stroke))
            }
            drawContent()
        }
    }
}

private class LiquidRimGeometry(val path: Path, val brush: Brush, val stroke: Float, val visible: Boolean)

private fun androidx.compose.ui.draw.CacheDrawScope.liquidRimGeometry(radius: Dp, dark: Boolean): LiquidRimGeometry {
    val stroke = .75f * density
    val inset = stroke / 2f
    val corner = (radius.toPx() - inset).coerceIn(0f, size.minDimension / 2f)
    val bounds = Rect(inset, inset, size.width - inset, size.height - inset)
    val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(corner))) }
    val brush = Brush.linearGradient(
        listOf(Color.White.copy(alpha = if (dark) .45f else .75f), Color.White.copy(alpha = .10f),
            Color.White.copy(alpha = .06f), Color.White.copy(alpha = if (dark) .22f else .35f)),
        Offset.Zero, Offset(size.width, size.height),
    )
    return LiquidRimGeometry(path, brush, stroke, bounds.width > 0f && bounds.height > 0f)
}
