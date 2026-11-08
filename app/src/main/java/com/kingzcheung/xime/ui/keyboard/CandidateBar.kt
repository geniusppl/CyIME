package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial
import com.kingzcheung.xime.ui.theme.drawUnderTopFeather
import com.kingzcheung.xime.ui.theme.liquidGlassEdge
import com.kingzcheung.xime.ui.theme.withSharedBackdropMotion

import com.kingzcheung.xime.service.PredictionManager
import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.boundsInRoot
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.FirstBaseline
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.settings.CandidateCommentMode
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState

@Immutable
data class CandidateBarVisuals(
    val backgroundColor: Color,
    val textColor: Color,
    val dividerColor: Color,
    val accentColor: Color = Color(0xFF1A73E8),
    val selectedTextColor: Color = Color(0xFF1A73E8),
    val isDarkTheme: Boolean = false,
    val preeditBackgroundColor: Color = Color.Unspecified,
    val opaqueComments: Boolean = false,
    val textShadow: androidx.compose.ui.graphics.Shadow? = null,
    val glassControlBackground: Color? = null,
)

data class CandidateBarCallbacks(
    val onCandidateSelect: (Int) -> Unit,
    val onVisibleCandidatesChanged: ((List<String>) -> Unit)? = null,
    val onVisibleAssociationsChanged: ((List<String>) -> Unit)? = null,
    val onLogoClick: (() -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
    val onHideKeyboard: (() -> Unit)? = null,
    val onShowMoreCandidates: (() -> Unit)? = null,
    val onClearAssociation: (() -> Unit)? = null,
    val onInputTextClick: (() -> Unit)? = null,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onDismissClipboardPreview: (() -> Unit)? = null,
    val onOpenClipboard: (() -> Unit)? = null,
    val onClipboardImageSelect: ((com.kingzcheung.xime.clipboard.ClipboardImage) -> Unit)? = null,
    val onExpandClipboardImages: (() -> Unit)? = null,
    val onCancelInput: (() -> Unit)? = null,
    val onReorderToolbar: ((List<String>) -> Unit)? = null,
    // 长按候选：抛事件给宿主（键盘视图内弹确认覆盖层，不弹独立窗口——
    // 焦点型弹窗会抢焦点导致 IME 被系统收起）。
    val onCandidateLongPress: ((Int) -> Unit)? = null,
    /** 手指开始左右滑打字候选。 */
    val onCandidateStripScrolled: (() -> Unit)? = null,
    /** 打字候选滑到最后几个了。 */
    val onCandidateStripNearEnd: (() -> Unit)? = null,
)

/** 空间充足时均匀排布，项目溢出时保留完整触控尺寸并允许滚动。 */
@Composable
internal fun ToolbarItemsRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = maxWidth),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            content = content,
        )
    }
}

