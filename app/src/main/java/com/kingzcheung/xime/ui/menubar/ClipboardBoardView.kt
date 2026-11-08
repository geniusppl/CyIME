package com.kingzcheung.xime.ui.menubar

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.kingzcheung.xime.ui.common.CompactActionMenu
import com.kingzcheung.xime.ui.common.CompactActionMenuEntry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kingzcheung.xime.ui.settings.idleBorderAlpha
import com.kingzcheung.xime.ui.settings.isNearBlack
import com.kingzcheung.xime.ui.theme.liquidGlassEdge
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.clipboard.*
import com.kingzcheung.xime.ui.keyboard.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class ClipboardMenuAnchor(val bounds: Rect = Rect.Zero, val occupied: List<Rect> = emptyList(),
    val preferSide: Boolean = true, val corner: Dp = 8.dp)

/** Corner of a record card; the long-press highlight uses the same one so its outline matches the card. */
/** 列表样式里图片记录的高度：一条横幅，不占半块面板。 */
internal val ClipboardListImageHeight = 72.dp

internal fun clipboardCardCorner(oneLine: Boolean): Dp = if (oneLine) 10.dp else 12.dp
private data class ClipboardItemMenuAnchor(val card: ClipboardCard, val position: ClipboardMenuAnchor)

@Composable
internal fun ConnectedClipboardBoard(
    textItems: List<ClipboardItem>, initialImages: Boolean, expanded: Boolean,
    onBack: () -> Unit, onQuickSend: () -> Unit, onSelectText: (String) -> Unit,
    onRemoveText: (List<Long>) -> Unit, onAddQuick: (Long) -> Unit, onSplit: (String, Long) -> Unit,
    onImageSelect: (ClipboardImage) -> Unit, onImageShare: (ClipboardImage, String?) -> Unit,
    onSystemPaste: () -> Unit, onPullRemote: (() -> Unit)?, modifier: Modifier = Modifier,
    onAddPinned: (() -> Unit)? = null, onEditText: ((Long, String, String) -> Unit)? = null,
    onRecognizeDocument: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    /** 长按菜单的「翻译」：交给 AI 改写的翻译；null（没装或没开 AI 改写）不显示这一项。 */
    onTranslateText: ((String) -> Unit)? = null,
    /** The search view above the keyboard hands its query on to the board when Enter is pressed. */
    keepQueryOnDispose: Boolean = false,
    /** The keyboard search field, typed live (composition included); null when the board is not searching from it. */
    liveQuery: ClipboardLiveQuery? = null,
    /** False in a short search results area (phone landscape): no category row, records only. */
    showControls: Boolean = true,
) {
    val context = LocalContext.current
    val store = remember(context) { ClipboardImages.getInstance(context) }
    val prefs = remember(context) { context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE) }
    val images by store.images.collectAsState()
    val failure by store.pasteFailure.collectAsState()
    val photoAccess by store.photoAccess.collectAsState()
    val screenshotsNeedAccess by store.screenshotsNeedAccess.collectAsState()
    var storedPins by remember { mutableStateOf(prefs.getStringSet("pins", emptySet()).orEmpty().toSet()) }
    var pendingTextPins by remember { mutableStateOf(emptyMap<Long, Boolean>()) }
    var pendingPinJobs by remember { mutableStateOf(emptyMap<Long, Job>()) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { store.refresh() }
    LaunchedEffect(Unit) { store.refresh() }
    val manager = remember(context) { ClipboardManager.getInstance(context) }
    // 短信里点链接、电话「复制」时键盘可能一直没收起，监听又没读到：打开面板时补读那一条（用户 11-03）。
    LaunchedEffect(manager) { runCatching { manager.readMissedCopy(boardOpened = true) } }
    val groupStore = remember(context) { ClipboardGroupStore.getInstance(context) }
    val groups by groupStore.state.collectAsState()
    // Back from the add / edit form: the board opens on the tab it was closed from.
    val returnTab = remember(groupStore) { if (showControls) groupStore.takeReturnTab() else null }
    val unpinnedTotal by manager.unpinnedCount.collectAsState()
    val room by manager.room.collectAsState()
    // 手动开的无痕（不含密码框、剪贴板搜索自动生效的那种）：面板顶上说明新复制的为什么没进来。
    val incognito = com.kingzcheung.xime.ui.keyboard.rememberQuickToggleStates().incognito
    // Older history is read page by page while open; closing returns to the first page.
    DisposableEffect(manager) { onDispose { manager.resetHistoryWindow() } }
    val cardMaxLines = remember(prefs) { clipboardCardMaxLines(prefs.getInt(CLIPBOARD_CARD_MAX_LINES_KEY, 4)) }
    val listLayout = remember(prefs) { prefs.getBoolean(CLIPBOARD_LIST_LAYOUT_KEY, true) }
    var closeAfterPaste by remember(prefs) { mutableStateOf(clipboardClosesAfterPaste(prefs)) }
    // A search submitted from the keyboard search bar shows only its matches (fixed records too).
    val boardQuery by manager.boardQuery.collectAsState()
    var searchResults by remember { mutableStateOf<List<ClipboardItem>?>(null) }
    LaunchedEffect(boardQuery, liveQuery, textItems) {
        if (liveQuery != null) {
            if (!liveQuery.isBlank) kotlinx.coroutines.delay(120) // Typing a word does not query per key.
            searchResults = if (liveQuery.isBlank) null else manager.search(liveQuery)
            return@LaunchedEffect
        }
        if (boardQuery.isNotBlank()) kotlinx.coroutines.delay(120) // Typing a word does not query per key.
        searchResults = if (boardQuery.isBlank()) null else manager.search(boardQuery)
    }
    DisposableEffect(manager) { onDispose { if (!keepQueryOnDispose) manager.boardQuery.value = "" } }
    val shownText = searchResults ?: textItems
    val pins = remember(shownText, storedPins, pendingTextPins) {
        clipboardPinnedKeys(shownText, storedPins, pendingTextPins)
    }
    LaunchedEffect(textItems, pendingPinJobs) {
        val inFlight = pendingTextPins.filterKeys { it in pendingPinJobs }
        val completed = pendingTextPins.filterKeys { it !in pendingPinJobs }
        pendingTextPins = inFlight + pendingClipboardTextPins(textItems, completed)
    }
    ClipboardBoardView(shownText, if (searchResults != null) emptyList() else images, initialImages, expanded, pins, photoAccess, failure,
        onBack, onQuickSend, onSelectText, onImageSelect, onSplit, onAddQuick,
        onPinsChange = { updated ->
            val changedTextPins = ((pins - updated) + (updated - pins)).mapNotNull { key ->
                key.takeIf { it.startsWith("text:") }?.removePrefix("text:")?.toLongOrNull()
                    ?.let { id -> id to (key in updated) }
            }.toMap()
            pendingTextPins = pendingTextPins + changedTextPins
            storedPins = updated.filterTo(mutableSetOf()) { it.startsWith("image:") }
            synchronized(prefs) {
                val unmigratedTextPins = prefs.getStringSet("pins", emptySet()).orEmpty()
                    .filterTo(mutableSetOf()) { it.startsWith("text:") }
                prefs.edit().putStringSet("pins", storedPins + unmigratedTextPins).apply()
            }
            val manager = ClipboardManager.getInstance(context)
            changedTextPins.forEach { (id, pinned) ->
                val job = manager.setClipboardPinned(id, pinned)
                pendingPinJobs = pendingPinJobs + (id to job)
                scope.launch {
                    job.join()
                    if (pendingPinJobs[id] === job) {
                        if (job.isCancelled) {
                            pendingTextPins = pendingTextPins - id
                            Toast.makeText(context, "无法保存固定状态，请重试", Toast.LENGTH_SHORT).show()
                        }
                        pendingPinJobs = pendingPinJobs - id
                    }
                }
            }
        },
        onRemove = { cards ->
            onRemoveText(cards.filterIsInstance<ClipboardCard.Text>().map { it.item.id })
            store.removeImages(cards.filterIsInstance<ClipboardCard.Image>().map { it.item.uri }.toSet())
        },
        onPhotoAccess = {
            if (store.recentScreenshotsEnabled()) store.enableRecentScreenshots(false)
            else context.startActivity(Intent(context, ImageAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onPick = { context.startActivity(Intent(context, ImageAccessActivity::class.java)
            .putExtra("pick_image", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onSystemPaste = onSystemPaste, onShare = onImageShare, onPullRemote = onPullRemote, modifier = modifier,
        onAddPinned = onAddPinned, onEditText = onEditText, onDismissFailure = store::clearPasteFailure,
        onRecognizeDocument = onRecognizeDocument, onTranslateText = onTranslateText,
        unpinnedTextTotal = unpinnedTotal, onLoadMore = { if (searchResults == null) manager.loadMoreHistory() },
        onClearUnpinned = { images ->
            // Clearing means all unfixed history in the database, not only the pages read so far.
            manager.clearAll()
            store.removeImages(images.filterIsInstance<ClipboardCard.Image>().map { it.item.uri }.toSet())
        }, cardMaxLines = cardMaxLines, listLayout = listLayout,
        searchQuery = (liveQuery?.display ?: boardQuery).takeIf { it.isNotBlank() },
        onClearSearch = { manager.boardQuery.value = "" }, searchChip = liveQuery == null,
        onOpenSearch = onOpenSearch, full = room.full, showControls = showControls,
        incognito = incognito && showControls,
        onIncognitoOff = { com.kingzcheung.xime.settings.SettingsPreferences.setIncognito(context, false) },
        screenshotAccess = screenshotsNeedAccess && showControls,
        onAllowScreenshots = { context.startActivity(Intent(context, ImageAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onHideScreenshots = { store.enableRecentScreenshots(false) },
        closeAfterPaste = closeAfterPaste, onCloseAfterPasteChange = { value ->
            closeAfterPaste = value
            prefs.edit().putBoolean(CLIPBOARD_CLOSE_AFTER_PASTE_KEY, value).apply()
        },
        groups = groups, initialTabKey = returnTab,
        onAssignGroup = groupStore::assign,
        onManageGroups = { record ->
            groupStore.setKeyboardEntry(record)
            context.startActivity(com.kingzcheung.xime.service.settingsActivityIntent(context,
                com.kingzcheung.xime.ui.settings.SettingsRoutes.ClipboardGroups))
            // The keyboard left open covered 新建分组 on the settings page (tablet 11-05); Back there returns here.
            hideKeyboardFrom(context)
        },
        onOpenSettings = {
            context.startActivity(com.kingzcheung.xime.service.settingsActivityIntent(context,
                com.kingzcheung.xime.ui.settings.SettingsRoutes.ClipboardPanel))
        },
        onFormOpen = { tab, adding ->
            groupStore.setReturnTab(tab.key)
            if (adding) groupStore.setAddTarget((tab as? ClipboardTab.Group)?.group?.id)
        })
}

/** Closes the keyboard this board is in (it runs inside the input method service). */
internal fun hideKeyboardFrom(context: Context) {
    generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.inputmethodservice.InputMethodService>().firstOrNull()?.requestHideSelf(0)
}

/** Preference (board prefs) for how many lines a history card shows: 2, 4 or 8. */
internal const val CLIPBOARD_CARD_MAX_LINES_KEY = "card_max_lines"
/** 剪贴板记录排成列表（默认）还是卡片。 */
internal const val CLIPBOARD_LIST_LAYOUT_KEY = "list_layout"

internal fun clipboardCardMaxLines(stored: Int): Int = if (stored in setOf(2, 4, 8)) stored else 4

/**
 * Preference (board prefs): close the clipboard after a text record is put into the text. On by
 * default, as before; off keeps the clipboard (or the search's matches) open to paste several.
 */
internal const val CLIPBOARD_CLOSE_AFTER_PASTE_KEY = "close_after_paste"

internal fun clipboardClosesAfterPaste(prefs: android.content.SharedPreferences): Boolean =
    prefs.getBoolean(CLIPBOARD_CLOSE_AFTER_PASTE_KEY, true)

internal fun clipboardClosesAfterPaste(context: Context): Boolean =
    clipboardClosesAfterPaste(context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE))

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ClipboardBoardView(
    textItems: List<ClipboardItem>, images: List<ClipboardImage>, initialImages: Boolean, expanded: Boolean,
    pins: Set<String>, photoAccess: Boolean, failure: ImagePasteFailure?,
    onBack: () -> Unit, onQuickSend: () -> Unit, onSelectText: (String) -> Unit,
    onSelectImage: (ClipboardImage) -> Unit, onSplit: (String, Long) -> Unit, onAddQuick: (Long) -> Unit,
    onPinsChange: (Set<String>) -> Unit, onRemove: (List<ClipboardCard>) -> Unit,
    onPhotoAccess: () -> Unit, onPick: () -> Unit, onSystemPaste: () -> Unit,
    onShare: (ClipboardImage, String?) -> Unit, onPullRemote: (() -> Unit)?, modifier: Modifier = Modifier,
    onAddPinned: (() -> Unit)? = null, onEditText: ((Long, String, String) -> Unit)? = null,
    onDismissFailure: () -> Unit = {},
    onRecognizeDocument: (() -> Unit)? = null,
    /** 长按菜单的「翻译」；null 不显示。密码记录没有这一项。 */
    onTranslateText: ((String) -> Unit)? = null,
    /** Unfixed text records in the whole history; -1 when only [textItems] is known. */
    unpinnedTextTotal: Int = -1,
    onLoadMore: () -> Unit = {},
    /** Clears all unfixed history; receives the unfixed images to remove. Null deletes [textItems] only. */
    onClearUnpinned: ((List<ClipboardCard>) -> Unit)? = null,
    cardMaxLines: Int = 4,
    /** 列表样式（豆包那种一条一行、分隔线隔开）；false 为原来的卡片瀑布流。 */
    listLayout: Boolean = false,
    /** The search shown in the board, or null; its chip clears it. */
    searchQuery: String? = null,
    onClearSearch: () -> Unit = {},
    onOpenSearch: (() -> Unit)? = null,
    /** False while the keyboard search field above shows the query: no second copy of it here. */
    searchChip: Boolean = true,
    /** History has reached its capacity: new copies are not being saved. */
    full: Boolean = false,
    /** The manual incognito switch is on: new copies are not being saved; existing records still work. */
    incognito: Boolean = false,
    onIncognitoOff: () -> Unit = {},
    /** 最近截图开着但没有相册权限：「全部」「图片」顶上一行「允许」。 */
    screenshotAccess: Boolean = false,
    onAllowScreenshots: () -> Unit = {},
    onHideScreenshots: () -> Unit = {},
    /** False hides the category / actions row (a short search results area). */
    showControls: Boolean = true,
    /** The "close after paste" switch shown in the more menu; null hides it. */
    closeAfterPaste: Boolean? = null,
    onCloseAfterPasteChange: (Boolean) -> Unit = {},
    /** The user's groups: tabs after the built-in categories, and what is in each. */
    groups: ClipboardGroupState = ClipboardGroupState(),
    /** Tab key to open on instead of 全部 / 图片 (back from the add / edit form). */
    initialTabKey: String? = null,
    /** Puts records into a group (null: out of their group); they are not fixed. */
    onAssignGroup: (Set<String>, String?) -> Unit = { _, _ -> },
    /**
     * Opens the group settings (new, rename, delete); null hides those entries. The argument is the
     * record whose 分组 menu offered 新建分组 (the new group takes it); null from 管理分组.
     */
    onManageGroups: ((String?) -> Unit)? = null,
    /** Opens Settings › 剪贴板面板 (the switches of this menu and the board's other settings); null hides it. */
    onOpenSettings: (() -> Unit)? = null,
    /** The add (true) or edit form is about to open from this tab. */
    onFormOpen: (ClipboardTab, Boolean) -> Unit = { _, _ -> },
) {
    val colors = MaterialTheme.colorScheme
    val tabs = remember(groups) { clipboardTabs(groups) }
    var chosenTab by remember(initialImages) { mutableStateOf<ClipboardTab>(
        tabs.firstOrNull { it.key == initialTabKey }
            ?: ClipboardTab.Category(if (initialImages) ClipboardFilter.IMAGE else ClipboardFilter.ALL)) }
    // A renamed group keeps its tab; a deleted group or hidden category falls back to 全部.
    val tab = tabs.resolve(chosenTab)
    var menu by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(ClipboardMenuAnchor()) }
    var deleteAnchor by remember { mutableStateOf(ClipboardMenuAnchor()) }
    val boardCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val recordsCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val moreBounds = remember { arrayOf(Rect.Zero) }
    val deleteBounds = remember { arrayOf(Rect.Zero) }
    // Each record's drawn bounds, so the long-press highlight is exactly the card (grid cell offsets
    // leave out the grid's content padding).
    val cardBounds = remember { mutableMapOf<String, Rect>() }
    // Deleted records leave at once. Unpinning them first would move a pin into recent history before
    // the delete lands, and the grid would scroll after it.
    var removing by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(false) }
    // Kept passwords the user chose to see (#95); hidden again when the board closes.
    var revealed by remember { mutableStateOf(emptySet<String>()) }
    var pinsExpanded by remember(initialImages) { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<ClipboardItemMenuAnchor?>(null) }
    // The 分组 choice opened from a record's menu, in the same place.
    var groupMenu by remember { mutableStateOf<ClipboardItemMenuAnchor?>(null) }
    // Physical-keyboard focus inside an open record menu or confirmation, and its entries.
    var menuFocus by remember { mutableStateOf(0) }
    val keyboardMenuEntries = remember { arrayOf<List<com.kingzcheung.xime.ui.common.CompactActionMenuEntry>>(emptyList()) }
    var deleting by remember { mutableStateOf<List<ClipboardCard>?>(null) }
    // True while the delete confirmation is the "clear all unfixed" one.
    var clearingAll by remember { mutableStateOf(false) }
    val all = remember(textItems, images, pins, removing) {
        clipboardCards(textItems, images, ClipboardFilter.ALL, pins).filterNot { it.key in removing }
    }
    LaunchedEffect(textItems, images) {
        if (removing.isNotEmpty()) removing = removing.intersect(
            clipboardCards(textItems, images, ClipboardFilter.ALL, pins).mapTo(mutableSetOf()) { it.key })
    }
    val cards = remember(all, tab, groups) { all.filter { clipboardMatches(it, tab, groups) } }
    val keyboard = LocalClipboardKeyboardNavigation.current
    var focusedKey by remember(keyboard?.active, tab.key) { mutableStateOf<String?>(null) }
    val gridState = rememberLazyStaggeredGridState()
    // Near the end of what is loaded, read the next page of older history (again after each page
    // arrives, so a sparse category keeps filling until the whole history is read).
    val nearEnd by remember { derivedStateOf {
        val info = gridState.layoutInfo
        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 30
    } }
    LaunchedEffect(nearEnd, textItems.size) { if (nearEnd) onLoadMore() }
    val scope = rememberCoroutineScope()
    fun menuPosition(anchorInRoot: Rect, preferSide: Boolean): ClipboardMenuAnchor {
        val boardOrigin = boardCoordinates[0]?.boundsInRoot()?.topLeft ?: Offset.Zero
        val records = recordsCoordinates[0]?.boundsInRoot() ?: Rect.Zero
        val visible = gridState.layoutInfo.visibleItemsInfo.mapNotNull { cell ->
            if (all.none { it.key == cell.key }) return@mapNotNull null
            val rect = Rect(records.left + cell.offset.x, records.top + cell.offset.y,
                records.left + cell.offset.x + cell.size.width, records.top + cell.offset.y + cell.size.height)
                .intersect(records)
            rect.takeIf { it.width > 0 && it.height > 0 }?.translate(-boardOrigin)
        }
        return ClipboardMenuAnchor(anchorInRoot.translate(-boardOrigin), visible, preferSide)
    }
    LaunchedEffect(tab.key) { gridState.scrollToItem(0) }
    fun changePinsExpansion(value: Boolean) {
        scope.launch {
            // Reset the scroll anchor before changing the keyed rows. A scrollToItem
            // after removal would reset the lazy item animator and discard fade-out.
            if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
                gridState.scrollToItem(0)
            }
            pinsExpanded = value
        }
    }
    LaunchedEffect(cards) {
        selected = selected.intersect(cards.mapTo(mutableSetOf()) { it.key })
    }
    LaunchedEffect(all, tab.key, pinsExpanded, failure) {
        // Anchors capture the visible grid at opening time. A background copy/update
        // can move a surviving record too; do not keep an obsolete source rectangle.
        menu = false; itemMenu = null; groupMenu = null; deleting = null
    }
    // A group keeps its records (never cleared or trimmed with history) without fixing them.
    fun assignGroup(keys: Set<String>, groupId: String?) = onAssignGroup(keys, groupId)
    val addPinned = onAddPinned?.let { add -> { onFormOpen(tab, true); add() } }
    val editText = onEditText?.let { edit -> { id: Long, text: String, code: String -> onFormOpen(tab, false); edit(id, text, code) } }
    DisposableEffect(keyboard) { onDispose { keyboard?.attach(null) } }
    BoxWithConstraints(modifier.fillMaxSize().keyboardPanelBackground(colors.surface).testTag("clipboard-board")
        .onGloballyPositioned { boardCoordinates[0] = it }) {
        val menuDensity = LocalDensity.current
        LaunchedEffect(constraints.maxWidth, constraints.maxHeight, menuDensity.density, menuDensity.fontScale) {
            // The source rectangles are opening-time snapshots. Resizing must not reuse stale anchors.
            menu = false; itemMenu = null; groupMenu = null; deleting = null
        }
        val columns = if (listLayout) 1 else clipboardColumnCount(maxWidth.value)
        val compactControls = maxWidth.value / LocalDensity.current.fontScale < 360f
        val content = remember(cards, pins, columns, pinsExpanded, listLayout) {
            // 列表样式一行一条：固定记录收起时留 3 条，而不是只剩 1 条。
            clipboardBoardContent(cards, pins, if (listLayout) 3 else columns, pinsExpanded)
        }
        val pinsActuallyExpanded = pinsExpanded && content.pinsCollapsible
        val visibleCards = remember(content) { content.visible }
        val focused = focusedKey?.takeIf { key -> visibleCards.any { it.key == key } }
            ?: visibleCards.firstOrNull()?.key
        val showScreenshotAccess = screenshotAccess && searchQuery == null &&
            (tab as? ClipboardTab.Category)?.filter.let { it == ClipboardFilter.ALL || it == ClipboardFilter.IMAGE }
        val gridKeys = remember(content, failure != null, full, incognito, showScreenshotAccess) {
            buildList {
                if (incognito) add("incognito")
                if (full) add("full")
                if (failure != null) add("failure")
                if (showScreenshotAccess) add("screenshot-access")
                addAll(content.pinned.map { it.key })
                if (content.pinned.isNotEmpty() && content.recent.isNotEmpty()) add("pins-end")
                addAll(content.recent.map { it.key })
            }
        }
        LaunchedEffect(focused, keyboard?.active, gridKeys) {
            if (keyboard?.active == true) {
                val index = gridKeys.indexOf(focused)
                val visible = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == focused }
                if (index >= 0 && (visible == null || visible.offset.y < gridState.layoutInfo.viewportStartOffset ||
                    visible.offset.y + visible.size.height > gridState.layoutInfo.viewportEndOffset)) gridState.scrollToItem(index)
            }
        }
        fun openItemMenu(card: ClipboardCard, oneLine: Boolean) {
            val cell = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == card.key } ?: return
            val region = recordsCoordinates[0]?.boundsInRoot() ?: return
            val bounds = (cardBounds[card.key] ?: Rect(region.left + cell.offset.x, region.top + cell.offset.y,
                region.left + cell.offset.x + cell.size.width, region.top + cell.offset.y + cell.size.height))
                .intersect(region)
            menuFocus = 0
            itemMenu = ClipboardItemMenuAnchor(card, menuPosition(bounds, true).copy(corner = clipboardCardCorner(oneLine)))
        }
        SideEffect {
            keyboard?.attach { action ->
                val focusedCard = visibleCards.firstOrNull { it.key == (focusedKey?.takeIf { key -> key in gridKeys } ?: focused) }
                // Physical keys: Space opens the record menu, arrows move inside it, Enter runs the
                // highlighted action, Delete asks to delete the record, Esc closes the menu first.
                val openMenu = itemMenu ?: groupMenu
                if (openMenu != null || deleting != null) {
                    val entries = keyboardMenuEntries[0]
                    when (action) {
                        ClipboardNavigation.UP, ClipboardNavigation.LEFT ->
                            menuFocus = (menuFocus - 1).coerceAtLeast(0)
                        ClipboardNavigation.DOWN, ClipboardNavigation.RIGHT ->
                            menuFocus = (menuFocus + 1).coerceAtMost((entries.size - 1).coerceAtLeast(0))
                        ClipboardNavigation.CONFIRM -> entries.getOrNull(menuFocus)?.takeIf { it.enabled }?.let { entry ->
                            itemMenu = null; groupMenu = null; deleting = null; menuFocus = 0; entry.onClick()
                        }
                        ClipboardNavigation.CANCEL -> { itemMenu = null; groupMenu = null; deleting = null }
                        else -> Unit
                    }
                    return@attach true
                }
                if (menu && action == ClipboardNavigation.CANCEL) { menu = false; return@attach true }
                if (!menu && !selecting && action == ClipboardNavigation.MENU) {
                    focusedCard?.let { card -> openItemMenu(card, content.pinned.any { it.key == card.key }) }
                    return@attach true
                }
                if (!menu && !selecting && action == ClipboardNavigation.DELETE) {
                    focusedCard?.let { card ->
                        val cell = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == card.key }
                        val region = recordsCoordinates[0]?.boundsInRoot()
                        if (cell != null && region != null) {
                            deleteAnchor = menuPosition(Rect(region.left + cell.offset.x, region.top + cell.offset.y,
                                region.left + cell.offset.x + cell.size.width, region.top + cell.offset.y + cell.size.height)
                                .intersect(region), true)
                            menuFocus = 0
                            deleting = listOf(card)
                        }
                    }
                    return@attach true
                }
                if (!menu && !selecting && itemMenu == null && groupMenu == null && deleting == null) {
                    if (action == ClipboardNavigation.CONFIRM) {
                        visibleCards.firstOrNull { it.key == (focusedKey?.takeIf { key -> key in gridKeys } ?: focused) }?.let { card ->
                            when (card) {
                                is ClipboardCard.Text -> onSelectText(card.item.text)
                                is ClipboardCard.Image -> onSelectImage(card.item)
                            }
                            keyboard.close()
                        }
                    } else {
                        focusedKey = nextClipboardKey(visibleCards.map { it.key }, focusedKey?.takeIf { it in gridKeys } ?: focused, action, columns,
                            gridState.layoutInfo.visibleItemsInfo.map { ClipboardCell(it.key.toString(),
                                it.offset.x, it.offset.y, it.size.width, it.size.height) })
                    }
                }
                false
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (showControls) Row(Modifier.fillMaxWidth().clipboardPanelExpandGesture()
                .testTag("clipboard-controls").padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    CompactClipboardAction(Icons.Default.Close, "退出多选", {
                        selecting = false
                        selected = emptySet()
                    }, "clipboard-exit-selection")
                    val allSelected = cards.isNotEmpty() && cards.all { it.key in selected }
                    CompactClipboardAction(
                        if (allSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        if (allSelected) "取消全选" else "全选当前分类", {
                            selected = if (allSelected) emptySet() else cards.mapTo(mutableSetOf()) { it.key }
                            // Do not operate on invisible folded pins without making the selection inspectable.
                            if (!allSelected) changePinsExpansion(true)
                        }, "clipboard-select-all", enabled = cards.isNotEmpty(),
                        label = if (compactControls) null else "全选")
                    Text("已选 ${selected.size}", color = colors.onSurface,
                        fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    val unpin = selected.isNotEmpty() && selected.all { it in pins }
                    CompactClipboardAction(if (unpin) Icons.Default.LockOpen else Icons.Default.PushPin,
                        if (unpin) "取消固定所选" else "固定所选", {
                        onPinsChange(if (unpin) pins - selected else pins + selected)
                        selecting = false
                        selected = emptySet()
                    }, "clipboard-pin", enabled = selected.isNotEmpty(),
                        label = if (compactControls) null else if (unpin) "取消固定" else "固定")
                    CompactClipboardAction(Icons.Default.DeleteOutline, "删除所选",
                        { deleteAnchor = menuPosition(deleteBounds[0], false); deleting = cards.filter { it.key in selected } }, "clipboard-delete",
                        enabled = selected.isNotEmpty(), label = if (compactControls) null else "删除",
                        modifier = Modifier.onGloballyPositioned { deleteBounds[0] = it.boundsInRoot() })
                } else {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        searchQuery?.takeIf { searchChip }?.let { query ->
                            CompactClipboardFilter("🔍 $query ✕", true, onClearSearch,
                                Modifier.testTag("clipboard-search-chip"))
                        }
                        tabs.forEach { item ->
                            CompactClipboardFilter(item.label, tab.key == item.key, { chosenTab = item },
                                Modifier.testTag("clipboard-filter:${item.key}"))
                        }
                    }
                    if (content.pinsCollapsible) {
                        Box(Modifier.heightIn(min = PanelCategoryHeight).clip(RoundedCornerShape(50))
                            .semantics { stateDescription = if (pinsActuallyExpanded) "已展开" else "已收起" }
                            .clickable(role = Role.Button,
                                onClickLabel = if (pinsActuallyExpanded) "收起" else "展开全部 ${content.pinnedCount} 条固定内容") {
                                changePinsExpansion(!pinsExpanded)
                            }.testTag("clipboard-pins-toggle").padding(horizontal = 2.dp),
                            contentAlignment = Alignment.Center) {
                            PinsFoldPill(if (pinsActuallyExpanded) "收起"
                                else if (compactControls) "${content.pinnedCount}" else "固定 ${content.pinnedCount}",
                                pinsActuallyExpanded, colors.onSurface)
                        }
                    }
                    onOpenSearch?.let {
                        CompactClipboardAction(Icons.Default.Search, "搜索剪贴板", it, "clipboard-search")
                    }
                    addPinned?.let {
                        CompactClipboardAction(Icons.Default.Add,
                            (tab as? ClipboardTab.Group)?.let { group -> "新增到「${group.label}」" } ?: "新增固定内容",
                            it, "clipboard-add-pinned")
                    }
                    CompactClipboardAction(Icons.Default.MoreHoriz, "更多操作", {
                        moreAnchor = menuPosition(moreBounds[0], false); menu = !menu
                    }, "clipboard-more", modifier = Modifier.onGloballyPositioned { moreBounds[0] = it.boundsInRoot() })
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("clipboard-records-region")
                .onGloballyPositioned { recordsCoordinates[0] = it }
                .clipboardPanelCollapseGesture(!selecting && !menu && itemMenu == null && groupMenu == null && deleting == null) {
                    !gridState.canScrollBackward
                }
                .clipboardCategorySwipe(!selecting && !menu && itemMenu == null && groupMenu == null && deleting == null) { delta ->
                    chosenTab = tabs.afterSwipe(tab, delta)
                }) {
                val expansion = LocalClipboardPanelExpansion.current
                val density = LocalDensity.current
                // Expanding exposes more records, not taller cards. Use the normal keyboard's
                // records area even at the expanded endpoint, so releasing cannot reflow the grid.
                val chromeHeight = remember(expansion, maxWidth, density.fontScale) {
                    expansion?.let { kotlin.math.round(it.height) - maxHeight.value } ?: 0f
                }
                val stableRecordsHeight = expansion?.let { (it.collapsedHeight - chromeHeight).coerceAtLeast(0f) }
                    ?: maxHeight.value
                val pinnedHeight = clipboardPinnedPreviewHeight(stableRecordsHeight).dp
                LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize().testTag("clipboard-records"),
                    state = gridState,
                    contentPadding = if (listLayout) PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        else PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (listLayout) 0.dp else 6.dp),
                    verticalItemSpacing = if (listLayout) 0.dp else 6.dp) {
                    if (incognito) item(key = "incognito", span = StaggeredGridItemSpan.FullLine) {
                        ClipboardIncognitoNotice(onIncognitoOff)
                    }
                    if (full) item(key = "full", span = StaggeredGridItemSpan.FullLine) {
                        Text("剪贴板已满，新复制的内容不再保存。删掉一些记录后恢复。",
                            Modifier.padding(horizontal = 4.dp, vertical = 6.dp).testTag("clipboard-full"),
                            color = colors.error, fontSize = 13.sp)
                    }
                    if (failure != null) item(key = "failure", span = StaggeredGridItemSpan.FullLine) {
                        ClipboardImageFailureNotice(failure, onSystemPaste, onShare, onDismissFailure)
                    }
                    if (showScreenshotAccess) item(key = "screenshot-access", span = StaggeredGridItemSpan.FullLine) {
                        ClipboardScreenshotAccessNotice(onAllowScreenshots, onHideScreenshots)
                    }
                    if (cards.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                        Text(if (searchQuery != null) "没有找到「$searchQuery」" else when (val shown = tab) {
                            is ClipboardTab.Group -> "「${shown.label}」里还没有内容。长按记录选「分组」放进来，或点 + 新增。"
                            is ClipboardTab.Category ->
                                "暂无${if (shown.filter == ClipboardFilter.ALL) "剪贴板记录" else shown.label}，复制后会显示在这里。"
                        },
                            Modifier.padding(vertical = 24.dp), color = colors.onSurfaceVariant, fontSize = 14.sp)
                    }
                    fun LazyStaggeredGridScope.records(records: List<ClipboardCard>, fixedRecords: Boolean) {
                        items(records, key = { it.key }) { card ->
                            val chosen = card.key in selected
                            ClipboardRecordCard(card = card, pinned = card.key in pins, chosen = chosen,
                                maxLines = if (listLayout) minOf(cardMaxLines, 2) else cardMaxLines, listRow = listLayout,
                                masked = card is ClipboardCard.Text && card.item.sensitive && card.key !in revealed,
                                selecting = selecting, keyboardFocused = keyboard?.active == true && card.key == focused,
                                // Pictures share the pinned preview height: a tall screenshot must not fill the board.
                                // Pins are one line; a recent picture keeps the bounded preview height.
                                previewHeight = if (fixedRecords) ClipboardPinnedRowHeight
                                    else if (card is ClipboardCard.Image) (if (listLayout) ClipboardListImageHeight else pinnedHeight) else null,
                                // Lazy layout retains removed keyed items through fade-out. Keep one
                                // grid so collapsing pins does not flash or replace recent history.
                                modifier = Modifier.animateItem(
                                    fadeInSpec = keyboardPanelMotion(true),
                                    placementSpec = keyboardPanelMotion(pinsActuallyExpanded),
                                    fadeOutSpec = keyboardPanelMotion(false))
                                    .onGloballyPositioned { cardBounds[card.key] = it.boundsInRoot() },
                                onPiece = { piece -> focusedKey = card.key; onSelectText(piece) },
                                onClick = {
                                    focusedKey = card.key
                                    if (selecting) selected = if (chosen) selected - card.key else selected + card.key
                                    else when (card) {
                                        is ClipboardCard.Text -> onSelectText(card.item.text)
                                        is ClipboardCard.Image -> onSelectImage(card.item)
                                    }
                                }, onLongClick = {
                                    if (selecting) {
                                        selected = if (chosen) selected - card.key else selected + card.key
                                    } else openItemMenu(card, fixedRecords)
                                })
                        }
                    }
                    records(content.pinned, fixedRecords = true)
                    if (content.pinned.isNotEmpty() && content.recent.isNotEmpty()) {
                        item(key = "pins-end", span = StaggeredGridItemSpan.FullLine) {
                            val motion = Modifier.animateItem(placementSpec = keyboardPanelMotion(pinsActuallyExpanded))
                            if (!content.pinsCollapsible) HorizontalDivider(motion.testTag("clipboard-pins-divider"), thickness = .5.dp,
                                color = colors.onSurface.copy(alpha = .18f))
                            else PinsFoldDivider(pinsActuallyExpanded, content.pinnedCount - content.pinned.size,
                                colors.onSurface, motion) { changePinsExpansion(!pinsExpanded) }
                        }
                    }
                    records(content.recent, fixedRecords = false)
                }
            }
        }
        itemMenu?.let { anchor ->
            val card = anchor.card
            val actions = buildList {
                add(LongPressMenuEntry(Icons.Default.PushPin, if (card.key in pins) "取消固定" else "固定") {
                    onPinsChange(if (card.key in pins) pins - card.key else pins + card.key)
                })
                if (groups.groups.isNotEmpty() || onManageGroups != null) {
                    add(LongPressMenuEntry(Icons.Default.Folder, groups.groupOf(card.key)?.let { "分组：${it.name}" } ?: "分组",
                        tag = "clipboard-item-group") { menuFocus = 0; groupMenu = anchor })
                }
                if (card is ClipboardCard.Text && card.item.sensitive) {
                    // A kept password: no 分词 (its pieces would be ordinary records), shown only on request.
                    val shown = card.key in revealed
                    add(LongPressMenuEntry(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (shown) "隐藏" else "显示", tag = "clipboard-item-reveal") {
                        revealed = if (shown) revealed - card.key else revealed + card.key
                    })
                } else if (card is ClipboardCard.Text) {
                    add(LongPressMenuEntry(Icons.Default.ContentCut, "分词") { onSplit(card.item.text, card.item.id) })
                    editText?.let { edit -> add(LongPressMenuEntry(Icons.Default.Edit, "编辑") {
                        edit(card.item.id, card.item.text, card.item.code)
                    }) }
                    onTranslateText?.let { translate -> add(LongPressMenuEntry(Icons.Default.Translate, "翻译",
                        tag = "clipboard-item-translate") { translate(card.item.text) }) }
                }
                add(LongPressMenuEntry(Icons.Default.DoneAll, "多选") {
                    selecting = true; selected = setOf(card.key)
                })
                add(LongPressMenuEntry(Icons.Default.DeleteOutline, "删除", colors.error) {
                    deleteAnchor = anchor.position; deleting = listOf(card)
                })
            }
            keyboardMenuEntries[0] = actions
            LongPressMenuOverlay(anchor.position.bounds, anchor.position.occupied,
                onDismiss = { itemMenu = null }, menuItems = actions, highlightCorner = anchor.position.corner,
                focusedIndex = menuFocus.takeIf { keyboard?.active == true })
        }
        groupMenu?.let { anchor ->
            val key = anchor.card.key
            val current = groups.groupOf(key)?.id
            val actions = buildList {
                groups.groups.forEach { group ->
                    add(LongPressMenuEntry(if (group.id == current) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        group.name, tag = "clipboard-group:${group.id}") { assignGroup(setOf(key), group.id) })
                }
                if (current != null) add(LongPressMenuEntry(Icons.Default.Close, "移出分组", tag = "clipboard-group-leave") {
                    assignGroup(setOf(key), null)
                })
                onManageGroups?.let { manage ->
                    // No group yet: the group made on the settings page takes this record (tablet 11-05).
                    val creating = groups.groups.isEmpty()
                    add(LongPressMenuEntry(if (creating) Icons.Default.Add else Icons.Default.Settings,
                        if (creating) "新建分组" else "管理分组", tag = "clipboard-group-manage") {
                        manage(key.takeIf { creating })
                    })
                }
            }
            keyboardMenuEntries[0] = actions
            LongPressMenuOverlay(anchor.position.bounds, anchor.position.occupied,
                onDismiss = { groupMenu = null }, menuItems = actions, title = "放入分组",
                highlightCorner = anchor.position.corner, tag = "clipboard-group-menu",
                focusedIndex = menuFocus.takeIf { keyboard?.active == true })
        }
        if (menu) {
            val actions = buildList {
                add(CompactActionMenuEntry(Icons.Default.DoneAll, "多选", enabled = cards.isNotEmpty(), tag = "clipboard-select") {
                    selecting = true; selected = emptySet()
                })
                // Same words as the + button: on a group's tab the new record goes into the group, not fixed.
                addPinned?.let { action -> add(CompactActionMenuEntry(Icons.Default.Edit,
                    (tab as? ClipboardTab.Group)?.let { group -> "新增到「${group.label}」" } ?: "新增固定内容",
                    tag = "clipboard-add-text", onClick = action)) }
                add(CompactActionMenuEntry(Icons.Default.Image, "添加图片", tag = "clipboard-pick", onClick = onPick))
                onRecognizeDocument?.let { action -> add(CompactActionMenuEntry(Icons.Default.DocumentScanner,
                    "拍照识别", tag = "clipboard-ocr", onClick = action)) }
                add(CompactActionMenuEntry(Icons.Default.PhotoLibrary, if (photoAccess) "关闭最近截图" else "启用最近截图",
                    tag = "clipboard-photos", onClick = onPhotoAccess))
                onPullRemote?.let { action -> add(CompactActionMenuEntry(Icons.Default.Sync, "同步", tag = "clipboard-sync", onClick = action)) }
                onManageGroups?.let { action -> add(CompactActionMenuEntry(Icons.Default.Folder, "管理分组",
                    tag = "clipboard-manage-groups") { action(null) }) }
                onOpenSettings?.let { action -> add(CompactActionMenuEntry(Icons.Default.Settings, "剪贴板设置",
                    tag = "clipboard-settings", onClick = action)) }
                closeAfterPaste?.let { on -> add(CompactActionMenuEntry(
                    if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, "上屏后自动关闭剪贴板",
                    tag = "clipboard-close-after-paste") { onCloseAfterPasteChange(!on) }) }
                // Records in a group stay, like fixed ones.
                add(CompactActionMenuEntry(Icons.Default.DeleteOutline, "清空未固定", colors.error,
                    enabled = all.any { it.key !in pins && it.key !in groups.members }, tag = "clipboard-clear") {
                    deleteAnchor = moreAnchor; clearingAll = onClearUnpinned != null
                    deleting = all.filterNot { it.key in pins || it.key in groups.members }
                })
                add(CompactActionMenuEntry(Icons.Default.Close, "关闭菜单", tag = "clipboard-close-menu") {})
            }
            CompactActionMenu(moreAnchor.bounds, actions, onDismiss = { menu = false },
                occupiedBounds = moreAnchor.occupied, preferSide = false, tag = "clipboard-more-menu",
                surfaceTag = "clipboard-board-action-card")
        }
        deleting?.let { records ->
            val imageCount = records.count { it is ClipboardCard.Image }
            // Clearing all counts the whole unfixed history, including pages not read yet.
            val textCount = if (clearingAll && unpinnedTextTotal >= 0) unpinnedTextTotal else records.size - imageCount
            val title = when {
                imageCount == 0 -> "删除 $textCount 条文本记录？"
                textCount == 0 -> "删除 $imageCount 条图片记录？"
                else -> "删除 $textCount 条文本和 $imageCount 条图片记录？"
            }
            val actions = listOf(
                CompactActionMenuEntry(Icons.Default.DeleteOutline, "删除", colors.error, tag = "clipboard-confirm-delete") {
                    val clearAll = clearingAll && onClearUnpinned != null
                    clearingAll = false
                    val keys = records.mapTo(mutableSetOf()) { it.key }
                    removing = removing + keys
                    if (clearAll) onClearUnpinned?.invoke(records) else onRemove(records)
                    onPinsChange(pins - keys)
                    // A deleted record leaves its group.
                    keys.filter { it in groups.members }.takeIf { it.isNotEmpty() }?.let { onAssignGroup(it.toSet(), null) }
                    selected = emptySet(); selecting = false
                },
                CompactActionMenuEntry(Icons.Default.Close, "取消", tag = "clipboard-cancel-delete") {},
            )
            // Same anchored card as the record actions, in the same place, at full touch size.
            if (itemMenu == null) keyboardMenuEntries[0] = actions
            LongPressMenuOverlay(deleteAnchor.bounds, deleteAnchor.occupied, onDismiss = { deleting = null; clearingAll = false },
                menuItems = actions, title = title, focusedIndex = menuFocus.takeIf { keyboard?.active == true },
                highlightCorner = deleteAnchor.corner,
                message = if (imageCount > 0) "只移除剪贴板记录，保留原始图片。" else null,
                tag = "clipboard-delete-menu", surfaceTag = "clipboard-board-action-card")
        }
    }
}

@Composable
private fun CompactClipboardFilter(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isSelected) { if (isSelected) bringIntoView.bringIntoView() }
    Box(modifier.bringIntoViewRequester(bringIntoView).heightIn(min = PanelCategoryHeight).clip(RoundedCornerShape(6.dp))
        .background(if (isSelected) frostedKeyColor(colors.secondaryContainer, colors.onSurface, glass) else Color.Transparent)
        .semantics { selected = isSelected }
        .clickable(role = Role.Tab, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = PanelCategoryVerticalPadding), contentAlignment = Alignment.Center) {
        Text(label, color = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ClipboardRecordCard(
    card: ClipboardCard, pinned: Boolean, chosen: Boolean, selecting: Boolean,
    keyboardFocused: Boolean, previewHeight: Dp?, maxLines: Int = 4,
    /** A kept password not asked to be shown: dots instead of its text (#95). */
    masked: Boolean = false,
    onClick: () -> Unit, onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 点了卡片下面识别出的小片段（邮箱、链接、密码…）：只贴那一段。 */
    onPiece: ((String) -> Unit)? = null,
    /** 列表样式：整行铺满，不画卡片底色和圆角，底下一条细分隔线。 */
    listRow: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val highlighted = chosen || keyboardFocused
    val shape = if (listRow) RoundedCornerShape(8.dp) else RoundedCornerShape(clipboardCardCorner(previewHeight == ClipboardPinnedRowHeight))
    val divider = colors.onSurface.copy(alpha = .10f)
    val oneLine = previewHeight == ClipboardPinnedRowHeight
    val liquid = com.kingzcheung.xime.ui.theme.LiquidGlass.surfacesOn()
    BoxWithConstraints(modifier.fillMaxWidth()
        .then(if (previewHeight != null) Modifier.height(previewHeight) else Modifier)
        .then(if (listRow) Modifier.drawBehind {
                val y = size.height - .5.dp.toPx() / 2f
                drawLine(divider, Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), .5.dp.toPx())
            } else Modifier)
        .clip(shape)
        .then(if (listRow) { if (highlighted) Modifier.background(colors.secondaryContainer.copy(alpha = .6f)) else Modifier }
            else Modifier.background(frostedKeyColor(if (highlighted) colors.secondaryContainer else clipboardCardColor(),
                colors.onSurface, glass)))
        // 黑白主题深色是纯黑底：卡片不铺灰，靠描边看出边界（与设置页卡片的 idleBorderAlpha 一致，#82）。
        // 液态玻璃：没选中的卡片用按键同款细亮边代替淡描边；选中、键盘焦点的 2dp 强调色描边不变。
        .then(if (listRow) Modifier
            else if (!highlighted && liquid) Modifier.liquidGlassEdge(clipboardCardCorner(oneLine))
            else Modifier.border(if (highlighted) 2.dp else .5.dp,
                if (highlighted) colors.primary
                else colors.onSurface.copy(alpha = if (colors.surface.isNearBlack()) idleBorderAlpha(colors.surface) else .08f), shape))
        .semantics { focused = keyboardFocused; if (selecting) selected = chosen }
        .testTag("clipboard-card:${card.key}")
        .combinedClickable(role = if (selecting) Role.Checkbox else Role.Button,
            onClick = onClick, onLongClick = onLongClick,
            onLongClickLabel = if (selecting) "切换选择" else "更多操作")) {
        when (card) {
            is ClipboardCard.Text -> {
                val pieces = if (masked || previewHeight != null || selecting || onPiece == null) emptyList()
                    else remember(card.item.text) { com.kingzcheung.xime.clipboard.ClipboardSmartPieces.pieces(card.item.text) }
                Column(Modifier.align(Alignment.CenterStart).fillMaxWidth()) {
                    Text(if (masked) com.kingzcheung.xime.clipboard.ClipboardLinkChoices.SENSITIVE_MASK else card.item.text,
                        Modifier.fillMaxWidth()
                            .heightIn(min = if (previewHeight == null && pieces.isEmpty()) ClipboardPinnedRowHeight else 0.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(start = if (selecting) 28.dp else if (listRow) 12.dp else 8.dp,
                                end = if (pinned) 26.dp else if (listRow) 12.dp else 8.dp,
                                top = if (oneLine) 0.dp else if (listRow) 11.dp else 8.dp,
                                bottom = if (oneLine) 0.dp else if (pieces.isNotEmpty()) 4.dp else if (listRow) 11.dp else 8.dp),
                        color = if (highlighted) colors.onSecondaryContainer else colors.onSurface,
                        fontSize = 16.sp, lineHeight = 23.sp,
                        maxLines = if (oneLine) 1 else if (previewHeight != null) 3 else maxLines,
                        overflow = TextOverflow.Ellipsis)
                    // 识别出的片段：自动换行排开（窄卡片也看得全），每个按钮写明是什么，长串只显示头尾。
                    if (pieces.isNotEmpty() && onPiece != null) FlowRow(
                        Modifier.fillMaxWidth().padding(start = if (listRow) 12.dp else 8.dp, end = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pieces.forEach { piece ->
                            Row(Modifier.clip(RoundedCornerShape(6.dp))
                                .background(colors.primary.copy(alpha = .10f))
                                .clickable(onClickLabel = "只贴${piece.kind.label}") { onPiece(piece.value) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("clipboard-piece:${card.key}:${piece.kind}"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(piece.kind.label, color = colors.primary, fontSize = 12.sp, lineHeight = 16.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.width(4.dp))
                                Text(com.kingzcheung.xime.clipboard.ClipboardSmartPieces.shortLabel(piece.value),
                                    color = colors.onSurface, fontSize = 13.sp, lineHeight = 17.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            is ClipboardCard.Image -> {
                val context = LocalContext.current
                val density = LocalDensity.current
                val widthPx = constraints.maxWidth.coerceAtLeast(1)
                val heightPx = previewHeight?.let { with(density) { it.roundToPx() } }
                    ?: (widthPx / ClipboardThumbnailAspectRatio).roundToInt()
                val thumbnail = remember(context, card.item.uri, widthPx, heightPx) {
                    ImageRequest.Builder(context).data(card.item.uri)
                        .size(widthPx, heightPx.coerceAtLeast(1)).build()
                }
                AsyncImage(thumbnail, card.item.label, contentScale = ContentScale.Crop,
                    modifier = if (previewHeight == null) Modifier.fillMaxWidth().aspectRatio(ClipboardThumbnailAspectRatio)
                    else Modifier.fillMaxSize())
                Canvas(Modifier.matchParentSize().testTag("clipboard-image-hatch:${card.key}")) {
                    val spacing = 36.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        val start = Offset(x, size.height)
                        val end = Offset(x + size.height, 0f)
                        drawLine(Color.Black.copy(alpha = .14f), start, end, 1.5.dp.toPx())
                        drawLine(Color.White.copy(alpha = .16f), start, end, .5.dp.toPx())
                        x += spacing
                    }
                }
            }
        }
        if (pinned) ClipboardCardBadge(Icons.Default.Lock, "已固定",
            Modifier.align(if (oneLine) Alignment.CenterEnd else Alignment.TopEnd).padding(4.dp), "clipboard-pin:${card.key}")
        if (selecting) {
            ClipboardCardBadge(if (chosen) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                if (chosen) "已选" else "未选", Modifier.align(Alignment.TopStart).padding(4.dp)
                    .semantics { selected = chosen; role = Role.Checkbox }, "clipboard-selection:${card.key}")
        }
    }
}

@Composable
private fun CompactClipboardAction(icon: ImageVector, description: String, onClick: () -> Unit, tag: String,
    enabled: Boolean = true, label: String? = null, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
    Row(modifier.heightIn(min = PanelCategoryHeight).widthIn(min = 28.dp).clip(RoundedCornerShape(6.dp))
        .testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, description, Modifier.size(16.dp), tint = color)
        if (label != null) Text(label, color = color, fontSize = 12.sp, lineHeight = 18.sp,
            maxLines = 1, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun ClipboardCardBadge(icon: ImageVector, description: String, modifier: Modifier, tag: String) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.testTag(tag).clip(RoundedCornerShape(4.dp))
        .background(frostedKeyColor(clipboardCardColor(), colors.onSurface,
            LocalKeyboardInputPreferences.current.frostedGlass)).padding(2.dp)) {
        Icon(icon, description, Modifier.size(16.dp), tint = colors.onSurface)
    }
}

/**
 * 固定内容折叠时，分隔线中间放一个带箭头的标签（“展开 N 条”/“收起”），整条线都可点：
 * 一眼能看出下面还有内容、这里可以展开；展开后箭头朝上，点一下收起。
 */
@androidx.compose.runtime.Composable
private fun PinsFoldDivider(expanded: Boolean, hidden: Int, content: androidx.compose.ui.graphics.Color,
    modifier: Modifier, onToggle: () -> Unit) {
    val line = content.copy(alpha = .22f)
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("clipboard-pins-divider")
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = if (expanded) "收起" else "展开 $hidden 条", onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), thickness = .5.dp, color = line)
        PinsFoldPill(if (expanded) "收起" else "展开 $hidden 条", expanded, content,
            Modifier.padding(horizontal = 8.dp).testTag("clipboard-pins-fold"))
        HorizontalDivider(Modifier.weight(1f), thickness = .5.dp, color = line)
    }
}

/** 固定内容的展开/收起胶囊：分隔线中间和工具栏右上角共用同一个样子。 */
@Composable
private fun PinsFoldPill(label: String, expanded: Boolean, content: Color, modifier: Modifier = Modifier) {
    val line = content.copy(alpha = .22f)
    val tint = content.copy(alpha = .78f)
    Row(modifier.clip(RoundedCornerShape(50)).border(.5.dp, line, RoundedCornerShape(50))
        .padding(start = 10.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
            Modifier.size(18.dp), tint = tint)
    }
}

/** One fixed height for a pinned record: a single line of text, or a thin strip of a picture. */
internal val ClipboardPinnedRowHeight = 40.dp

/**
 * Cards sit just above the panel in the theme's own colors (dark panel → slightly lighter dark
 * card, light panel → slightly darker light card), instead of a fixed mid gray.
 */
@Composable
private fun clipboardCardColor(): Color {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    return androidx.compose.ui.graphics.lerp(colors.surface, colors.onSurface, if (dark) .10f else .06f)
        .copy(alpha = 1f)
}
