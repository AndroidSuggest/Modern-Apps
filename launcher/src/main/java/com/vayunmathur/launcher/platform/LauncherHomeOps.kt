package com.vayunmathur.launcher.platform

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

// ------------------------------------------------------------------
// HomeActions / DrawerActions / FolderActions / IconLoader bodies — moved from
// LauncherViewModel.kt (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal fun LauncherViewModel.launchImpl(item: WorkspaceItem, left: Int, top: Int, right: Int, bottom: Int) {
    if (item.hidden) {
        AppMessages.show("${item.label} is not available right now")
        return
    }
    val bounds = Rect(left, top, right, bottom)
    val options = bridge?.launchAnimationOptions(left, top, right, bottom)
    val shortcutId = item.shortcutId
    val key = item.key ?: return
    if (shortcutId != null) {
        val user = appsMonitor.userFor(key.profileSerial)
        val shortcut = appsMonitor.shortcuts(key.componentName.packageName, user)
            .firstOrNull { it.id == shortcutId }
        if (shortcut != null && appsMonitor.startShortcut(shortcut, bounds, options)) return
    }
    val entry = appsMonitor.entryFor(key.componentName, key.profileSerial)
    if (entry == null || !appsMonitor.launch(entry, bounds, options)) {
        AppMessages.show("Could not open ${item.label}")
        return
    }
    countLaunch(key)
}

internal fun LauncherViewModel.commitMoveImpl(
    id: Long,
    container: ContainerRef,
    screen: Int,
    rect: CellRect,
    rank: Int,
    displaced: Map<Long, CellRect>,
) {
    viewModelScope.launch {
        val from = savedItems.firstOrNull { it.id == id }?.container
        if (container is ContainerRef.Hotseat) {
            repository.moveToHotseat(id, rank, homeState.value.grid)
        } else {
            repository.moveTo(id, container, screen, rect, rank, displaced)
        }
        // Dragging the second-to-last child out of a folder has to take the folder with it,
        // and the move above is what emptied it - so the collapse is checked afterwards
        // rather than being the mover's business.
        if (from is ContainerRef.Folder && from != container) {
            repository.collapseFolderIfNeeded(from.id)
        }
    }
}

internal fun LauncherViewModel.mergeIntoFolderImpl(targetId: Long, draggedId: Long) {
    viewModelScope.launch {
        val target = savedItems.firstOrNull { it.id == targetId } ?: return@launch
        if (target.itemType == LauncherItemType.FOLDER) {
            repository.addToFolder(targetId, draggedId)
        } else {
            repository.createFolder(targetId, draggedId)
        }
    }
}

internal fun LauncherViewModel.addPendingToHomeImpl(
    key: ComponentKey,
    screen: Int,
    rect: CellRect,
    displaced: Map<Long, CellRect>,
) {
    viewModelScope.launch {
        val id = repository.upsert(entityForKeyImpl(key))
        repository.moveTo(id, ContainerRef.Desktop, screen, rect, displaced = displaced)
    }
}

internal fun LauncherViewModel.addPendingToHotseatImpl(key: ComponentKey, slot: Int) {
    viewModelScope.launch {
        repository.moveToHotseat(
            repository.upsert(entityForKeyImpl(key)),
            slot,
            homeState.value.grid,
        )
    }
}

internal fun LauncherViewModel.setWallpaperBlurredImpl(blurred: Boolean) {
    bridge?.setWallpaperBlurRadius(if (blurred) WALLPAPER_BLUR_PX else 0)
}

internal fun LauncherViewModel.expandNotificationShadeImpl() {
    if (!privilege.expandNotificationShade()) {
        AppMessages.show("Could not open the notification shade")
    }
}

internal fun LauncherViewModel.removeImpl(id: Long) {
    viewModelScope.launch {
        repository.remove(id)?.let(widgets::release)
    }
}

internal fun LauncherViewModel.resizeItemImpl(id: Long, rect: CellRect, displaced: Map<Long, CellRect>) {
    viewModelScope.launch { repository.resizeTo(id, rect, displaced) }
}

internal fun LauncherViewModel.uninstallItemImpl(id: Long) {
    val entity = savedItems.firstOrNull { it.id == id } ?: return
    val packageName = entity.packageName ?: return
    bridge?.requestUninstall(packageName)
}

internal fun LauncherViewModel.openItemInfoImpl(id: Long) {
    val entity = savedItems.firstOrNull { it.id == id } ?: return
    val className = entity.className ?: return
    val component = ComponentName(entity.packageName.orEmpty(), className)
    val entry = appsMonitor.entryFor(component, entity.profileSerial) ?: return
    appsMonitor.startAppDetails(entry, null)
}

internal fun LauncherViewModel.launchAppImpl(key: ComponentKey, left: Int, top: Int, right: Int, bottom: Int) {
    val entry = appsMonitor.entryFor(key.componentName, key.profileSerial) ?: return
    val options = bridge?.launchAnimationOptions(left, top, right, bottom)
    if (!appsMonitor.launch(entry, Rect(left, top, right, bottom), options)) {
        AppMessages.show("Could not open ${entry.label}")
        return
    }
    countLaunch(key)
}

internal fun LauncherViewModel.setWorkPausedImpl(paused: Boolean) {
    val workApp = appsMonitor.apps.value.firstOrNull { it.isWorkProfile } ?: return
    if (!privilege.setQuietMode(workApp.user, paused)) {
        AppMessages.show("Could not change the work profile")
        return
    }
    drawerState.value = drawerState.value.copy(workPaused = paused)
}

internal fun LauncherViewModel.renameImpl(id: Long, title: String) {
    viewModelScope.launch { repository.setTitle(id, title) }
}

internal fun LauncherViewModel.launchChildImpl(
    item: WorkspaceItem,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
) = launch(item, left, top, right, bottom)

internal fun LauncherViewModel.reorderInFolderImpl(folderId: Long, itemId: Long, rank: Int) {
    viewModelScope.launch { repository.reorderInFolder(folderId, itemId, rank) }
}

internal fun LauncherViewModel.appIconImpl(key: ComponentKey): ImageBitmap? =
    iconCache.get(key) { appsMonitor.icon(key.componentName, key.profileSerial, densityDpi) }

internal fun LauncherViewModel.shortcutIconImpl(
    packageName: String,
    shortcutId: String,
    profileSerial: Long,
): ImageBitmap? {
    val user = appsMonitor.userFor(profileSerial)
    val shortcut = appsMonitor.shortcuts(packageName, user).firstOrNull { it.id == shortcutId }
        ?: return null
    // Keyed on the shortcut id rather than an activity, so two shortcuts from the same
    // app do not share one cache entry.
    return iconCache.get(ComponentKey(ComponentName(packageName, shortcutId), profileSerial)) {
        appsMonitor.shortcutIcon(shortcut, densityDpi)
    }
}

internal fun LauncherViewModel.widgetPreviewImpl(provider: String, profileSerial: Long): ImageBitmap? {
    val component = widgets.unflatten(provider) ?: return null
    val info = widgets.providers(appsMonitor.userFor(profileSerial))
        .firstOrNull { it.provider == component } ?: return null
    return iconCache.get(ComponentKey(component, profileSerial)) {
        // Providers that ship only a previewLayout have no preview image, and there is
        // nothing to render one into here - those fall back to a generic icon.
        runCatching { info.loadPreviewImage(getApplication(), densityDpi) }.getOrNull()
            ?: runCatching { info.loadIcon(getApplication(), densityDpi) }.getOrNull()
    }
}
