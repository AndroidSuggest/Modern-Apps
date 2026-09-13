package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHostView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import com.vayunmathur.launcher.domain.PageCount
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.platform.FolderActions
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.platform.ItemMenuActions
import com.vayunmathur.launcher.platform.ItemMenuUiState
import com.vayunmathur.launcher.platform.WidgetPickerActions
import com.vayunmathur.launcher.platform.WidgetPickerUiState
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.library.ui.Motion
import kotlinx.coroutines.launch

/**
 * The home screen: a pager of cell grids, a hotseat, the app drawer and any open folder, and the
 * drag machinery over the top.
 *
 * Everything is in this one composable's root [HomeGestureRoot] on purpose. The drawer and an
 * open folder are overlays here rather than nav destinations, because that root is what carries
 * the single gesture owner — and a drag can only cross from the drawer to the grid, or out of a
 * folder onto the grid, if all three are inside it. Launcher3 arranges its `DragLayer` the same
 * way and for the same reason.
 *
 * The long-press popups are the exception, and deliberately so: they are `LauncherPopup`s in
 * windows of their own, because a popup that opens *while the finger is still down* cannot share a
 * pointer hierarchy with the gesture that opened it. Their scrim is drawn here, with no pointer
 * handling of its own, and dismissal belongs to the gesture owner — which is what makes one touch
 * dismiss the popup and do nothing else.
 *
 * No `AppScaffold`: a home screen has no top app bar, and the point of the transparent window plus
 * `containerColor` is that nothing opaque is drawn over the wallpaper.
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    actions: HomeActions,
    drawerState: DrawerUiState = DrawerUiState(),
    drawerActions: DrawerActions = DrawerActions.Noop,
    folderActions: FolderActions = FolderActions.Noop,
    onOpenItemMenu: (Long) -> Unit = {},
    itemMenuState: ItemMenuUiState = ItemMenuUiState(),
    itemMenuActions: ItemMenuActions = ItemMenuActions.Noop,
    onOpenSettings: () -> Unit = {},
    onPickWallpaper: () -> Unit = {},
    widgetPickerState: WidgetPickerUiState = WidgetPickerUiState(),
    widgetPickerActions: WidgetPickerActions = WidgetPickerActions.Noop,
    widgetView: (Int) -> AppWidgetHostView? = { null },
    updateWidgetSize: (AppWidgetHostView, Int, Int) -> Unit = { _, _, _ -> },
    initialDrawerOpen: Boolean = false,
    initialOpenFolderId: Long? = null,
) {
    val drag = LocalLauncherDrag.current
    val density = LocalDensity.current
    // The trailing empty page exists only while something is in the air: it is somewhere to drag
    // *to*, and kept permanently it is just a page the user can reach and cannot get rid of.
    val pageCount = PageCount.pageCount(state.pages.keys.maxOrNull() ?: -1, drag.isDragging)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val drawerGridState = rememberLazyGridState()
    // Stable, because the gesture owner is keyed on it: the drawer fills in where its A-Z strip is
    // and what it should do, from inside the very composition the gesture owner wraps.
    val fastScroll = remember { FastScrollStrip() }
    val scope = rememberCoroutineScope()
    var rootBounds by remember { mutableStateOf(Rect.Zero) }

    // Overlay and selection state, all transient: none of it is worth restoring, and the workspace
    // itself lives in the database.
    //
    // The drawer is a progress rather than a boolean because it is dragged: 0 is home, 1 is the
    // drawer, and every value between is a frame of the swipe that is opening it.
    //
    // A plain float rather than an `Animatable`, and that is a performance fix rather than a
    // simplification. `Animatable` has no synchronous setter, so following a finger through one
    // means launching a coroutine per pointer event, each cancelling the last through a mutator
    // mutex: at the rate a touchscreen reports that is an allocation and a cancellation per event on
    // the main thread, and - worse - any delta whose `snapTo` was cancelled before it applied was
    // simply lost. The drawer then travelled a fraction of what the finger did, which is what made
    // the swipe feel far longer than it is.
    val drawer = remember { mutableFloatStateOf(if (initialDrawerOpen) 1f else 0f) }
    // Where the drawer is animating to once the finger has let go, or null while nothing is.
    // A holder, because the settle effect below is the other thing that writes it.
    val drawerSettleState = remember { mutableStateOf<Float?>(null) }
    var drawerSettle by drawerSettleState
    var openFolderId by remember { mutableStateOf(initialOpenFolderId) }
    // The icon the open folder grew out of, so it can shrink back into it.
    var folderAnchor by remember { mutableStateOf(Rect.Zero) }
    val folder = remember { Animatable(if (initialOpenFolderId != null) 1f else 0f) }
    // The item whose popup is up and the icon it is anchored to, or - for a press on bare
    // wallpaper - the point the finger was at.
    var menuItemId by remember { mutableStateOf<Long?>(null) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    var optionsAnchor by remember { mutableStateOf<Rect?>(null) }
    val popup = remember { Animatable(0f) }
    var resizingId by remember { mutableStateOf<Long?>(null) }

    val allItems = state.pages.values.flatten() + state.hotseat
    val openFolder = openFolderId?.let { id -> allItems.firstOrNull { it.id == id } }
    // A folder that collapsed under us - its last child was dragged out - has no item left to
    // render, so the overlay closes itself rather than showing an empty sheet.
    if (openFolderId != null && openFolder == null) openFolderId = null

    val popupOpen = menuItemId != null || optionsAnchor != null

    // Composed from the first frame of the swipe, so it can be seen coming up, but only *reachable*
    // past the halfway point. Derived rather than read straight off the progress: reading
    // `drawer.floatValue` here would recompose this whole screen on every frame of the swipe, where
    // these only change twice.
    //
    // The workspace is still composed underneath an open overlay, so its icons would still be
    // registered as drag sources and a long press over the drawer could pick one up through it; the
    // drawer's own icons have the mirror-image problem while it is barely showing. Overlays consume
    // touches in Launcher3; here the equivalent is that whichever of the two is not in front is
    // un-draggable.
    val drawerVisible by remember { derivedStateOf { drawer.floatValue > 0f } }
    val drawerShown by remember { derivedStateOf { drawer.floatValue >= DRAWER_INTERACTIVE } }
    val overlayOpen = drawerShown || openFolder != null || popupOpen

    fun openFolder(id: Long, anchor: Rect) {
        folderAnchor = anchor
        openFolderId = id
        scope.launch {
            // Snapped first: a folder whose last close was cut short would otherwise open with no
            // animation at all.
            folder.snapTo(0f)
            folder.animateTo(1f, Motion.open(Motion.FolderOpenMillis))
        }
    }

    // Closed in two steps, because the sheet has to stay composed while it shrinks; clearing the id
    // first would take the thing being animated off screen before the animation ran.
    fun closeFolder() {
        scope.launch {
            folder.animateTo(0f, Motion.close(Motion.FolderCloseMillis))
            openFolderId = null
        }
    }

    fun openMenu(id: Long, anchor: Rect) {
        menuAnchor = anchor
        optionsAnchor = null
        menuItemId = id
        onOpenItemMenu(id)
        scope.launch {
            popup.snapTo(0f)
            popup.animateTo(1f, Motion.open(Motion.PopupOpenMillis))
        }
    }

    fun openOptions(at: Offset) {
        menuItemId = null
        optionsAnchor = Rect(at.x, at.y, at.x, at.y)
        scope.launch {
            popup.snapTo(0f)
            popup.animateTo(1f, Motion.open(Motion.PopupOpenMillis))
        }
    }

    fun closePopup() {
        scope.launch {
            popup.animateTo(0f, Motion.close(Motion.PopupCloseMillis))
            menuItemId = null
            optionsAnchor = null
        }
    }

    fun settleDrawer(to: Float) {
        // Blurred while the drawer is up, as Launcher3 blurs its wallpaper behind all-apps. Set on
        // the settle rather than per frame of the swipe: a window-level blur is not free, and
        // changing it sixty times a second is the one way to make it cost something visible.
        actions.setWallpaperBlurred(to > 0f)
        drawerSettle = to
    }

    val drawerSwipe = rememberDrawerSwipe(
        drawer = drawer,
        drawerGridState = drawerGridState,
        // A lambda, read when the gesture asks: the swipe object is remembered once for the life
        // of the screen, so a value would stay at its first-composition value forever.
        rootBounds = { rootBounds },
        density = density,
        canExpandShade = state.canExpandShade,
        onSettleDrawer = ::settleDrawer,
        onTakeBackSettle = { drawerSettle = null },
        onExpandShade = actions::expandNotificationShade,
    )

    // Remembered, all of them, and for the same reason the ones in `HomePage` are: a callable
    // reference to a local function is a fresh object on every recomposition, and these go down into
    // every page and every cell. Handing them a new identity means nothing below can skip, so one
    // recomposition here became three pages of icons recomposing. They capture only state delegates
    // and remembered objects, so remembering them keeps them live.
    val onOpenFolder = remember { { id: Long, anchor: Rect -> openFolder(id, anchor) } }
    val onOpenMenu = remember { { id: Long, anchor: Rect -> openMenu(id, anchor) } }
    val onCloseFolder = remember { { closeFolder() } }
    val onClosePopup = remember { { closePopup() } }
    val onEndResize = remember { { resizingId = null } }

    // Launcher3 offers the handles the moment a widget lands, and only when the widget says it can
    // be resized at all.
    val onWidgetDropped = remember { { id: Long -> resizingId = id } }

    HomeScreenEffects(
        drag = drag,
        pagerState = pagerState,
        rootWidth = rootBounds.width,
        drawer = drawer,
        drawerSettle = drawerSettleState,
        popupOpen = popupOpen,
        onBack = { closePopup() },
    )

    HomeGestureRoot(
        drag = drag,
        verticalSwipe = drawerSwipe,
        fastScroll = fastScroll,
        // Reading the two state holders rather than the `popupOpen` boolean, which is the
        // difference between a live answer and a dead one: this lambda is captured once, by
        // a `pointerInput` keyed on things that never change, so anything it closes over by
        // value stays at its first-composition value for the life of the screen. Capturing
        // the state delegates instead means the read happens when the gesture asks.
        popupOpen = { menuItemId != null || optionsAnchor != null },
        onDragStart = { payload ->
            // An app picked up in the drawer, or a shortcut picked up in a popup, needs the
            // grid it is being dropped onto to be visible, so whatever it came out of gets
            // out of the way the moment the drag begins.
            if (payload.itemId == null) settleDrawer(0f)
            resizingId = null
        },
        onLongPressItem = { payload, anchor ->
            // The popup, for a widget as for anything else. Launcher3 shows the resize frame
            // on *drop*, not on long press - see `getWidgetResizeFrameRunnable` - and going
            // straight to the handles here meant a widget could never be reached by its menu
            // at all.
            payload.itemId?.let { openMenu(it, anchor) }
        },
        onLongPressEmpty = ::openOptions,
        onDismissPopup = onClosePopup,
        onBounds = { rootBounds = it },
    ) {
        HomeWorkspaceColumn(
            state = state,
            actions = actions,
            pagerState = pagerState,
            pageCount = pageCount,
            overlayOpen = overlayOpen,
            drawer = drawer,
            resizingId = resizingId,
            drag = drag,
            onOpenFolder = onOpenFolder,
            onEndResize = onEndResize,
            onWidgetDropped = onWidgetDropped,
            onRemoveItem = { id ->
                resizingId = null
                actions.remove(id)
            },
            widgetView = widgetView,
            updateWidgetSize = updateWidgetSize,
        )

        HomeOverlays(
            popupOpen = popupOpen,
            popup = popup,
            drawerVisible = drawerVisible,
            drawerState = drawerState,
            drawerActions = drawerActions,
            drawerGridState = drawerGridState,
            drawerShown = drawerShown,
            fastScroll = fastScroll,
            drawer = drawer,
            onDismissDrawer = { settleDrawer(0f) },
            openFolder = openFolder,
            folderActions = folderActions,
            showLabels = state.showLabels,
            iconScale = state.iconScale,
            folderAnchor = folderAnchor,
            folder = folder,
            onOpenMenu = onOpenMenu,
            onCloseFolder = onCloseFolder,
            drag = drag,
        )
    }

    HomePopups(
        menuItemId = menuItemId,
        menuAnchor = menuAnchor,
        itemMenuState = itemMenuState,
        itemMenuActions = itemMenuActions,
        popup = popup,
        onClosePopup = onClosePopup,
        optionsAnchor = optionsAnchor,
        onPickWallpaper = onPickWallpaper,
        widgetPickerActions = widgetPickerActions,
        onOpenSettings = onOpenSettings,
        settleDrawer = ::settleDrawer,
        closePopup = ::closePopup,
        widgetPickerState = widgetPickerState,
    )
}
