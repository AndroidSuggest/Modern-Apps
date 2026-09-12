package com.vayunmathur.launcher.platform

import android.app.Application
import android.appwidget.AppWidgetHostView
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.toRaw
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

/**
 * The widget-picker and hosted-widget half of [LauncherViewModel], as `internal`
 * extensions so LauncherViewModel.kt stays under the FileLength limit. The
 * `WidgetPickerActions` overrides stay on the ViewModel and delegate here.
 */

/** Nominal cell size used only to turn a provider's minWidth/Height into a span. */
internal const val CELL_WIDTH_DP = 72
internal const val CELL_HEIGHT_DP = 88

/**
 * Enumerates every installed provider, which queries the package manager — so it happens when
 * the picker opens rather than being kept live in the workspace state.
 */
internal fun LauncherViewModel.loadWidgetPicker() {
    _widgetPicker.value = _widgetPicker.value.copy(loading = true)
    val spec = _home.value.grid
    val groups = appsMonitor.apps.value
        .map { it.profileSerial }
        .distinct()
        .flatMap { serial ->
            widgets.providers(appsMonitor.userFor(serial)).map { info ->
                val (spanX, spanY) = widgets.spanFor(info, CELL_WIDTH_DP, CELL_HEIGHT_DP)
                val label = runCatching { info.loadLabel(getApplication<Application>().packageManager) }
                    .getOrNull().orEmpty()
                val appLabel = appsMonitor.apps.value
                    .firstOrNull { it.componentName.packageName == info.provider.packageName }
                    ?.label
                    ?: info.provider.packageName
                appLabel to WidgetEntry(
                    provider = info.provider.flattenToString(),
                    label = label,
                    description = runCatching {
                        info.loadDescription(getApplication())?.toString()
                    }.getOrNull().orEmpty(),
                    spanX = spanX.coerceAtMost(spec.columns),
                    spanY = spanY.coerceAtMost(spec.rows),
                    profileSerial = serial,
                )
            }
        }
        .groupBy({ it.first }, { it.second })
        .map { (appLabel, entries) -> WidgetGroup(appLabel, entries.sortedBy { it.label }) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appLabel })

    _widgetPicker.value = _widgetPicker.value.copy(loading = false)
    applyWidgetQuery(_widgetPicker.value.query, groups)
}

internal fun LauncherViewModel.applyWidgetQuery(query: String, source: List<WidgetGroup>? = null) {
    if (source != null) allWidgetGroups = source
    val trimmed = query.trim()
    val filtered = if (trimmed.isEmpty()) {
        allWidgetGroups
    } else {
        allWidgetGroups.mapNotNull { group ->
            if (group.appLabel.contains(trimmed, ignoreCase = true)) return@mapNotNull group
            val matches = group.widgets.filter { it.label.contains(trimmed, ignoreCase = true) }
            if (matches.isEmpty()) null else group.copy(widgets = matches)
        }
    }
    _widgetPicker.value = _widgetPicker.value.copy(query = query, groups = filtered)
}

internal fun LauncherViewModel.addWidgetEntry(entry: WidgetEntry) {
    val bridge = bridge ?: return
    val component = widgets.unflatten(entry.provider) ?: return
    val info = widgets.providers(appsMonitor.userFor(entry.profileSerial))
        .firstOrNull { it.provider == component }
    if (info == null) {
        AppMessages.show("${entry.label} is no longer available")
        return
    }
    // Closed before the bind flow starts, not after it finishes: the consent dialog is another
    // activity, and a sheet still up behind it would be what the user comes back to.
    closeWidgetPicker()
    widgets.add(
        provider = info,
        bridge = bridge,
        profileSerial = entry.profileSerial,
        onBound = { appWidgetId ->
            viewModelScope.launch {
                repository.addToFirstVacantCell(
                    _home.value.grid,
                    LauncherItemEntity(
                        itemType = LauncherItemType.APPWIDGET,
                        containerId = ContainerRef.Desktop.toRaw(),
                        spanX = entry.spanX,
                        spanY = entry.spanY,
                        title = entry.label,
                        packageName = component.packageName,
                        className = component.className,
                        profileSerial = entry.profileSerial,
                        appWidgetId = appWidgetId,
                        appWidgetProvider = entry.provider,
                    ),
                )
            }
        },
        onCancelled = { AppMessages.show("${entry.label} was not added") },
    )
}

/** The hosted view for a placed widget, or null when the provider has gone away. */
internal fun LauncherViewModel.hostedWidgetView(appWidgetId: Int): AppWidgetHostView? {
    val info = widgets.providerInfo(appWidgetId) ?: return null
    return runCatching { widgets.createView(appWidgetId, info) }.getOrNull()
}