@Composable
fun CandidateBar(
    state: CandidateBarState,
    page: KeyboardPage = KeyboardPage.Main(com.kingzcheung.xime.keyboard.MainType.FULL),
    candidatePageExpanded: Boolean = false,
    toolbarActions: List<ToolbarAction> = emptyList(),
    visuals: CandidateBarVisuals,
    callbacks: CandidateBarCallbacks,
    inlineSuggestions: List<*> = listOf<Any>(),
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    isFloatingMode: Boolean = false,
    isVoiceSticky: Boolean = false,
    voiceAmplitude: Float = 0f,
    voiceSpectrum: FloatArray = FloatArray(16),
    voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    voicePluginName: String = "",
    onEditPreedit: (() -> Unit)? = null,
    showPreeditPreview: Boolean = true,
    showNumberLabels: Boolean = false,
    inlinePreeditEditor: (@Composable () -> Unit)? = null,
    commentsAreGlosses: Boolean = false,
) {
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val glassEnabled = glass.enabled
    val themeSurface = com.kingzcheung.xime.ui.theme.LocalMaterialPalette.current?.surface
        ?: visuals.preeditBackgroundColor.takeUnless { it == Color.Unspecified || it.alpha == 0f }
        ?: if (visuals.isDarkTheme) Color(0xFF202020) else Color(0xFFF2F2F2)
    val themeKey = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.key ?: themeSurface
    val shadowRadius = with(LocalDensity.current) { 1.dp.toPx() }
    // 候选栏/工具栏这一条跟按键一样厚：浅色玻璃默认底色 20%、按键 80%，栏里原来只有 20% 底色，
    // 后面是深色应用时整条透出应用的深底，深字只能靠白光晕撑着（GitHub #40/#55，平板 11-01 复现）。
    // 按键比底色厚时给这一条补一层同样的底色，补到按键的厚度；深色玻璃（底色比按键厚）和不透明主题都不变。
    val glassNormalized = glass.normalized()
    val panelOpacity = com.kingzcheung.xime.ui.theme.LocalSharedKeyboardBackdrop.current?.opacity
        ?: glassNormalized.backgroundOpacity
    // 浅色页亲手调过背景不透明度时，工具栏跟着键盘底色走，不再单独补厚（用户反馈 11-03）。
    val stripBoost = if (glassEnabled && !glass.lightPanelCustom) glassStripBoostAlpha(panelOpacity, glassNormalized.keyOpacity) else 0f
    val stripGlass = if (stripBoost > 0f)
        glass.copy(backgroundOpacity = maxOf(panelOpacity, glassNormalized.keyOpacity)) else glass
    val textSurface = remember(stripGlass, themeSurface, themeKey, shadowRadius, visuals.textColor, visuals.selectedTextColor, visuals.accentColor) {
        if (glassEnabled) resolveFrostedTextSurface(themeSurface, visuals.textColor,
            visuals.selectedTextColor, visuals.accentColor, stripGlass, themeKey, shadowRadius) else null
    }
    val protectedVisuals = visuals.withTextSurface(textSurface)
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val horizontalPadding = 8.dp
    val context = LocalContext.current

    // Toolbar and keys share the selected keyboard palette, including in previews.
    val iconButtonContainer = protectedVisuals.glassControlBackground ?: androidx.compose.ui.graphics.lerp(
        protectedVisuals.backgroundColor,
        protectedVisuals.accentColor,
        0.15f
    )
    val iconButtonTint = protectedVisuals.textColor
    // 候选栏两端的返回、展开按钮平时垫一层淡底，不画边框（用户 10-20）。从中间工具按钮按下时的底推出来，
    // 只取一半左右的浓度，处在候选栏底和中间按钮之间：玻璃时是同一种键帽玻璃（半透明，跟键帽一致），
    // 非玻璃时是同一种白/灰叠色，所以黑白主题也只是比底色稍亮或稍暗一点，不会冒出一块别的颜色。
    val edgeStrength = LocalEdgeButtonStrength.current
    val edgeButtonFill = protectedVisuals.glassControlBackground?.let { it.copy(alpha = it.alpha * edgeStrength) }
        ?: if (protectedVisuals.isDarkTheme) Color.White.copy(alpha = 0.25f * edgeStrength)
        else Color(0xFFE0E0E0).copy(alpha = edgeStrength)
    val preferences = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var showCancelButton by remember(preferences) {
        mutableStateOf(SettingsPreferences.shouldShowCandidateCancelButton(context))
    }
    var associationCancelCross by remember(preferences) {
        mutableStateOf(SettingsPreferences.isAssociationCancelCross(context))
    }
    var pressHighlight by remember(preferences) {
        mutableStateOf(preferences.getBoolean(SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT, false))
    }
    val edgePressedFill = if (pressHighlight) protectedVisuals.textColor.copy(alpha = 0.34f) else iconButtonContainer
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON || key == null) {
                showCancelButton = SettingsPreferences.shouldShowCandidateCancelButton(context)
            }
            if (key == SettingsPreferences.KEY_ASSOCIATION_CANCEL_CROSS || key == null) {
                associationCancelCross = SettingsPreferences.isAssociationCancelCross(context)
            }
            if (key == SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT || key == null) {
                pressHighlight = preferences.getBoolean(SettingsPreferences.KEY_CANDIDATE_PRESS_HIGHLIGHT, false)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val showCompositionCancel = showCancelButton && state !is CandidateBarState.Idle && callbacks.onCancelInput != null
    // 候选栏两端最多两个按钮（用户 11-03）：左边取消，最右展开候选，位置固定。左边打字和联想都是「<」，
    // 设置「联想关闭键用叉号」打开后联想时才是 ⊗（用户 11-03：两种状态图标不一样）；
    // 收起键盘只在空闲（没有候选）时有。手写候选也是纯联想态，但那里的取消是撤销当前字，仍用「<」。
    val closesAssociation = state is CandidateBarState.AssociationOnly &&
        !(page is KeyboardPage.Main && page.type == MainType.HANDWRITING)
    // 手写候选的注释是读音，单字也放得下：「自动」在手写里照样显示，只有「隐藏」才不显示
    // （用户 10-31：手机竖屏「自动」把手写读音藏掉了，忘了读音的字正要靠它）。
    val commentsAllowed = rememberCandidateCommentsAllowed().let {
        if (it == CandidateCommentMode.AUTO && page is KeyboardPage.Main && page.type == MainType.HANDWRITING) CandidateCommentMode.SHOW else it
    }
    // 终端里编码一律显示在候选栏（见 TerminalEditor）。
    val candidateTextSize = rememberCandidateTextSize()
    val candidateItemPadding = rememberCandidateItemPadding(candidateTextSize.sp)
    val candidateFontFamily = AppFonts.candidateFontFamily
    val commentFontFamily = AppFonts.commentFontFamily

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val itemPaddingPx = with(density) { 8.dp.toPx() }
    val spacingPx = with(density) { 4.dp.toPx() }

    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val rowPaddingPx = with(density) { 16.dp.toPx() }
    val rightSidePx = with(density) {
        val moreBtn = if (callbacks.onShowMoreCandidates != null) 38.dp.toPx() else 0f
        val clearBtn = if (callbacks.onClearAssociation != null) 38.dp.toPx() else 0f
        val hideBtn = if (callbacks.onHideKeyboard != null) 28.dp.toPx() else 0f
        rowPaddingPx + maxOf(moreBtn, clearBtn) + hideBtn + 8.dp.toPx()
    }

    // 候选行滚动状态：需在 state 分支前声明——ChineseCandidates 的 hasAnyMore
    // 叠加 canScrollForward 判断（见分支内注释）
    val candidateListState = rememberLazyListState()

    val displayCandidates: List<String>
    val displayAssociation: List<String>
    val displayComments: List<String>
    val hasAnyMore: Boolean
    val showInputTextRow: Boolean
    val showLeftIcon: Boolean

    when (val s = state) {
        is CandidateBarState.Idle -> {
            displayCandidates = emptyList()
            displayAssociation = emptyList()
            displayComments = emptyList()
            hasAnyMore = false
            showLeftIcon = true
        }
        is CandidateBarState.ChineseCandidates -> {
            // 候选栏只排放得下的几个，其余可左右滑动查看；展开列表接到候选栏时也全部可滑到（只排可见的几个）。
            displayCandidates = s.candidates
            displayComments = s.comments

            hasAnyMore = s.hasMore || s.candidates.size > 1
            showLeftIcon = false
            displayAssociation = s.associationCandidates.take(PredictionManager.MAX_ASSOCIATION_COUNT)
        }
        is CandidateBarState.AssociationOnly -> {
            displayCandidates = emptyList()
            // 展开时和打字候选一样：栏里能放几个放几个，展开页从放不下的那个接着排（用户 11-03：只显示一个「除」）。
            displayAssociation = s.candidates.take(maxOf(PredictionManager.MAX_ASSOCIATION_COUNT, s.highlightIndex + 1))
            hasAnyMore = s.hasMore
            showLeftIcon = false
            displayComments = s.comments
        }
        is CandidateBarState.EnglishCandidates -> {
            displayCandidates = s.candidates.take(20)
            displayComments = s.comments
            displayAssociation = emptyList()
            hasAnyMore = false
            showLeftIcon = false
        }
        is CandidateBarState.ClipboardDisplay -> {
            displayCandidates = s.candidates.take(20)
            displayComments = emptyList()
            displayAssociation = emptyList()
            hasAnyMore = false
            showLeftIcon = true
        }
        is CandidateBarState.Calculator -> {
            displayCandidates = s.candidates.take(20)
            displayComments = s.comments
            displayAssociation = emptyList()
            hasAnyMore = false
            showLeftIcon = false
        }
    }
    showInputTextRow = when (page) {
        is KeyboardPage.Overlay -> page.route !is OverlayRoute.Clipboard
        else -> true
    }

    val associationHighlight = (state as? CandidateBarState.AssociationOnly)?.highlightIndex
        ?.takeIf { it in displayAssociation.indices }
    // 换了一组候选回到最前；「下一项」移动联想高亮时，高亮已完整露出就不滚，移出栏外才滚到刚好露出，
    // 和打字候选同一规矩（用户 10-30：原来每按一下都把高亮滚到最左，像在翻页，看着永远是第一个）。
    // 纯联想态栏里只有联想词，联想序号就是 LazyRow 的序号。
    val associationSpacingPx = with(density) { 4.dp.roundToPx() }
    LaunchedEffect(displayCandidates, displayAssociation, associationHighlight) {
        if (associationHighlight == null) candidateListState.scrollToItem(0)
        else candidateListState.revealItem(associationHighlight, associationSpacingPx)
    }

    // Keep this animation outside the conditional button branches so changing pages
    // does not discard the previous angle and jump directly to the destination.
    val expansionRotation by animateFloatAsState(
        targetValue = if (candidatePageExpanded) -180f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing), label = "candidateExpansion",
    )
    val preeditText = (state as? CandidateBarState.ChineseCandidates)?.let {
        it.preeditText.ifEmpty { it.inputText }
    }.orEmpty()
    val previewPrefs = LocalKeyboardInputPreferences.current
    // 编码只显示在选中的一处：显示在输入框时不再另浮气泡（用户 11-01「只显示一处」；另设开关被他否掉：「这是想干什么」）。
    val showPreedit = showPreeditPreview && showInputTextRow && preeditText.isNotEmpty() &&
        previewPrefs.showCodeInCandidateBar
    // 分体时工具栏和候选栏也分到中缝两侧；剪贴板预览与自动填充建议仍是整条。
    // 编码预览浮在栏上方，不受中缝裁剪影响。
    val splitGap = LocalSplitKeyboardGap.current
    val splitGapX = splitGap?.gapX()?.takeIf {
        state !is CandidateBarState.ClipboardDisplay && inlineSuggestions.isEmpty()
    }
    var barOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var barTopInWindow by remember { mutableStateOf<Float?>(null) }
    // 根布局坐标：Shift 拖线和候选位置都用这个坐标系。
    val dragSpace = LocalKeyDragSpace.current
    var barDragOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    SideEffect { splitGap?.reportBarTop(if (splitGapX != null) barTopInWindow else null) }
    DisposableEffect(splitGap) { onDispose { splitGap?.reportBarTop(null) } }

    val cardTopCorner = LocalKeyboardCardTopCorner.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (state is CandidateBarState.ClipboardDisplay) Modifier.heightIn(min = 44.dp)
                else Modifier.height(44.dp))
            .onGloballyPositioned {
                barOrigin = it.positionInWindow()
                barTopInWindow = barOrigin.y
                barDragOrigin = dragSpace.point(it)
            }
            .shiftDragOverlay({ barDragOrigin }, protectedVisuals.accentColor)
            .clipSplitGapColumn(splitGapX, splitGap) { barOrigin }
            .background(if (glassEnabled) Color.Transparent else protectedVisuals.backgroundColor)
            .then(if (stripBoost > 0f) Modifier.glassStripBoost(stripBoost) else Modifier)
            // Glass rails inherit the keyboard background; only individual controls get a material.
            // 描边的上两角跟键盘卡片同一圆角，否则直角描边在卡片圆角处被切掉（用户 10-31）。
            .then(if (glassEnabled) Modifier else Modifier.visualMaterial(VisualStyles.current, cardTopCorner,
                level = if (state is CandidateBarState.Idle) com.kingzcheung.xime.ui.theme.MaterialLevel.FLOATING
                    else com.kingzcheung.xime.ui.theme.MaterialLevel.BASE, bottomRadius = 0.dp))
            .padding(horizontal = horizontalPadding)
            // 玻璃太薄时字和图标垫反色光晕，宿主是黑是白都看得清（用户 10-31 黑色背景截图）。
            .glassTextHalo(textSurface?.halo),
        verticalArrangement = Arrangement.Center,
    ) {
        PreeditBubbleHost(showPreedit && !previewPrefs.preeditInsideKeyboard) { hidden ->
            PreeditPreview(preeditText, protectedVisuals, onEditPreedit,
                preeditBubbleColor(themeSurface, visuals.preeditBackgroundColor,
                    blurBehind = glass.enabled && glass.normalized().blurRadiusDp >= 1f,
                    opacity = previewPrefs.preeditBubbleOpacity), hidden)
        }

        if (state is CandidateBarState.ClipboardDisplay) {
            ClipboardPreviewBar(state.candidates, protectedVisuals, callbacks, iconButtonContainer, state.image)
            return@Column
        }

        // 空闲工具栏：菜单、收起与中间各图标同为等宽一格，胶囊同尺寸（分体时扣除中缝）。
        val rowDensity = LocalDensity.current
        var toolbarRowWidth by remember { mutableStateOf(0.dp) }
        // 联想态不放收起键盘按钮，最右只有展开按钮（用户 11-02）。
        val idleToolbarCell = if (inlineSuggestions.isEmpty() && toolbarRowWidth > 0.dp) {
            val gap = splitGapX?.let { (l, r) -> with(rowDensity) { (r - l).toDp() } } ?: 0.dp
            // 空闲时左侧菜单／返回总在，固定格＝它加收起按钮。
            val fixed = (if (callbacks.onHideKeyboard != null) 1 else 0) + 1
            // 悬浮小窗里格子随宽度收窄（最窄 36dp），图标都留在面板内，不再横向溢出压住边框。
            maxOf((toolbarRowWidth - 8.dp - gap) / (toolbarActions.size + fixed).coerceAtLeast(1), 36.dp)
        } else null
        // 打字时两端「<」「^」用和空闲工具栏同样宽的格子：宽屏下同样变成胶囊（用户 10-20）。
        val edgeStyle = LocalEdgeButtonStyle.current
        val edgeSlot = if (edgeStyle == EdgeButtonStyle.CIRCLE || toolbarRowWidth <= 0.dp) null else {
            val gap = splitGapX?.let { (l, r) -> with(rowDensity) { (r - l).toDp() } } ?: 0.dp
            val baseSlot = maxOf((toolbarRowWidth - 8.dp - gap) / (toolbarActions.size + 2).coerceAtLeast(1), 36.dp)
            // 手机（含横屏）和平板竖屏都保持正圆；候选栏宽过 940dp 才开始拉长，到 1400dp（平板横屏）
            // 拉到最宽 52dp（用户 10-21：原来手机上就开始拉，太早）。悬浮小窗格子窄时照旧缩小的圆。
            val stretch = ((toolbarRowWidth - EDGE_STRETCH_START) / (EDGE_STRETCH_FULL - EDGE_STRETCH_START)).coerceIn(0f, 1f)
            if (baseSlot < 44.dp) baseSlot else 44.dp + 20.dp * stretch
        }
        // 同心：胶囊圆角 = 键盘卡片圆角 - 胶囊离栏边的距离（上下各 6dp），和卡片四角同心。
        val edgeCorner = if (edgeStyle == EdgeButtonStyle.CONCENTRIC) FloatingKeyboardCardCorner - 6.dp else null
        // 窄键盘两端锚定在候选箭头的中心，中间工具均分余宽。宽键盘的所有工具仍各占完整一格。
        val toolbarSlot = when {
            state !is CandidateBarState.Idle || idleToolbarCell == null -> null
            toolbarRowWidth < 600.dp -> edgeSlot ?: 40.dp
            else -> idleToolbarCell
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { toolbarRowWidth = with(rowDensity) { it.width.toDp() } },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showCompositionCancel && closesAssociation && associationCancelCross) {
                KeyboardToolbarButton({ callbacks.onCancelInput?.invoke() }, edgePressedFill,
                    modifier = Modifier.testTag("association-cancel-cross"), restingFill = edgeButtonFill,
                    slotWidth = edgeSlot, cornerRadius = edgeCorner, continuousPill = true) {
                    Icon(Icons.Outlined.HighlightOff, contentDescription = "关闭联想",
                        tint = protectedVisuals.textColor, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(4.dp))
            } else if (showCompositionCancel) {
                KeyboardBackButton({ callbacks.onCancelInput?.invoke() }, edgePressedFill, protectedVisuals.textColor,
                    label = if (closesAssociation) "关闭联想" else "取消输入",
                    modifier = if (closesAssociation) Modifier.testTag("association-cancel") else Modifier,
                    restingFill = edgeButtonFill, slotWidth = edgeSlot, cornerRadius = edgeCorner, continuousPill = true)
                Spacer(Modifier.width(4.dp))
            }
            if (inlinePreeditEditor != null && previewPrefs.showCodeInCandidateBar && previewPrefs.inlinePreeditPreview) {
                Box(Modifier.width(minOf(240.dp, toolbarRowWidth * 0.42f))) { inlinePreeditEditor() }
            } else if (showPreedit && previewPrefs.preeditInsideKeyboard) {
                Box(Modifier.widthIn(max = minOf(108.dp, toolbarRowWidth * 0.32f)).height(44.dp)
                    .then(if (onEditPreedit == null) Modifier else Modifier.clickable(onClickLabel = "编辑拼音", onClick = onEditPreedit))
                    .padding(horizontal = 4.dp).testTag("inline-preedit"), contentAlignment = Alignment.CenterStart) {
                    Text(preeditText, color = protectedVisuals.textColor, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showLeftIcon) {
                when (state) {
                    is CandidateBarState.Idle -> {
                        val panelHasToolbarBack = page is KeyboardPage.Overlay &&
                            (page.route is OverlayRoute.Menu || page.route is OverlayRoute.SchemaList || page.route is OverlayRoute.Edit ||
                                page.route is OverlayRoute.Emoji || page.route is OverlayRoute.Clipboard)
                        if ((page is KeyboardPage.Main && page.type == MainType.HANDWRITING || panelHasToolbarBack) && callbacks.onBack != null) {
                            KeyboardBackButton(callbacks.onBack, iconButtonContainer, protectedVisuals.textColor,
                                modifier = Modifier.testTag("toolbar-leading"), slotWidth = toolbarSlot)
                        } else {
                            KeyboardToolbarButton({ callbacks.onLogoClick?.invoke() }, iconButtonContainer,
                                modifier = Modifier.testTag("toolbar-leading"), slotWidth = toolbarSlot) {
                                Icon(painterResource(R.drawable.cyime_toolbar_mark), contentDescription = "CyIME Logo",
                                    tint = iconButtonTint, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    is CandidateBarState.ClipboardDisplay -> {
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "剪切板",
                                tint = protectedVisuals.accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }

            if (inlineSuggestions.isNotEmpty()) {
                LazyRow(
                    // 占满配额：内容少时建议靠左、右侧留白到收起按钮（收起按钮
                    // 因此固定最右）；内容超出配额时占满并可横向滑动查看后续建议；
                    // clipToBounds：滑动时滑出边界的建议裁剪掉，避免与左侧 logo 重叠
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds(),
                    overscrollEffect = null,
                ) {
                    itemsIndexed(inlineSuggestions, key = { index, _ -> index }) { _, suggestion ->
                        Box(modifier = Modifier.fillMaxHeight()) {
                            InlineSuggestionView(
                                suggestion = suggestion,
                                modifier = Modifier.fillMaxHeight(),
                            )
                            // 每条尾部 1dp 分隔线：条目之间为间隔，最后一条的尾线
                            // 同时充当与候选词区的分界（与旧平铺布局视觉一致）
                            InlineSuggestionDivider(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                color = protectedVisuals.dividerColor,
                            )
                        }
                    }
                }
            }

            if (state is CandidateBarState.ChineseCandidates || (splitGapX != null && state !is CandidateBarState.Idle) ||
                (candidatePageExpanded && state is CandidateBarState.AssociationOnly && state.hasMore)) {
                FixedCandidateStrip(
                    candidates = displayCandidates, associations = displayAssociation,
                    comments = displayComments,
                    visuals = protectedVisuals, callbacks = callbacks,
                    fontSize = candidateTextSize.sp,
                    modifier = Modifier.weight(1f),
                    commentMode = commentsAllowed,
                    commentsAreGlosses = commentsAreGlosses && state is CandidateBarState.ChineseCandidates,
                    highlightIndex = (state as? CandidateBarState.ChineseCandidates)?.highlightIndex ?: 0,
                    showNumberLabels = showNumberLabels,
                    gapWindowX = splitGapX,
                    scrollable = state is CandidateBarState.ChineseCandidates,
                    onUserScroll = callbacks.onCandidateStripScrolled,
                    onNearEnd = callbacks.onCandidateStripNearEnd,
                    scrollKey = (state as? CandidateBarState.ChineseCandidates)?.let { it.inputText to it.candidates.firstOrNull() },
                    itemPadding = candidateItemPadding,
                )
            } else if (state !is CandidateBarState.Idle) BoxWithConstraints(Modifier.weight(1f)) {
                val showComments = candidateCommentsVisible(maxWidth.value, density.fontScale,
                    candidateTextSize.toFloat(), commentsAllowed)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    state = candidateListState,
                    flingBehavior = rememberCandidateFlingBehavior(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    overscrollEffect = null,
                ) {
                    itemsIndexed(displayCandidates, key = { index, _ -> index }) { index, candidate ->
                        CandidateItem(
                            text = candidate,
                            index = index,
                            onClick = { callbacks.onCandidateSelect(index) },
                            onLongClick = if (callbacks.onCandidateLongPress != null) {
                                { callbacks.onCandidateLongPress(index) }
                            } else null,
                            textColor = protectedVisuals.textColor,
                            comment = if (showComments) {
                                when (val s = state) {
                                    is CandidateBarState.ChineseCandidates -> s.comments.getOrElse(index) { "" }
                                    is CandidateBarState.EnglishCandidates -> s.comments.getOrElse(index) { "" }
                                    else -> ""
                                }
                            } else "",
                            isSelected = index == 0,
                            accentColor = protectedVisuals.accentColor,
                            selectedTextColor = protectedVisuals.selectedTextColor,
                            opaqueComment = protectedVisuals.opaqueComments,
                            textShadow = protectedVisuals.textShadow,
                            fontSize = candidateTextSize.sp,
                            candidateFontFamily = candidateFontFamily,
                            commentFontFamily = commentFontFamily,
                            correction = if (state is CandidateBarState.ChineseCandidates) LocalCorrectedCandidates.current[candidate].orEmpty() else "",
                            horizontalPadding = candidateItemPadding,
                        )
                    }

                    // 仅当左侧存在打字候选时才需要分隔线；纯联想态（无打字候选）下
                    // 该竖线会孤悬列表最左缘，属多余元素。
                    // 注意：分隔线在条件内，联想词 items 必须在条件外——纯联想态
                    // displayCandidates 为空，若一并包进条件会导致联想词整个不渲染。
                    if (displayCandidates.isNotEmpty() && displayAssociation.isNotEmpty()) {
                        item(key = "divider") {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(20.dp)
                                    .background(protectedVisuals.dividerColor.copy(alpha = 0.5f))
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }

                    itemsIndexed(displayAssociation, key = { index, _ -> "assoc-$index" }) { index, candidate ->
                        val assocState = state as? CandidateBarState.AssociationOnly
                        CandidateItem(
                            text = candidateNumberedText(candidate, index, showNumberLabels && assocState != null),
                            index = -1,
                            onClick = { callbacks.onAssociationSelect?.invoke(index) },
                            textColor = protectedVisuals.textColor,
                            comment = if (showComments) displayComments.getOrElse(index) { "" } else "",
                            isSelected = assocState?.highlightIndex == index,
                            accentColor = protectedVisuals.accentColor,
                            selectedTextColor = protectedVisuals.selectedTextColor,
                            opaqueComment = protectedVisuals.opaqueComments,
                            textShadow = protectedVisuals.textShadow,
                            fontSize = candidateTextSize.sp,
                            candidateFontFamily = candidateFontFamily,
                            commentFontFamily = commentFontFamily,
                            modifier = Modifier.testTag("bar-association:$index"),
                            horizontalPadding = candidateItemPadding,
                        )
                    }
                }
            }

            if (state !is CandidateBarState.Idle) Spacer(modifier = Modifier.width(8.dp))

            when {
                state is CandidateBarState.Idle -> {
                    // 显示内联建议时隐藏工具栏按钮区，把宽度让给建议；logo 与
                    // 收起按钮保留，退格回到 idle 时的状态感知不变
                    if (inlineSuggestions.isEmpty() && splitGapX != null) {
                        // 分体：图标按两侧宽度比例分到中缝左右，各自整格可点。
                        SplitGapRow(splitGapX, Modifier.weight(1f).fillMaxHeight(), left = { leftWidth, rightWidth ->
                            ToolbarHalf(toolbarActions, protectedVisuals, iconButtonTint, voiceAmplitude, voiceRecognitionState,
                                callbacks.onReorderToolbar, leftWidth, rightWidth, rightSide = false)
                        }, right = { leftWidth, rightWidth ->
                            ToolbarHalf(toolbarActions, protectedVisuals, iconButtonTint, voiceAmplitude, voiceRecognitionState,
                                callbacks.onReorderToolbar, leftWidth, rightWidth, rightSide = true)
                        })
                    } else if (inlineSuggestions.isEmpty()) {
                        ReorderableToolbar(toolbarActions, protectedVisuals.accentColor, callbacks.onReorderToolbar,
                            modifier = Modifier.weight(1f)) { action ->
                            ToolbarActionButton(action, protectedVisuals, iconButtonTint, voiceAmplitude, voiceRecognitionState,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (callbacks.onHideKeyboard != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        HideKeyboardButton(callbacks.onHideKeyboard, iconButtonContainer, isVoiceSticky,
                            protectedVisuals.textColor, toolbarSlot)
                    }
                }
                !candidatePageExpanded && displayAssociation.isNotEmpty() && callbacks.onClearAssociation != null && callbacks.onCancelInput == null -> {
                    val clearInteractionSource = remember { MutableInteractionSource() }
                    val isClearPressed by clearInteractionSource.collectIsPressedAsState()

                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(protectedVisuals.dividerColor).padding(end = 1.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(30.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isClearPressed) (if (protectedVisuals.isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(
                                    alpha = 0.1f
                                ))
                                else Color.Transparent
                            )
                            .clickable(
                                interactionSource = clearInteractionSource,
                                indication = null,
                                onClick = { callbacks.onClearAssociation() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "清空",
                            color = if (isClearPressed) protectedVisuals.textColor.copy(alpha = 0.6f) else protectedVisuals.textColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            // 打字时候选栏只放候选：亮着的开关（悬浮、繁体、全角等）只在空闲工具栏里显示，
            // 不再挤到候选右边（11-03：悬浮键盘里一直多一个蓝色悬浮按钮，候选只剩一个）。
            if (state !is CandidateBarState.Idle && (candidatePageExpanded || hasAnyMore)) {
                val action = if (candidatePageExpanded) callbacks.onBack else callbacks.onShowMoreCandidates
                action?.let {
                    CandidateExpansionButton(it, edgePressedFill, protectedVisuals.accentColor,
                        expanded = candidatePageExpanded, rotation = expansionRotation, restingFill = edgeButtonFill,
                        slotWidth = edgeSlot, cornerRadius = edgeCorner, continuousPill = true)
                }
            }
        }
    }
}

/** 收起键盘；语音听写中收起成底部悬浮条，语音继续。 */
@Composable
private fun HideKeyboardButton(
    onHide: () -> Unit,
    background: Color,
    isVoiceSticky: Boolean,
    tint: Color,
    slotWidth: androidx.compose.ui.unit.Dp?,
) {
    KeyboardToolbarButton(onHide, background, modifier = Modifier.testTag("toolbar-hide"), slotWidth = slotWidth) {
        Icon(if (isVoiceSticky) Icons.Default.KeyboardDoubleArrowDown else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isVoiceSticky) "收起成悬浮条" else "收起键盘",
            tint = tint, modifier = Modifier.size(24.dp))
    }
}

/**
 * 工具栏一格里的胶囊：随格宽向两边拉长（参照 Gboard 大屏），窄格退为圆。
 * 中间按钮的按下/开启底色、菜单与收起按钮的底色、拖动排序的外框共用这一尺寸。
 */
internal fun toolbarPillSize(slot: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.DpSize {
    // 高 32dp，在 44dp 的栏里上下各留 6dp；窄格（悬浮小窗）缩成圆，左右至少留 4dp，不压面板边框。
    val width = (slot - 12.dp).coerceIn(28.dp, 112.dp)
    return if (width > 40.dp) androidx.compose.ui.unit.DpSize(width, 32.dp)
        else androidx.compose.ui.unit.DpSize(minOf(width, 32.dp), minOf(width, 32.dp))
}

@Composable
internal fun ToolbarActionButton(
    action: ToolbarAction, visuals: CandidateBarVisuals, tint: Color,
    amplitude: Float, voiceState: RecognitionState,
    source: MutableInteractionSource = remember { MutableInteractionSource() },
    modifier: Modifier = Modifier,
) {
    val voiceActive = action.active && action.item.id == "voice"
    // 外圈（胶囊底色）固定，内圈只跟随实时音量；峰值按分贝映射，正常说话也能明显撑开。
    val voiceLevel by animateFloatAsState(
        if (voiceActive && voiceState == RecognitionState.LISTENING) microphoneLevel(amplitude) else 0f,
        animationSpec = tween(100), label = "microphoneLevel")
    val pressed by source.collectIsPressedAsState()
    // Hover/focus events must not cancel a release fade and leave a permanent glow.
    val pressAlpha by animateFloatAsState(if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 0 else 300), label = "toolbar-press")
    // The disc appears only while pressed or on (no resting disc, glass or not). Under glass
    // the active tool is filled solidly with an inverted glyph; voice keeps its pulsing glow.
    val glassActiveFill = visuals.glassControlBackground?.takeIf { action.active && !voiceActive }?.let { fill ->
        val surface = fill.copy(alpha = 1f)
        if (textContrast(visuals.accentColor, surface) >= 3f) visuals.accentColor.copy(alpha = 1f) else tint.copy(alpha = 1f)
    }
    val iconTint = when {
        glassActiveFill != null -> if (glassActiveFill.luminance() > 0.5f) Color.Black else Color.White
        action.active -> visuals.accentColor
        else -> tint
    }
    // 大屏参照 Gboard：整格都可点，按下/开启的底色是随格宽向两边拉长的胶囊；窄格仍是圆形。
    val listening = voiceActive && voiceState == RecognitionState.LISTENING
    // 液态玻璃：胶囊露出来时（开启常亮，按下随底色淡出）加按键同款细亮边；听写音量光圈不加。
    val liquidEdge = com.kingzcheung.xime.ui.theme.LiquidGlass.surfacesOn() && !listening
    BoxWithConstraints(
        modifier.widthIn(min = 36.dp).height(40.dp)
            // 音量光圈画在不裁切的外层：始终正圆、比图标大，宽格也不会被拉成胶囊。
            .then(if (listening) Modifier.microphoneLevelHalo({ voiceLevel }, visuals.accentColor) else Modifier)
            .semantics {
                selected = action.active
                if (voiceActive) stateDescription = if (voiceState == RecognitionState.PROCESSING) "正在转录" else "正在聆听，点击结束"
            }
            .clickable(interactionSource = source, indication = null, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
    Box(
        Modifier.size(if (listening) androidx.compose.ui.unit.DpSize(36.dp, 36.dp) else toolbarPillSize(maxWidth))
            .clip(CircleShape)
            .background(if (listening) Color.Transparent else glassActiveFill ?: visuals.glassControlBackground?.let { fill ->
                if (action.active) androidx.compose.ui.graphics.lerp(fill, visuals.accentColor, 0.18f).copy(alpha = fill.alpha)
                else fill.copy(alpha = fill.alpha * pressAlpha)
            } ?: if (action.active) visuals.accentColor.copy(alpha = 0.28f)
                else if (visuals.isDarkTheme) Color.White.copy(alpha = 0.25f * pressAlpha)
                else Color(0xFFE0E0E0).copy(alpha = pressAlpha))
            .then(if (liquidEdge) Modifier.liquidGlassEdge(CAPSULE_RADIUS) { if (action.active) 1f else pressAlpha }
                else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (voiceActive && voiceState == RecognitionState.PROCESSING) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp), color = visuals.accentColor, strokeWidth = 2.dp)
        }
        ToolbarButtonIcon(action.item, tint = iconTint, modifier = Modifier.size(22.dp), active = action.active)
    }
    }
}

/** 胶囊的圆角：比任何胶囊的半高都大，细亮边按半高画成全圆头。 */
private val CAPSULE_RADIUS = 999.dp

/** 麦克风峰值（0~1 线性）转成 0~1 的显示音量：-50 dB 以下当静音，-15 dB 以上算满，小声说话也能明显撑开。 */
internal fun microphoneLevel(peak: Float): Float {
    if (peak <= 0f) return 0f
    val db = 20f * kotlin.math.log10(peak.coerceAtMost(1f))
    return ((db + 50f) / 35f).coerceIn(0f, 1f)
}

/**
 * 麦克风音量光圈：以控件中心画正圆，静音 30dp（比 22dp 图标大一圈），满音量 42dp。
 * 展开键盘的工具栏和收起后的悬浮条共用，外观一致。
 */
internal fun Modifier.microphoneLevelHalo(level: () -> Float, color: Color): Modifier = drawBehind {
    val value = level()
    drawCircle(color.copy(alpha = 0.30f + 0.30f * value), radius = (30.dp.toPx() + 12.dp.toPx() * value) / 2f)
}

@Composable
private fun ClipboardPreviewBar(
    candidates: List<String>,
    visuals: CandidateBarVisuals,
    callbacks: CandidateBarCallbacks,
    iconButtonContainer: Color,
    image: com.kingzcheung.xime.clipboard.ClipboardImage? = null,
) {
    val previews = remember(candidates) { candidates.map { it.replace(Regex("[\\r\\n]+"), " ") } }
    val textMeasurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 15.sp))
    val density = LocalDensity.current
    val tallestPreviewPx = previews.maxOfOrNull {
        textMeasurer.measure(it.take(80), style = textStyle, maxLines = 1, softWrap = false).size.height
    } ?: 0
    val verticalTextPadding = with(density) { ((44.dp - tallestPreviewPx.toDp()) / 2).coerceIn(0.dp, 7.dp) }
    val capsuleColor = visuals.preeditBackgroundColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.surfaceContainer
    val liquidEdge = com.kingzcheung.xime.ui.theme.LiquidGlass.surfacesOn()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KeyboardBackButton({ callbacks.onDismissClipboardPreview?.invoke() },
            iconButtonContainer, visuals.textColor, label = "返回工具栏")
        BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            // Reserve space for both icons and the scroll area's padding. Short previews
            // size the capsule to their text; long previews keep the full scroll viewport.
            val chromeWidth = 82.dp
            val maxTextWidthPx = with(density) { (maxWidth - chromeWidth).coerceAtLeast(0.dp).roundToPx() }
            var textWidthPx = if (image != null) with(density) { 64.dp.roundToPx() }.coerceAtMost(maxTextWidthPx) else 0
            if (image == null) for ((index, preview) in previews.withIndex()) {
                if (textWidthPx >= maxTextWidthPx || preview.length > 80) {
                    textWidthPx = maxTextWidthPx
                    break
                }
                val itemWidth = textMeasurer.measure(preview, style = textStyle,
                    maxLines = 1, softWrap = false).size.width
                val itemPadding = if (index == 0) 0 else with(density) { 16.dp.roundToPx() }
                textWidthPx = (textWidthPx + itemWidth + itemPadding).coerceAtMost(maxTextWidthPx)
            }
            val capsuleWidth = (chromeWidth + with(density) { textWidthPx.toDp() }).coerceAtMost(maxWidth)
            Row(
                Modifier.width(capsuleWidth).heightIn(min = 36.dp).testTag("clipboard-preview-pill")
                    .clip(CircleShape)
                    .background(capsuleColor)
                    // 液态玻璃：强调色描边换成按键同款细亮边。
                    .then(if (liquidEdge) Modifier.liquidGlassEdge(CAPSULE_RADIUS)
                        else Modifier.border(1.dp, visuals.accentColor.copy(alpha = 0.45f), CircleShape))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null,
                    tint = visuals.textColor, modifier = Modifier.size(18.dp))
                if (image != null) {
                    ClipboardImagePreview(image,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        onPaste = { callbacks.onClipboardImageSelect?.invoke(image) })
                } else LazyRow(Modifier.weight(1f).padding(horizontal = 8.dp).testTag("clipboard-preview-scroll"),
                    overscrollEffect = null) {
                    itemsIndexed(previews) { index, preview ->
                        // 预览可横向滚动全部内容；换行仅在显示时折成空格，粘贴仍使用原文。
                        Text(preview, color = visuals.textColor, fontSize = 15.sp, maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("clipboard-preview-text:$index")
                                .clickable { callbacks.onCandidateSelect(index) }
                                .padding(vertical = verticalTextPadding, horizontal = if (index == 0) 0.dp else 8.dp))
                    }
                }
                Box(Modifier.size(30.dp).clip(CircleShape)
                    .then(if (image != null) Modifier.testTag("clipboard-image-more") else Modifier)
                    .clickable {
                        (callbacks.onOpenClipboard ?: if (image != null) callbacks.onExpandClipboardImages else null)?.invoke()
                    },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "打开剪贴板",
                        tint = visuals.accentColor, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (callbacks.onHideKeyboard != null) {
            KeyboardToolbarButton(callbacks.onHideKeyboard, iconButtonContainer,
                modifier = Modifier.testTag("toolbar-hide")) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起键盘",
                    tint = visuals.textColor, modifier = Modifier.size(24.dp))
            }
        } else {
            Spacer(Modifier.size(40.dp))
        }
    }
}

/** Use the exact same explicit primary style for rendering and width measurement. */
internal fun candidatePrimaryTextStyle(
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    isSelected: Boolean,
) = TextStyle(
    fontSize = fontSize,
    fontFamily = fontFamily,
    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
    lineHeight = (fontSize.value * 1.35f).sp,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
)

/** 长候选缩到两行时：同一字体，行距收紧，量宽和绘制共用。 */
internal fun candidateTwoLineTextStyle(primary: TextStyle, fontSize: androidx.compose.ui.unit.TextUnit) =
    primary.copy(fontSize = fontSize, lineHeight = (fontSize.value * CANDIDATE_TWO_LINE_SPACING).sp)

internal val LocalCorrectedCandidates = androidx.compose.runtime.compositionLocalOf<Map<String, String>> { emptyMap() }

private val CorrectionRed = Color(0xFFE53935)

/** 纠错候选的拼音只显示改正后的读音（所打字母已在输入框里），改动的字母标红：补上的、换成的、对调的；多打的不再显示。 */
internal fun correctionAnnotatedString(marks: String, plain: Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val keep = androidx.compose.ui.text.SpanStyle(color = plain)
        val changed = androidx.compose.ui.text.SpanStyle(color = CorrectionRed)
        for (mark in com.kingzcheung.xime.rime.CorrectionMarks.parse(marks)) when (mark) {
            is com.kingzcheung.xime.rime.CorrectionMark.Keep -> withStyle(keep) { append(mark.letter) }
            is com.kingzcheung.xime.rime.CorrectionMark.Extra -> Unit
            is com.kingzcheung.xime.rime.CorrectionMark.Missing -> withStyle(changed) { append(mark.letter) }
            is com.kingzcheung.xime.rime.CorrectionMark.Replace -> withStyle(changed) { append(mark.correct) }
            is com.kingzcheung.xime.rime.CorrectionMark.Swap -> withStyle(changed) { append(mark.correct) }
            com.kingzcheung.xime.rime.CorrectionMark.Gap -> withStyle(keep) { append(' ') }
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CandidateItem(
    text: String,
    index: Int,
    onClick: () -> Unit,
    textColor: Color,
    comment: String = "",
    isSelected: Boolean = false,
    accentColor: Color = Color(0xFF1A73E8),
    selectedTextColor: Color = Color(0xFF1A73E8),
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 19.sp,
    candidateFontFamily: androidx.compose.ui.text.font.FontFamily = androidx.compose.ui.text.font.FontFamily.Default,
    commentFontFamily: androidx.compose.ui.text.font.FontFamily = androidx.compose.ui.text.font.FontFamily.Default,
    onLongClick: (() -> Unit)? = null,
    opaqueComment: Boolean = false,
    /** 注释是英文释义：字号比拼音注释大一号（自用学习版，用户 11-08 要求）。 */
    glossComment: Boolean = false,
    textShadow: androidx.compose.ui.graphics.Shadow? = null,
    /** 纠错来的候选：引擎给的逐音节纠正，代替拼音注释标出怎么改的。 */
    correction: String = "",
    /** Shift 拖动指着别的候选时由候选栏统一画底色，这里先不画，保证只亮一个。 */
    selectionFill: Boolean = true,
    /** 候选栏还没滑动时藏起来的候选：不能点，也不接 Shift 拖动。 */
    interactive: Boolean = true,
    /** 左右留白（含在点按范围和选中底色里），见 [rememberCandidateItemPadding]。 */
    horizontalPadding: androidx.compose.ui.unit.Dp = CANDIDATE_ITEM_PADDING,
    /** 比整条还宽的长候选缩小后的字号／两行，见 [CandidateFit]；平常为空。 */
    fit: CandidateFit? = null,
) {
    // 登记位置给 Shift 拖动：从 Shift 拖到这里松手，按住 Shift 选它（释义模式上屏英文）。
    val dropId = remember { Any() }
    val dragSpace = LocalKeyDragSpace.current
    val currentClick by rememberUpdatedState(onClick)
    DisposableEffect(dropId) { onDispose { CandidateDropTargets.shared.remove(dropId) } }
    if (!interactive) SideEffect { CandidateDropTargets.shared.remove(dropId) }
    Row(
        modifier = modifier
            .then(if (interactive) Modifier.onGloballyPositioned {
                CandidateDropTargets.shared.put(dropId, dragSpace.bounds(it)) { currentClick() } } else Modifier)
            .height(if (fit?.twoLines == true) maxOf(candidateItemHeight(fontSize), CANDIDATE_TWO_LINE_HEIGHT)
                else candidateItemHeight(fontSize))
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (isSelected && selectionFill) accentColor.copy(alpha = 0.2f)
                else Color.Transparent
            )
            .semantics { selected = isSelected }
            .then(if (interactive) Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val textSize = fit?.fontSize ?: fontSize
        val twoLineWidth = fit?.takeIf { it.twoLines }?.textWidth
        val primaryStyle = candidatePrimaryTextStyle(textSize, candidateFontFamily, isSelected).let {
            if (twoLineWidth != null) candidateTwoLineTextStyle(it, textSize) else it
        }.copy(shadow = textShadow)
        Text(
            text = text,
            modifier = if (twoLineWidth != null) Modifier.width(with(LocalDensity.current) { twoLineWidth.toDp() })
                else Modifier.candidateBaseline(textSize),
            color = if (isSelected) selectedTextColor else textColor,
            fontSize = textSize,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = if (twoLineWidth != null) 2 else 1,
            fontFamily = candidateFontFamily,
            style = primaryStyle,
            overflow = TextOverflow.Ellipsis,
        )
        if (correction.isNotEmpty() || comment.isNotEmpty()) {
            Spacer(modifier = Modifier.width(3.dp))
            val commentColor = if (isSelected) selectedTextColor.copy(alpha = if (opaqueComment) 1f else 0.6f) else textColor.copy(alpha = if (opaqueComment) 1f else 0.5f)
            Text(
                text = if (correction.isNotEmpty()) correctionAnnotatedString(correction, commentColor)
                    else androidx.compose.ui.text.AnnotatedString(comment),
                modifier = Modifier.candidateBaseline(fontSize),
                color = commentColor,
                fontSize = (fontSize.value * candidateCommentScale(glossComment)).sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                fontFamily = commentFontFamily,
                style = LocalTextStyle.current.copy(shadow = textShadow),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 候选后那段小字占候选字号的比例：拼音等注释 11/19，英文释义大一号 14/19。 */
internal fun candidateCommentScale(gloss: Boolean): Float = if (gloss) 14f / 19f else 11f / 19f

/** 候选后面那段小字：有纠错显示纠错，否则显示注释（和 [CandidateItem] 一致）。 */
internal fun candidateSecondaryText(comment: String, correction: String): AnnotatedString =
    if (correction.isNotEmpty()) correctionAnnotatedString(correction, Color.Unspecified) else AnnotatedString(comment)

/** 候选两侧的默认留白。 */
internal val CANDIDATE_ITEM_PADDING = 4.dp

/** 「候选词间距加宽」时每侧留白：按字号加大，两个候选之间（含 4dp 间隔）约空出 1.2 个字。 */
internal fun wideCandidateItemPadding(fontSize: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    CANDIDATE_ITEM_PADDING + fontSize * 0.3f

/** 候选两侧留白，随设置「候选词间距加宽」实时变化（默认关，B站反馈 10-31）。 */
@Composable
internal fun rememberCandidateItemPadding(fontSize: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    val preferences = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var wide by remember(preferences) { mutableStateOf(SettingsPreferences.isWideCandidateSpacing(context)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SettingsPreferences.KEY_WIDE_CANDIDATE_SPACING)
                wide = SettingsPreferences.isWideCandidateSpacing(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val fontDp = with(LocalDensity.current) { fontSize.toDp() }
    return if (wide) wideCandidateItemPadding(fontDp) else CANDIDATE_ITEM_PADDING
}

/** Reserve the same text height before asynchronous candidates arrive. */
@Composable
internal fun candidateItemHeight(fontSize: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.Dp =
    with(LocalDensity.current) { (fontSize.value * 1.35f).sp.toDp() } + 4.dp

/** 候选词和联想词共用：向下展开，向上返回键盘。 */
@Composable
private fun CandidateExpansionButton(
    onClick: () -> Unit,
    background: Color,
    foreground: Color,
    expanded: Boolean,
    rotation: Float,
    restingFill: Color = Color.Transparent,
    slotWidth: androidx.compose.ui.unit.Dp? = null,
    cornerRadius: androidx.compose.ui.unit.Dp? = null,
    continuousPill: Boolean = false,
) {
    KeyboardToolbarButton(onClick, background, restingFill = restingFill, slotWidth = slotWidth, cornerRadius = cornerRadius,
        continuousPill = continuousPill,
        modifier = Modifier.testTag("candidate-expansion").semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
        Icon(Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "返回键盘" else "展开候选词",
            tint = foreground, modifier = Modifier.size(24.dp).rotate(rotation))
    }
}

/**
 * 候选栏这一条在键盘玻璃底色上再补多少同样的底色，才和按键一样厚：
 * 1 − (1 − 底色)(1 − 补) = 按键 ⇒ 补 = 1 − (1 − 按键)/(1 − 底色)。底色不比按键薄（深色玻璃、「我的图片」）时不补。
 */
internal fun glassStripBoostAlpha(panelOpacity: Float, keyOpacity: Float): Float {
    val panel = panelOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return 0f
    val key = keyOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return 0f
    if (key <= panel || panel >= 1f) return 0f
    return (1f - (1f - key) / (1f - panel)).coerceIn(0f, 1f)
}

/**
 * 给候选栏这一条补底色：画的是键盘同一张玻璃底图（同一窗口坐标），只是再叠 [alpha]，
 * 所以纯色、渐变、图片主题都还是原来的样子，只是不再透出后面的应用。「柔化顶部边缘」照样渐隐。
 */
@Composable
private fun Modifier.glassStripBoost(alpha: Float): Modifier {
    val shared = com.kingzcheung.xime.ui.theme.LocalSharedKeyboardBackdrop.current
    val root = LocalView.current.rootView
    val backdrop = shared?.takeIf { it.windowRoot === root }
    val feather = com.kingzcheung.xime.ui.theme.LocalGlassTopFeather.current?.takeIf { it.windowRoot === root }
    val fallback = backdrop?.fallback ?: com.kingzcheung.xime.ui.theme.LocalMaterialPalette.current?.surface
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val surfaceKey = remember { Any() }
    if (backdrop != null) DisposableEffect(backdrop.reportSurfaceTop, surfaceKey) {
        onDispose { backdrop.reportSurfaceTop(surfaceKey, null) }
    }
    if (fallback == null) return this
    return onGloballyPositioned {
        origin = it.positionInWindow()
        backdrop?.reportSurfaceTop?.invoke(surfaceKey, origin.y)
    }.drawBehind {
        drawUnderTopFeather(feather?.top?.value?.minus(origin.y), feather?.heightPx ?: 0f) {
            val image = backdrop?.image?.takeIf { backdrop.frame.size.width > 0 && backdrop.frame.size.height > 0 }
            if (image == null) drawRect(fallback.copy(alpha = 1f), alpha = alpha)
            else {
                val offset = backdrop.frame.offsetIn(origin)
                clipRect(offset.x, offset.y, offset.x + backdrop.frame.size.width,
                    offset.y + backdrop.frame.size.height, androidx.compose.ui.graphics.ClipOp.Difference) {
                    drawRect(fallback.copy(alpha = 1f), alpha = alpha)
                }
                clipRect {
                    // 动态背景：和键盘本体同一个样子动。
                    withSharedBackdropMotion(backdrop, offset) {
                        translate(offset.x, offset.y) {
                            drawImage(image, dstSize = backdrop.frame.size, alpha = alpha,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** 非聚焦窗口提供真实触控区域，点击编码不会抢走宿主编辑框焦点。 */
@Composable
private fun PreeditPreview(text: String, visuals: CandidateBarVisuals, onEdit: (() -> Unit)?, bubbleColor: Color,
    /** 编码刚清空、窗口还没拆：气泡画成全透明，不能点，也不再垫模糊（见 [PreeditBubbleHost]）。 */
    hidden: Boolean = false) {
    val density = LocalDensity.current
    // 放大后的候选栏里 dp 变大了，按屏宽限宽要换回同样的像素宽度。
    val screenWidth = (LocalConfiguration.current.screenWidthDp / LocalCandidateBarScale.current).dp
    val margin = with(density) { 4.dp.roundToPx() }
    val gap = with(density) { 2.dp.roundToPx() }
    val rotation = LocalFloatingKeyboardRotation.current
    if (isFloatingRotated(rotation)) {
        RotatedPreeditPreview(text, visuals, onEdit.takeUnless { hidden }, rotation, gap, screenWidth, bubbleColor,
            if (hidden) HiddenPreeditBubble else Modifier)
        return
    }
    // 气泡在键盘外面（上方），键盘的玻璃模糊盖不到它：开玻璃时给气泡单独垫一块同样的实时模糊（用户反馈 10-31
    // 「字母显示栏的模糊会消失」：气泡底色和按键一样半透明，后面却是没模糊的应用）。区域按气泡在输入法窗口里的位置算。
    val bubbleBounds = remember { mutableStateOf<IntRect?>(null) }
    // 气泡贴着键盘左边（用户 10-20 要求恢复）：不跟光标，所有应用位置一样。
    val positionProvider = remember(margin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                return IntOffset(
                    anchorBounds.left.coerceIn(margin,
                        (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    (anchorBounds.top - popupContentSize.height - gap).coerceIn(0,
                        (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                ).also { offset ->
                    val bounds = IntRect(offset, popupContentSize)
                    if (bubbleBounds.value != bounds) bubbleBounds.value = bounds
                }
            }
        }
    }
    val glass = LocalKeyboardInputPreferences.current.frostedGlass.normalized()
    if (!hidden && glass.enabled && glass.blurRadiusDp >= 1f) {
        // 气泡内容最少 44dp 高、上下各留 6dp，真正的底色块在中间，圆角 4dp（见下面 PreeditLabel）。
        val inset = with(density) { 6.dp.roundToPx() }
        PreeditBubbleBlur(bubbleBounds.value?.let { IntRect(it.left, it.top + inset, it.right, it.bottom - inset) },
            glass.blurRadiusDp, with(density) { 4.dp.toPx() })
    }
    Popup(popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false,
            dismissOnClickOutside = false, clippingEnabled = true)) {
        PreeditLabel(text, visuals, onEdit.takeUnless { hidden }, Modifier.widthIn(max = screenWidth - 16.dp)
            .heightIn(min = 44.dp).padding(vertical = 6.dp).then(if (hidden) HiddenPreeditBubble else Modifier),
            solidBackground = bubbleColor, maxLines = PREEDIT_BUBBLE_MAX_LINES)
    }
}

/**
 * 拼音气泡是单独的 Popup 小窗口。窗口一拆，系统会拿它最后一帧放退出动画：先往下沉一小段，约半秒才没
 * （用户 11-06 转来的反馈）。编码清空时先把气泡画成全透明，等这几帧画面真正送上屏再拆窗口，
 * 退出动画播的就是空白，看上去当场消失。重新打字时窗口还在就直接接着用。位置、底色、不透明度都不变。
 */
@Composable
private fun PreeditBubbleHost(visible: Boolean, bubble: @Composable (hidden: Boolean) -> Unit) {
    var windowOpen by remember { mutableStateOf(false) }
    SideEffect { if (visible) windowOpen = true }
    LaunchedEffect(visible) {
        if (!visible && windowOpen) {
            repeat(PREEDIT_BUBBLE_CLEAR_FRAMES) { androidx.compose.runtime.withFrameNanos { } }
            windowOpen = false
        }
    }
    if (visible || windowOpen) bubble(!visible)
}

/** 气泡清空后再画几帧透明的才拆窗口：一帧排版绘制，其余留给合成器把这张空白画面换上屏。 */
private const val PREEDIT_BUBBLE_CLEAR_FRAMES = 3

/** 透明且没有语义节点：测试和无障碍都当气泡已经没了。 */
private val HiddenPreeditBubble = Modifier.alpha(0f).clearAndSetSemantics {}

/** 拼音气泡最多排几行；再长就在气泡里上下滚，始终露出光标所在的那行（没有光标时露出最后一行）。 */
private const val PREEDIT_BUBBLE_MAX_LINES = 3

/**
 * 拼音气泡的底色：按键颜色叠在键盘底色上、不透明，和键盘上的按键一个样（用户 10-31：原来是按键色再打六折，
 * 应用画面在气泡后面时拼音看不清；三星、Gboard 的气泡都是实底）。开着玻璃、气泡下面垫了实时模糊时最多 0.9。
 * [opacity] 是设置里的「拼音气泡不透明度」（默认 1，喜欢透的可以调低，用户 11-01）。
 */
internal fun preeditBubbleColor(surface: Color, key: Color, blurBehind: Boolean, opacity: Float = 1f): Color {
    val base = surface.copy(alpha = 1f)
    val solid = if (key == Color.Unspecified || key.alpha == 0f) base
        else key.compositeOver(base).copy(alpha = 1f)
    val alpha = opacity.coerceIn(0f, 1f).let { if (blurBehind) minOf(it, PREEDIT_BUBBLE_GLASS_ALPHA) else it }
    return solid.copy(alpha = alpha)
}

internal const val PREEDIT_BUBBLE_GLASS_ALPHA = 0.9f

/** 拼音气泡下面的实时模糊：和键盘卡片的玻璃同一种子窗口，放在输入法窗口下面，只模糊气泡那一块应用画面。 */
@Composable
private fun PreeditBubbleBlur(region: IntRect?, blurRadiusDp: Float, cornerRadiusPx: Float) {
    val view = LocalView.current
    val context = LocalContext.current
    // 模糊半径按屏幕真实密度算，和键盘卡片那块一样；候选栏放大只放大气泡，不改模糊强度。
    val radiusPx = (blurRadiusDp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val blur = remember(view) {
        com.kingzcheung.xime.service.ImeGlassBlurWindow(context, "preedit-bubble") { view.rootView }
    }
    DisposableEffect(blur) {
        blur.setVisible(true)
        onDispose { blur.dispose() }
    }
    SideEffect {
        blur.configure(true, radiusPx, cornerRadiusPx)
        blur.setRegion(region?.let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) })
    }
}

/**
 * 悬浮键盘转过时的拼音气泡：仍贴在候选栏左上方，但跟键盘一起倾斜。
 * Popup 的锚点是转过后的候选栏原点，气泡中心按同一角度转到窗口坐标；窗口取转后的外接框，里面再把气泡转过去。
 */
@Composable
private fun RotatedPreeditPreview(text: String, visuals: CandidateBarVisuals, onEdit: (() -> Unit)?,
    rotation: Float, gap: Int, screenWidth: androidx.compose.ui.unit.Dp, bubbleColor: Color,
    labelModifier: Modifier = Modifier) {
    val labelSize = remember { IntArray(2) }
    val positionProvider = remember(rotation, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val (cx, cy) = rotatePoint(labelSize[0] / 2f, -(labelSize[1] / 2f + gap), 0f, 0f, rotation)
                return IntOffset((anchorBounds.left + cx - popupContentSize.width / 2f).roundToInt(),
                    (anchorBounds.top + cy - popupContentSize.height / 2f).roundToInt())
            }
        }
    }
    Popup(popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false,
            dismissOnClickOutside = false, clippingEnabled = false)) {
        Box(Modifier.layout { measurable, _ ->
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints())
            labelSize[0] = placeable.width
            labelSize[1] = placeable.height
            val c = kotlin.math.abs(kotlin.math.cos(Math.toRadians(rotation.toDouble()))).toFloat()
            val s = kotlin.math.abs(kotlin.math.sin(Math.toRadians(rotation.toDouble()))).toFloat()
            val boxWidth = kotlin.math.ceil(placeable.width * c + placeable.height * s).toInt().coerceAtLeast(1)
            val boxHeight = kotlin.math.ceil(placeable.width * s + placeable.height * c).toInt().coerceAtLeast(1)
            layout(boxWidth, boxHeight) {
                placeable.placeWithLayer((boxWidth - placeable.width) / 2, (boxHeight - placeable.height) / 2) {
                    rotationZ = rotation
                }
            }
        }) {
            PreeditLabel(text, visuals, onEdit, Modifier.widthIn(max = screenWidth - 16.dp)
                .heightIn(min = 44.dp).padding(vertical = 6.dp).then(labelModifier),
                solidBackground = bubbleColor, maxLines = PREEDIT_BUBBLE_MAX_LINES)
        }
    }
}

/** Shared by the screen keyboard preview and the hardware caret surface. */
@Composable
internal fun PreeditLabel(text: String, visuals: CandidateBarVisuals, onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier, followTextTail: Boolean = false,
    /** 键盘上方的拼音气泡：给了就用这个实底（不再打折），字色按它调到看得清。 */
    solidBackground: Color? = null,
    /** 大于 1 时放不下就折行，最多这么多行，再多在里面上下滚。 */
    maxLines: Int = 1) {
    val scrollState = rememberScrollState()
    // The input preview must expose newly typed syllables once it reaches the available width.
    // A manual scroll remains untouched until the text or viewport changes.
    LaunchedEffect(text, followTextTail, scrollState.maxValue) {
        if (followTextTail && scrollState.maxValue != Int.MAX_VALUE) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    val background = if (visuals.preeditBackgroundColor != Color.Unspecified) visuals.preeditBackgroundColor
        else if (visuals.textColor.luminance() > 0.5f) Color(0xFF2D2F31) else Color(0xFFFAFAFA)
    val surface = solidBackground?.let {
        Modifier.heightIn(min = PreeditStyle.SurfaceHeight).clip(RoundedCornerShape(4.dp)).background(it)
            .padding(horizontal = 8.dp)
    } ?: Modifier.preeditSurface(background)
    val textColor = solidBackground?.let { readableThemeText(visuals.textColor, it.copy(alpha = 1f)) }
        ?: visuals.textColor.copy(alpha = if (visuals.opaqueComments) 1f else 0.9f)
    Box(modifier.then(surface)
        .testTag("candidate-preedit")
        .then(if (onEdit != null) Modifier.clickable(role = Role.Button,
            onClickLabel = "编辑拼音", onClick = onEdit) else Modifier),
        contentAlignment = Alignment.CenterStart) {
        val shownText = if (onEdit != null) com.kingzcheung.xime.rime.pinyinPreviewText(text) else text
        // 光标像系统文字光标一样闪（530ms 一亮一灭，用户 10-21 同意）。节拍挂在拼音条上而不是光标位置上：
        // 移动光标、光标暂时到末尾不显示时都接着原来的节拍走，不会每移一下又从头亮起。
        // 用计时切换，不用无限动画：无限动画每一帧（120Hz 屏每秒 120 次）都要唤醒界面线程，打拼音的全程一直耗电。
        var caretOn by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(530); caretOn = !caretOn } }
        val caretAlpha = if (caretOn) 1f else 0f
        // 折行时在隔音符后面垫零宽空格：tou'ming'du 整串没有空格，不垫就只能从音节中间断开。
        val displayText = if (maxLines > 1) shownText.replace("'", "'\u200B") else shownText
        val annotated = preeditWithCaret(displayText, visuals.accentColor, textColor, visuals.selectedTextColor, caretAlpha)
        val textStyle = LocalTextStyle.current.copy(shadow = visuals.textShadow)
        if (maxLines <= 1) {
            Text(annotated, color = textColor, fontSize = PreeditStyle.FontSize, maxLines = 1,
                softWrap = false, style = textStyle, modifier = Modifier.horizontalScroll(scrollState).testTag("candidate-preedit-text"))
        } else {
            // 长拼音折成几行，不再一行横穿整个应用、后半截被切掉（用户 10-31 截图）。
            val density = LocalDensity.current
            val lineHeight = PreeditStyle.FontSize * 1.35f
            val viewportPx = with(density) { (lineHeight * maxLines).roundToPx() }
            val lines = rememberScrollState()
            var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            LaunchedEffect(displayText, layout, lines.maxValue) {
                val result = layout ?: return@LaunchedEffect
                if (lines.maxValue == 0 || lines.maxValue == Int.MAX_VALUE) return@LaunchedEffect
                val caret = displayText.indexOf(com.kingzcheung.xime.service.PREEDIT_CARET)
                val target = if (caret < 0 || caret >= result.layoutInput.text.length) lines.maxValue else {
                    val line = result.getLineForOffset(caret)
                    val top = result.getLineTop(line).roundToInt()
                    val bottom = result.getLineBottom(line).roundToInt()
                    when {
                        top < lines.value -> top
                        bottom > lines.value + viewportPx -> bottom - viewportPx
                        else -> lines.value
                    }
                }
                lines.scrollTo(target.coerceIn(0, lines.maxValue))
            }
            Text(annotated, color = textColor, fontSize = PreeditStyle.FontSize, lineHeight = lineHeight,
                softWrap = true, style = textStyle, onTextLayout = { layout = it },
                modifier = Modifier.padding(vertical = 4.dp)
                    .heightIn(max = with(density) { viewportPx.toDp() })
                    .verticalScroll(lines).testTag("candidate-preedit-text"))
        }
    }
}

/** 选中底色上一次对应的候选组和选中项；只是记录，不触发重组。 */
private class HighlightMove {
    var candidates: List<String>? = null
    var index = -1
    var animateNext = false
}

/** Layout history only; updating it after composition must not schedule another recomposition. */
private class CandidateStripViewport { var firstIndex: Int = 0 }

/** [completeCount] 个候选连同注释完整可读，展开页从它们之后接着排。
 * [tailPadding]：能滑的候选行末尾补的空白（像素），滑到最末时最后一屏也从一个完整的候选开始。 */
private class CandidateStripWindow(val split: CandidateWindowSplit, val completeCount: Int, val tailPadding: Int = 0,
    val glossBodyWidthAt: ((Int) -> Int?)? = null, val fits: Map<Int, CandidateFit> = emptyMap())

/**
 * 比整条候选栏还宽的长候选（多为整句）怎么放下：先缩小字号，最小到原字号的 3/4；还放不下就缩到两行。
 * 两行也放不下的仍照旧一行，滑着看完。放得下的候选不受影响。[textWidth] 只在两行时用于折行。
 */
class CandidateFit(val fontSize: androidx.compose.ui.unit.TextUnit, val twoLines: Boolean,
    val textWidth: Int, val totalWidth: Int)

/** 两行长候选占的高度；候选栏 44dp，上下各留 2dp。 */
private val CANDIDATE_TWO_LINE_HEIGHT = 40.dp

/** 两行时的行高（字号的倍数）。 */
private const val CANDIDATE_TWO_LINE_SPACING = 1.2f

/** 长候选一行缩字的下限（原字号的倍数）。 */
private const val CANDIDATE_SHRINK_FLOOR = 0.75f

/** One measured row. Keyboard focus can move its window without changing candidate identities. */
@Composable
internal fun FixedCandidateStrip(
    candidates: List<String>, associations: List<String> = emptyList(), comments: List<String>, visuals: CandidateBarVisuals,
    callbacks: CandidateBarCallbacks, fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    highlightIndex: Int = 0,
    showNumberLabels: Boolean = false,
    itemSpacing: androidx.compose.ui.unit.Dp = 4.dp,
    /** 分体中缝（窗口横坐标）；候选先排左侧，越过中缝接着排右侧。 */
    gapWindowX: Pair<Float, Float>? = null,
    /** 能左右滑动看后面的候选；平时仍只显示放得下的那几个，手指一滑才露出后面的。分体中缝时不滑。 */
    scrollable: Boolean = false,
    /** 手指开始滑候选栏（展开着的话收起展开页，免得同一个候选出现两次）。 */
    onUserScroll: (() -> Unit)? = null,
    /** 滑到最后几个候选了（接着加载展开列表）。 */
    onNearEnd: (() -> Unit)? = null,
    /** 换了这个值（新的编码）就回到最前面。 */
    scrollKey: Any? = null,
    /** 每个候选左右的留白，见 [rememberCandidateItemPadding]。 */
    itemPadding: androidx.compose.ui.unit.Dp = CANDIDATE_ITEM_PADDING,
    /** 候选栏已按页面定好的注释显示方式（手写里「自动」按显示）；不传就按设置。 */
    commentMode: CandidateCommentMode? = null,
    commentsAreGlosses: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    var stripOriginX by remember { mutableStateOf(Float.NaN) }
    val density = LocalDensity.current
    val inheritedTextStyle = LocalTextStyle.current
    val commentsAllowed = rememberCandidateCommentsAllowed().let { commentMode ?: it }
    // 选中底色由整条统一画，换选中的候选时从旧候选滑到新候选（用户 10-21 选 1）；
    // 候选一变（打字、翻页）就直接落到新位置，打字时什么都不动。
    var stripRootOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var focusedBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 滑动时选中的候选滑出了候选栏：底色先不画，滑回来再画。
    var focusedOnScreen by remember { mutableStateOf(true) }
    val highlightBox = remember { androidx.compose.animation.core.Animatable(androidx.compose.ui.geometry.Rect.Zero,
        androidx.compose.ui.geometry.Rect.VectorConverter) }
    var highlightShown by remember { mutableStateOf(false) }
    // 只有同一组候选里换了选中项才滑；在组合时记下，等新位置量出来再决定滑还是直接落。
    val highlightMove = remember { HighlightMove() }
    if (highlightMove.candidates != candidates) highlightMove.animateNext = false
    else if (highlightMove.index != highlightIndex) highlightMove.animateNext = true
    highlightMove.candidates = candidates
    highlightMove.index = highlightIndex
    // 位置只在这里读，不让候选栏因为量位置而多重组一次。
    androidx.compose.runtime.LaunchedEffect(highlightBox) {
        androidx.compose.runtime.snapshotFlow { focusedBounds }.collectLatest { target ->
            if (target == null) return@collectLatest
            if (highlightShown && highlightMove.animateNext) {
                highlightMove.animateNext = false
                highlightBox.animateTo(target, androidx.compose.animation.core.tween(CANDIDATE_SLIDE_MS * 2,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing))
            } else { highlightBox.snapTo(target); highlightShown = true }
        }
    }
    val highlightAccent = visuals.accentColor
    val highlightVisible = candidates.isNotEmpty()
    BoxWithConstraints(modifier.clipToBounds().testTag("candidate-fixed-strip")
        .onGloballyPositioned { stripOriginX = it.positionInWindow().x; stripRootOrigin = it.boundsInRoot().topLeft }
        .drawBehind {
            val rect = highlightBox.value
            if (highlightShown && highlightVisible && focusedOnScreen && CandidateDropTargets.shared.hoveredBounds == null && rect.width > 0f)
                drawRoundRect(highlightAccent.copy(alpha = 0.2f), rect.topLeft - stripRootOrigin, rect.size,
                    androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        }) {
        // 中缝在本条内的位置（像素）；未测到位置前按整条排。
        val localGap = gapWindowX?.takeUnless { stripOriginX.isNaN() }?.let { (l, r) ->
            val left = (l - stripOriginX).coerceIn(0f, constraints.maxWidth.toFloat())
            val right = (r - stripOriginX).coerceIn(left, constraints.maxWidth.toFloat())
            if (right - left >= 1f && left > 0f) left.toInt() to right.toInt() else null
        }
        val shownComments = if (candidateCommentsVisible(maxWidth.value, density.fontScale,
            fontSize.value, commentsAllowed)) comments else emptyList()
        val all = candidates + associations
        val focused = highlightIndex.takeIf { it in candidates.indices } ?: 0
        val viewport = remember(candidates, shownComments) { CandidateStripViewport() }
        // 纠错和画出来的一样算进宽度，否则放不下的最后一个候选也算「显示了」，「下一项」移到它时被栏边切掉。
        val correctedCandidates = LocalCorrectedCandidates.current
        val window = remember(viewport, all, candidates.size, shownComments, commentsAreGlosses, scrollable, focused, fontSize, density, measurer, inheritedTextStyle, showNumberLabels, itemSpacing, itemPadding, constraints.maxWidth, AppFonts.candidateFontFamily, AppFonts.commentFontFamily, localGap, correctedCandidates) {
            val bodyWidths = HashMap<Int, Int>()
            fun bodyWidth(index: Int): Int = bodyWidths.getOrPut(index) {
                val numbered = index < candidates.size
                val text = candidateNumberedText(all[index], index, showNumberLabels && numbered)
                val primary = measurer.measure(AnnotatedString(text),
                    candidatePrimaryTextStyle(fontSize, AppFonts.candidateFontFamily, index == focused),
                    softWrap = false).size.width
                primary + with(density) { 2 * itemPadding.roundToPx() }
            }
            val fullWidths = HashMap<Int, Int>()
            fun widthAt(index: Int): Int = fullWidths.getOrPut(index) {
                val secondaryText = candidateSecondaryText(shownComments.getOrElse(index) { "" },
                    if (index < candidates.size) correctedCandidates[all[index]].orEmpty() else "")
                val secondary = if (secondaryText.isEmpty()) 0 else measurer.measure(secondaryText,
                    inheritedTextStyle.merge(TextStyle(fontSize = (fontSize.value * candidateCommentScale(commentsAreGlosses)).sp,
                        fontWeight = FontWeight.Normal, fontFamily = AppFonts.commentFontFamily)), softWrap = false).size.width + with(density) { 3.dp.roundToPx() }
                bodyWidth(index) + secondary
            }
            // 纠错标记和拼音仍要完整。只有释义可以越过本段右边界。
            val glossBodyWidthAt: ((Int) -> Int?)? = if (commentsAreGlosses && shownComments.isNotEmpty()) { index ->
                if (index !in candidates.indices || shownComments.getOrNull(index).isNullOrEmpty() || !correctedCandidates[all[index]].isNullOrEmpty()) null
                else bodyWidth(index)
            } else null
            fun measureFit(index: Int): CandidateFit? {
                val full = widthAt(index)
                if (full <= constraints.maxWidth) return null
                val text = AnnotatedString(candidateNumberedText(all[index], index, showNumberLabels && index < candidates.size))
                val primary = measurer.measure(text, candidatePrimaryTextStyle(fontSize, AppFonts.candidateFontFamily,
                    index == focused), softWrap = false).size.width
                val others = full - primary
                val room = constraints.maxWidth - others
                if (room <= 0 || primary <= 0) return null
                // 一行：按比例缩，每级 0.5sp，不低于原字号 3/4。
                var size = kotlin.math.floor(fontSize.value * room / primary * 2f) / 2f
                while (size >= fontSize.value * CANDIDATE_SHRINK_FLOOR) {
                    val width = measurer.measure(text, candidatePrimaryTextStyle(size.sp, AppFonts.candidateFontFamily,
                        index == focused), softWrap = false).size.width
                    if (width <= room) return CandidateFit(size.sp, twoLines = false, width, others + width)
                    size -= 0.5f
                }
                // 两行：字号取原字号 3/4，且两行要放进 40dp 高。两行还放不下就照旧一行，滑着看。
                val twoLineCap = with(density) { ((CANDIDATE_TWO_LINE_HEIGHT - 4.dp) / (2 * CANDIDATE_TWO_LINE_SPACING)).toSp().value }
                val twoSize = (kotlin.math.floor(minOf(fontSize.value * CANDIDATE_SHRINK_FLOOR, twoLineCap) * 2f) / 2f).sp
                val wrapped = measurer.measure(text, candidateTwoLineTextStyle(candidatePrimaryTextStyle(twoSize,
                    AppFonts.candidateFontFamily, index == focused), twoSize), maxLines = 2,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = room))
                return if (wrapped.hasVisualOverflow) null else CandidateFit(twoSize, twoLines = true, room, others + room)
            }
            // 比整条还宽的长候选先缩字、再缩到两行（用户 11-01：整句候选后半截看不到）。分体中缝时照旧。
            val fits = HashMap<Int, CandidateFit?>()
            fun fitAt(index: Int): CandidateFit? = when {
                localGap != null -> null
                index in fits -> fits[index]
                else -> measureFit(index).also { fits[index] = it }
            }
            fun shownWidth(index: Int): Int = fitAt(index)?.totalWidth ?: widthAt(index)
            val edgeWidthAt = glossBodyWidthAt?.let { body -> { index: Int -> body(index) ?: shownWidth(index) } }
            val spacing = with(density) { itemSpacing.roundToPx() }
            val split = if (localGap != null) {
                splitCandidateWindow(all.size, focused, localGap.first, constraints.maxWidth - localGap.second,
                    spacing, viewport.firstIndex, edgeWidthAt, ::widthAt)
            } else {
                val range = candidateWindow(all.size, focused, constraints.maxWidth,
                    spacing, windowStart = viewport.firstIndex, edgeWidthAt = edgeWidthAt, widthAt = ::shownWidth)
                CandidateWindowSplit(range, Int.MAX_VALUE)
            }
            val canScroll = scrollable && localGap == null
            val completeCount = if (canScroll) 0 else {
                var available = localGap?.first ?: constraints.maxWidth
                var count = 0
                for (index in split.range) {
                    if (localGap != null && count == split.leftCount) available = constraints.maxWidth - localGap.second
                    val width = shownWidth(index)
                    if (width > available) break
                    available -= width + spacing
                    count++
                }
                count
            }
            CandidateStripWindow(split, completeCount, if (canScroll)
                candidateTailPadding(all.size, constraints.maxWidth, spacing, ::shownWidth) else 0, glossBodyWidthAt,
                fits.mapNotNull { (index, fit) -> fit?.let { index to it } }.toMap())
        }
        val visibleIndices = window.split.range
        val splitLeftCount = window.split.leftCount
        androidx.compose.runtime.SideEffect {
            viewport.firstIndex = visibleIndices.first.takeUnless { visibleIndices.isEmpty() } ?: 0
            // Expanded pagination removes a prefix only. A shifted window must not hide earlier items.
            // 栏里放不全的长候选不算已显示：展开页从它开始排，能看到全文。
            if (!scrollable || localGap != null) {
                callbacks.onVisibleCandidatesChanged?.invoke(if (visibleIndices.first == 0)
                    candidates.take(window.completeCount) else emptyList())
                val completeEnd = visibleIndices.first + window.completeCount
                callbacks.onVisibleAssociationsChanged?.invoke(if (candidates.size >= visibleIndices.first && candidates.size < completeEnd)
                    associations.take(completeEnd - candidates.size)
                    else emptyList())
            }
        }
        // 分体：前半段排在中缝左边，后半段从中缝右边开始。
        val leftCount = splitLeftCount
        val segments = if (localGap == null) listOf(visibleIndices.toList() to 0)
            else listOf(visibleIndices.take(leftCount) to 0, visibleIndices.drop(leftCount) to localGap.second)
        // Shift 拖动指着候选时，选中底色改由候选栏画在手指指着的那个上，原来的先不画。
        val shiftDragFree = CandidateDropTargets.shared.hoveredBounds == null
        if (scrollable && localGap == null) {
            ScrollableCandidateRow(candidates, all, shownComments, visuals, callbacks, fontSize, focused, showNumberLabels,
                itemSpacing, itemPadding, visibleIndices, window.tailPadding, window.glossBodyWidthAt, window.fits, scrollKey, onUserScroll, onNearEnd,
                selectionFill = shiftDragFree && !highlightShown,
                onFocusedBounds = { focusedBounds = it; focusedOnScreen = true },
                onFocusedGone = { focusedOnScreen = false })
            return@BoxWithConstraints
        }
        segments.forEach { (segmentIndices, offsetPx) ->
        val segmentPx = if (offsetPx == 0 && localGap != null) localGap.first else constraints.maxWidth - offsetPx
        Row(Modifier.offset { androidx.compose.ui.unit.IntOffset(offsetPx, 0) }
            .widthIn(max = with(density) { segmentPx.toDp() }).clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing), verticalAlignment = Alignment.CenterVertically) {
            segmentIndices.forEach { index ->
                // 联想按词定身份：按下后联想换了一组，手指松开不选中换上来的那个词（和展开页一样）。
                androidx.compose.runtime.key(index, if (index < candidates.size) "" else all[index]) {
                CandidateItem(candidateNumberedText(all[index], index, showNumberLabels && index < candidates.size), index, {
                    if (index < candidates.size) callbacks.onCandidateSelect(index)
                    else callbacks.onAssociationSelect?.invoke(index - candidates.size)
                }, visuals.textColor,
                    comment = shownComments.getOrElse(index) { "" }, isSelected = index == focused,
                    accentColor = visuals.accentColor, selectedTextColor = visuals.selectedTextColor,
                    opaqueComment = visuals.opaqueComments, glossComment = commentsAreGlosses,
                    textShadow = visuals.textShadow,
                    fontSize = fontSize, candidateFontFamily = AppFonts.candidateFontFamily,
                    commentFontFamily = AppFonts.commentFontFamily,
                    modifier = Modifier.testTag("bar-candidate:$index")
                        .then(if (index == focused) Modifier.onGloballyPositioned { focusedBounds = it.boundsInRoot() } else Modifier),
                    onLongClick = if (index < candidates.size) callbacks.onCandidateLongPress?.let { action -> { action(index) } } else null,
                    correction = if (index < candidates.size) LocalCorrectedCandidates.current[all[index]].orEmpty() else "",
                    selectionFill = shiftDragFree && !highlightShown, horizontalPadding = itemPadding, fit = window.fits[index])
                }
            }
        }
        }
    }
}

/**
 * 能左右滑的候选行：用实际排版判断文字是否放得下，[shown] 指定初始焦点窗口；
 * 手指一滑就全部露出来，可以一直滑到展开列表里的候选。长候选不截断，滑过去就能看完。
 * 松手后对齐到候选开头，停下时两边被栏边切掉一半的候选先藏起来（用户 10-31：滑动后左边的候选显示不全）；
 * 末尾补 [tailPaddingPx] 的空白，滑到最末时也能从一个完整的候选开始。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollableCandidateRow(
    candidates: List<String>, all: List<String>, comments: List<String>, visuals: CandidateBarVisuals,
    callbacks: CandidateBarCallbacks, fontSize: androidx.compose.ui.unit.TextUnit, focused: Int,
    showNumberLabels: Boolean, itemSpacing: androidx.compose.ui.unit.Dp, itemPadding: androidx.compose.ui.unit.Dp,
    shown: IntRange, tailPaddingPx: Int, glossBodyWidthAt: ((Int) -> Int?)?, fits: Map<Int, CandidateFit>, scrollKey: Any?,
    onUserScroll: (() -> Unit)?, onNearEnd: (() -> Unit)?, selectionFill: Boolean,
    onFocusedBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onFocusedGone: () -> Unit,
) {
    val start = shown.first.takeUnless { shown.isEmpty() } ?: 0
    // 新编码或键盘焦点换了一屏：直接从那一屏开始，不带上一次滑到的位置。
    val listState = remember(scrollKey, start) { androidx.compose.foundation.lazy.LazyListState(start) }
    var revealed by remember(listState) { mutableStateOf(false) }
    val currentUserScroll by rememberUpdatedState(onUserScroll)
    val currentNearEnd by rememberUpdatedState(onNearEnd)
    val candidateCount by rememberUpdatedState(candidates.size)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) {
                revealed = true
                currentUserScroll?.invoke()
            }
        }
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            revealed && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= candidateCount - 4
        }.collect { if (it) currentNearEnd?.invoke() }
    }
    // 停稳后被栏边切掉一部分的候选（滑动、对齐途中为空，全部照常显示）。比整条还宽的长候选不算，要能滑着看完。
    val labelPadding = with(LocalDensity.current) { itemPadding.roundToPx() }
    val cutAtEdge by remember(listState, labelPadding, glossBodyWidthAt) {
        androidx.compose.runtime.derivedStateOf {
            if (listState.isScrollInProgress) emptySet<Int>() else {
                val info = listState.layoutInfo
                val viewport = info.viewportEndOffset - info.viewportStartOffset
                info.visibleItemsInfo.filter {
                    val bodyWidth = glossBodyWidthAt?.invoke(it.index) ?: it.size
                    // 超宽词或释义滑到中段时仍可读；新进入右端的词本身必须放全。
                    val readingOversizedItem = it.size > viewport && (bodyWidth > viewport || it.offset < info.viewportStartOffset)
                    !readingOversizedItem && !candidateLabelFits(it.offset, bodyWidth, labelPadding,
                        info.viewportStartOffset, info.viewportEndOffset)
                }.mapTo(HashSet()) { it.index }
            }
        }
    }
    // The expanded page skips only the actual readable prefix. Resting estimates include touch padding.
    val readablePrefix = remember(listState, labelPadding) {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (listState.isScrollInProgress || items.firstOrNull()?.index != 0) 0 else {
                val clipped = items.indexOfFirst { !candidateLabelFits(it.offset, it.size, labelPadding,
                    info.viewportStartOffset, info.viewportEndOffset) }
                if (clipped < 0) items.size else clipped
            }
        }
    }.value
    SideEffect {
        val layout = listState.layoutInfo
        if (all.isEmpty() || (layout.viewportEndOffset > layout.viewportStartOffset && layout.visibleItemsInfo.isNotEmpty())) {
            callbacks.onVisibleCandidatesChanged?.invoke(candidates.take(readablePrefix))
            callbacks.onVisibleAssociationsChanged?.invoke(if (readablePrefix > candidates.size)
                all.subList(candidates.size, readablePrefix.coerceAtMost(all.size)) else emptyList())
        }
    }
    // 露出长候选时不对齐，停在手指松开的地方，才能看到长候选中间的字。
    val longVisible by remember(listState) {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            info.visibleItemsInfo.any { it.size > viewport }
        }
    }
    // 松手后很快停住，方便马上点中候选（用户 11-05：惯性太大）。
    val snapFling = rememberCandidateSnapFlingBehavior(listState,
        androidx.compose.foundation.gestures.snapping.SnapPosition.Start)
    val freeFling = rememberCandidateFlingBehavior()
    val tailPadding = with(LocalDensity.current) { tailPaddingPx.toDp() }
    // 候选栏不要边缘拉伸回弹（这个文件里的几条横滑行都一样）：回弹没收完时，列表把每次按下都当成接着滑，
    // 点候选选不上，按着时栏尾藏起来的半截候选还会露出来（用户录屏 11-05：有的手机回弹一直收不完，
    // 横滑一下才恢复）。
    LazyRow(Modifier.fillMaxWidth().testTag("candidate-scroll-row"), state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = tailPadding),
        flingBehavior = if (longVisible) freeFling else snapFling,
        overscrollEffect = null,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing), verticalAlignment = Alignment.CenterVertically) {
        items(all.size) { index ->
            val hidden = index in cutAtEdge
            val numbered = index < candidates.size
            if (index == focused) DisposableEffect(Unit) { onDispose { onFocusedGone() } }
            // 选中的候选停在栏边被藏起来：选中底色也先不画。
            if (index == focused && hidden) SideEffect { onFocusedGone() }
            CandidateItem(candidateNumberedText(all[index], index, showNumberLabels && numbered), index, {
                if (numbered) callbacks.onCandidateSelect(index)
                else callbacks.onAssociationSelect?.invoke(index - candidates.size)
            }, visuals.textColor,
                comment = comments.getOrElse(index) { "" }, isSelected = index == focused,
                accentColor = visuals.accentColor, selectedTextColor = visuals.selectedTextColor,
                opaqueComment = visuals.opaqueComments, glossComment = glossBodyWidthAt != null,
                textShadow = visuals.textShadow,
                fontSize = fontSize, candidateFontFamily = AppFonts.candidateFontFamily,
                commentFontFamily = AppFonts.commentFontFamily,
                modifier = if (hidden) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier.testTag("bar-candidate:$index")
                    .then(if (index == focused) Modifier.onGloballyPositioned { onFocusedBounds(it.boundsInRoot()) } else Modifier),
                onLongClick = if (numbered) callbacks.onCandidateLongPress?.let { action -> { action(index) } } else null,
                correction = if (numbered) LocalCorrectedCandidates.current[all[index]].orEmpty() else "",
                selectionFill = selectionFill,
                interactive = !hidden, horizontalPadding = itemPadding, fit = fits[index])
        }
    }
}

// Latin and CJK fallback fonts have different ascents. Anchor actual baselines,
// not just differently sized text boxes, to the same position in the candidate row.
private fun Modifier.candidateBaseline(fontSize: androidx.compose.ui.unit.TextUnit): Modifier = layout { measurable, constraints ->
    val child = measurable.measure(constraints.copy(minHeight = 0))
    val height = (fontSize.toPx() * 1.35f).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
    val baseline = child[FirstBaseline]
    layout(child.width, height) {
        child.placeRelative(0, (fontSize.toPx() * 1.05f).roundToInt() - baseline)
    }
}

/** 分体工具栏的一侧：按两侧宽度比例分配图标；拖动排序后合回整条顺序保存。 */
@Composable
private fun ToolbarHalf(
    actions: List<ToolbarAction>, visuals: CandidateBarVisuals, tint: Color, amplitude: Float,
    voiceState: RecognitionState, onReorder: ((List<String>) -> Unit)?,
    leftWidth: androidx.compose.ui.unit.Dp, rightWidth: androidx.compose.ui.unit.Dp, rightSide: Boolean,
) {
    val total = (leftWidth.value + rightWidth.value).coerceAtLeast(1f)
    val leftCount = kotlin.math.round(actions.size * leftWidth.value / total).toInt().coerceIn(0, actions.size)
    val left = actions.take(leftCount)
    val right = actions.drop(leftCount)
    val mine = if (rightSide) right else left
    if (mine.isEmpty()) return
    val save: ((List<String>) -> Unit)? = onReorder?.let { persist ->
        { ids: List<String> ->
            persist(if (rightSide) left.map { it.item.id } + ids else ids + right.map { it.item.id })
        }
    }
    ReorderableToolbar(mine, visuals.accentColor, save, modifier = Modifier.fillMaxSize()) { action ->
        ToolbarActionButton(action, visuals, tint, amplitude, voiceState, modifier = Modifier.fillMaxWidth())
    }
}

/** 两端按钮平时的底相对中间按钮按下时的浓度；渲染对比测试里可以换值。 */
internal val LocalEdgeButtonStrength = androidx.compose.runtime.staticCompositionLocalOf { 0.45f }

/** 两端按钮的形状：圆（原样）、和空闲工具栏一样宽的胶囊、与键盘卡片同心的圆角胶囊。 */
internal enum class EdgeButtonStyle { CIRCLE, CAPSULE, CONCENTRIC }
internal val LocalEdgeButtonStyle = androidx.compose.runtime.staticCompositionLocalOf { EdgeButtonStyle.CAPSULE }

/** Shift 拖到候选时选中底色滑过去的时长；换选中候选时用它的两倍。 */
private const val CANDIDATE_SLIDE_MS = 120

/**
 * Shift 拖到候选上时（用户 10-21）：在候选栏里画出拖线的那一段（键盘那边只画到字母区为止），
 * 并把选中底色挪到手指指着的候选上（与平时选中候选同一样式，任何时候只亮一个）；换候选时底色滑过去（120ms）。
 * 只在绘制时读取拖动状态，不让候选栏重组。
 */
@Composable
private fun Modifier.shiftDragOverlay(rootOrigin: () -> androidx.compose.ui.geometry.Offset, accent: Color): Modifier {
    val drops = CandidateDropTargets.shared
    val hovered = drops.hoveredBounds
    // 底色和选中候选一样（强调色两成、5dp 圆角），不加边框；第一次指到候选时直接出现，之后换候选时滑过去。
    val box = remember { androidx.compose.animation.core.Animatable(androidx.compose.ui.geometry.Rect.Zero,
        androidx.compose.ui.geometry.Rect.VectorConverter) }
    var hadTarget by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(hovered) {
        if (hovered == null) { hadTarget = false; return@LaunchedEffect }
        if (!hadTarget) { box.snapTo(hovered); hadTarget = true }
        else box.animateTo(hovered, androidx.compose.animation.core.tween(CANDIDATE_SLIDE_MS))
    }
    return drawWithContent {
        drawContent()
        val origin = rootOrigin()
        val rect = box.value
        if (drops.hoveredBounds != null && rect.width > 0f) {
            drawRoundRect(accent.copy(alpha = 0.2f), rect.topLeft - origin, rect.size,
                androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        }
        drops.dragLine?.let { (from, to) ->
            clipRect {
                drawLine(accent, from - origin, to - origin, strokeWidth = 6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/** 候选栏两端按钮从圆开始拉长成胶囊的候选栏宽度，和拉到最宽 52dp 的宽度。 */
private val EDGE_STRETCH_START = 940.dp
private val EDGE_STRETCH_FULL = 1400.dp

/**
 * 拼音里的编辑光标“|”用主题强调色加粗画，和拼音字母区分开（用户 10-21）。其余文字照旧。
 * 柔和蓝、黑白这类强调色和拼音几乎同色的主题，改用首选候选的选中字色；光标只是一道线，不垫底色（用户 10-21）。
 * [alpha] 是闪烁的当前亮度。
 */
internal fun preeditWithCaret(text: String, accent: Color, textColor: Color = Color.Unspecified,
                              selectedText: Color = accent, alpha: Float = 1f): AnnotatedString {
    val caret = text.indexOf(com.kingzcheung.xime.service.PREEDIT_CARET)
    if (caret < 0) return AnnotatedString(text)
    val style = if (textColor != Color.Unspecified && colorsClose(accent, textColor))
        androidx.compose.ui.text.SpanStyle(color = selectedText.copy(alpha = selectedText.alpha * alpha),
            fontWeight = FontWeight.Bold)
    else androidx.compose.ui.text.SpanStyle(color = accent.copy(alpha = accent.alpha * alpha), fontWeight = FontWeight.Bold)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        addStyle(style, caret, caret + 1)
    }
}

/** 两个颜色肉眼几乎分不开（RGB 各分量差都很小）。 */
private fun colorsClose(a: Color, b: Color): Boolean =
    kotlin.math.abs(a.red - b.red) < 0.12f && kotlin.math.abs(a.green - b.green) < 0.12f &&
        kotlin.math.abs(a.blue - b.blue) < 0.12f

/** 列表里一项当前所占的横向范围（像素，相对视口）。 */
internal data class CandidateItemSpan(val index: Int, val start: Int, val end: Int)

/**
 * 让第 [index] 项完整露出还要滚多少像素：已完整露出是 0，往前露出是负数；紧挨在最后一个可见项后面、还没排出来时
 * 先滚到它露头（下一轮按量出的宽度补齐）；离得更远（如从最后一个绕回第一个）返回 null，直接跳到那一项。
 */
internal fun candidateRevealDelta(index: Int, visible: List<CandidateItemSpan>, viewportStart: Int, viewportEnd: Int,
    spacing: Int): Int? {
    visible.firstOrNull { it.index == index }?.let { item ->
        return when {
            item.start < viewportStart -> item.start - viewportStart
            item.end > viewportEnd -> minOf(item.end - viewportEnd, item.start - viewportStart)
            else -> 0
        }
    }
    val last = visible.lastOrNull() ?: return null
    return if (index == last.index + 1) maxOf(last.end - viewportEnd, 0) + spacing + 1 else null
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.revealItem(index: Int, spacing: Int) {
    // 等这一帧排完再量：高亮换了的那一项字重会变，宽度以新排的为准。
    androidx.compose.runtime.withFrameNanos { }
    repeat(3) {
        val info = layoutInfo
        val spans = info.visibleItemsInfo.map { CandidateItemSpan(it.index, it.offset, it.offset + it.size) }
        when (val delta = candidateRevealDelta(index, spans, info.viewportStartOffset, info.viewportEndOffset, spacing)) {
            null -> { scrollToItem(index); return }
            0 -> return
            else -> scrollBy(delta.toFloat())
        }
    }
}
