package com.vayunmathur.auto.platform

import android.util.Log
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import kotlin.concurrent.thread

/** One template action: title plus the app's own click delegate. */
data class HostUiAction(
    val title: String,
    val onClick: () -> Unit,
)

/** One browsable row: title, subtitle lines, and the app's own click. */
data class HostUiRow(
    val title: String,
    val texts: List<String> = emptyList(),
    val browse: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** One grid cell: title, subtitle, and the app's own click. */
data class HostUiGridItem(
    val title: String,
    val text: String? = null,
    val loading: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** Header: title plus its start/end actions. */
data class HostUiHeader(
    val title: String? = null,
    val startAction: HostUiAction? = null,
    val endActions: List<HostUiAction> = emptyList(),
)

/**
 * What a hosted car app is currently publishing, parsed from its template.
 *
 * One parser per template kind below extends the old NavigationTemplate-only
 * parse from `CarAppHost`. Unhandled template kinds log and fall back to
 * [Pane] carrying the template's class name, so the renderer never blanks.
 */
sealed interface HostTemplate {
    /** Legacy Maps-only state, kept until the nav renderer reads [Navigation]. */
    data class LegacyNav(val state: HostNavState) : HostTemplate

    /** A navigation template: cue/road banner, distance, lanes, ETA, actions. */
    data class Navigation(
        val navigating: Boolean = false,
        val loading: Boolean = false,
        val cue: String? = null,
        val road: String? = null,
        val distanceText: String? = null,
        val etaText: String? = null,
        val lanesText: String? = null,
        val header: HostUiHeader? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** A place list (POI): rows with a browsable flag, plus header/actions. */
    data class PlaceList(
        val title: String? = null,
        val rows: List<HostUiRow> = emptyList(),
        val loading: Boolean = false,
        val header: HostUiHeader? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** A generic list (or sectioned lists flattened with headers). */
    data class TemplateList(
        val title: String? = null,
        val sections: List<HostUiSection> = emptyList(),
        val loading: Boolean = false,
        val header: HostUiHeader? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** A grid of cells. */
    data class Grid(
        val title: String? = null,
        val items: List<HostUiGridItem> = emptyList(),
        val loading: Boolean = false,
        val header: HostUiHeader? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** A pane of rows plus a free action row. */
    data class Pane(
        val title: String? = null,
        val rows: List<HostUiRow> = emptyList(),
        val loading: Boolean = false,
        val header: HostUiHeader? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** A search box plus its result list. */
    data class Search(
        val hint: String? = null,
        val initialText: String = "",
        val rows: List<HostUiRow> = emptyList(),
        val loading: Boolean = false,
        val showKeyboardByDefault: Boolean = false,
        val onSearchTextChanged: ((String) -> Unit)? = null,
        val onSearchSubmitted: ((String) -> Unit)? = null,
        val header: HostUiHeader? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** A message with optional icon and actions. */
    data class Message(
        val title: String? = null,
        val message: String? = null,
        val loading: Boolean = false,
        val header: HostUiHeader? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** A tabbed view: 2-4 tabs over one active content template. */
    data class Tabs(
        val tabs: List<HostUiTab> = emptyList(),
        val activeContentId: String? = null,
        val content: HostTemplate? = null,
        val loading: Boolean = false,
        val onTabSelected: ((String) -> Unit)? = null,
    ) : HostTemplate

    /** Media now-playing: host renders transport from the session token. */
    data class MediaPlayback(
        val title: String? = null,
        val loading: Boolean = false,
    ) : HostTemplate

    /** Parked-only sign-in / permissions / long text. */
    data class SignIn(
        val title: String? = null,
        val message: String? = null,
        val loading: Boolean = false,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** In-call controls (experimental dialer). */
    data class InCall(
        val title: String? = null,
        val texts: List<String> = emptyList(),
        val loading: Boolean = false,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate

    /** Telephone dialpad (experimental dialer). */
    data class Keypad(
        val title: String? = null,
        val phoneNumber: String = "",
        val onNumberChanged: ((String) -> Unit)? = null,
        val onPrimaryAction: (() -> Unit)? = null,
        val primaryTitle: String? = null,
    ) : HostTemplate

    /** App map + overlay content (replaces deprecated map templates). */
    data class MapWithContent(
        val content: HostTemplate? = null,
        val actions: List<HostUiAction> = emptyList(),
    ) : HostTemplate
}

/** One tab inside [HostTemplate.Tabs]. */
data class HostUiTab(
    val title: String? = null,
    val contentId: String,
)

/** One named section inside [HostTemplate.TemplateList]. */
data class HostUiSection(
    val header: String? = null,
    val rows: List<HostUiRow> = emptyList(),
)

/** Parses template wrappers into [HostTemplate]. Pure functions, any thread. */
object HostTemplateParsers {
    /** Parses one fetched wrapper; null wrapper means "connected, empty". */
    fun parse(wrapper: TemplateWrapper?): HostTemplate {
        val template = runCatching { wrapper?.template }.getOrNull()
            ?: return HostTemplate.Pane(loading = true)
        return when (template) {
            is NavigationTemplate -> parseNavigation(template)
            is PlaceListMapTemplate -> parsePlaceList(template)
            is ListTemplate -> parseList(template)
            is GridTemplate -> parseGrid(template)
            is PaneTemplate -> parsePane(template)
            is SearchTemplate -> parseSearch(template)
            is MessageTemplate -> parseMessage(template)
            is LongMessageTemplate -> parseLongMessage(template)
            is androidx.car.app.model.SectionedItemTemplate -> parseSectionedItem(template)
            is androidx.car.app.model.TabTemplate -> parseTab(template)
            is androidx.car.app.media.model.MediaPlaybackTemplate -> parseMediaPlayback(template)
            is androidx.car.app.model.signin.SignInTemplate -> parseSignIn(template)
            is androidx.car.app.dialer.InCallTemplate -> parseInCall(template)
            is androidx.car.app.dialer.TelephoneKeypadTemplate -> parseKeypad(template)
            is androidx.car.app.navigation.model.MapWithContentTemplate -> parseMapWithContent(template)
            // Deprecated map templates: route through the same content parsers.
            is androidx.car.app.navigation.model.MapTemplate ->
                runCatching { template.pane } .getOrNull()?.let { parsePane(PaneTemplate.Builder(it).build()) }
                    ?: runCatching { template.itemList }.getOrNull()?.let {
                        parseList(ListTemplate.Builder().setSingleList(it).build())
                    }
                    ?: HostTemplate.Pane(title = "Map")
            is androidx.car.app.navigation.model.PlaceListNavigationTemplate ->
                HostTemplate.PlaceList(
                    rows = itemListRows(runCatching { template.itemList }.getOrNull()),
                    loading = runCatching { template.isLoading }.getOrDefault(false),
                    actions = actionStripActions(runCatching { template.actionStrip }.getOrNull()),
                )
            is androidx.car.app.navigation.model.RoutePreviewNavigationTemplate ->
                HostTemplate.PlaceList(
                    rows = itemListRows(runCatching { template.itemList }.getOrNull()),
                    loading = runCatching { template.isLoading }.getOrDefault(false),
                    actions = actionStripActions(runCatching { template.actionStrip }.getOrNull()),
                )
            else -> {
                Log.w(TAG, "unhandled template ${template.javaClass.simpleName}; pane fallback")
                HostTemplate.Pane(title = template.javaClass.simpleName)
            }
        }
    }

    private fun parseSectionedItem(template: androidx.car.app.model.SectionedItemTemplate): HostTemplate.TemplateList {
        val sections = mutableListOf<HostUiSection>()
        runCatching { template.sections }.getOrNull().orEmpty().forEach { section ->
            val items = runCatching { section.items }.getOrNull().orEmpty()
            val rows = items.mapNotNull { item ->
                when (item) {
                    is Row -> parseRow(item)
                    is androidx.car.app.messaging.model.ConversationItem -> parseConversation(item)
                    is androidx.car.app.model.GridItem -> null // grid-in-section: title row
                    else -> null
                }
            }
            val header = when (section) {
                is androidx.car.app.model.RowSection ->
                    carText(runCatching { section.header }.getOrNull()?.title)
                is androidx.car.app.model.GridSection ->
                    carText(runCatching { section.header }.getOrNull()?.title)
                else -> carText(runCatching {
                    section.javaClass.getMethod("getHeader").invoke(section)
                        as? androidx.car.app.model.CarText
                }.getOrNull())
            } ?: section.javaClass.simpleName.removeSuffix("Section")
            sections += HostUiSection(header = header, rows = rows)
        }
        return HostTemplate.TemplateList(
            title = carText(runCatching { template.header }.getOrNull()?.title),
            sections = sections,
            loading = runCatching { template.isLoading }.getOrDefault(false),
            actions = listActions(runCatching { template.actions }.getOrNull().orEmpty()),
        )
    }

    private fun parseConversation(item: androidx.car.app.messaging.model.ConversationItem): HostUiRow? {
        val title = carText(runCatching { item.title }.getOrNull()) ?: return null
        val lastBody = runCatching { item.messages }.getOrNull()?.lastOrNull()?.let { msg ->
            carText(runCatching { msg.body }.getOrNull())
        }
        val delegate = runCatching { item.conversationCallbackDelegate }.getOrNull()
        return HostUiRow(
            title = title,
            texts = listOfNotNull(lastBody),
            browse = true,
            onClick = delegate?.let { d ->
                {
                    thread(name = "ma-auto-carhost-read", isDaemon = true) {
                        runCatching { d.sendMarkAsRead(HostClickCallback) }
                    }
                }
            },
        )
    }

    private fun parseTab(template: androidx.car.app.model.TabTemplate): HostTemplate.Tabs {
        val delegate = runCatching { template.tabCallbackDelegate }.getOrNull()
        val tabs = runCatching { template.tabs }.getOrNull().orEmpty().map { tab ->
            HostUiTab(
                title = carText(runCatching { tab.title }.getOrNull()),
                contentId = runCatching { tab.contentId }.getOrNull().orEmpty(),
            )
        }
        val activeId = runCatching { template.activeTabContentId }.getOrNull()
        val content = runCatching { template.tabContents }.getOrNull()?.let { contents ->
            runCatching { contents.template }.getOrNull()?.let { inner ->
                runCatching { parseInner(inner) }.getOrNull()
            }
        }
        return HostTemplate.Tabs(
            tabs = tabs,
            activeContentId = activeId,
            content = content,
            loading = runCatching { template.isLoading }.getOrDefault(false),
            onTabSelected = delegate?.let { d ->
                { contentId ->
                    thread(name = "ma-auto-carhost-tab", isDaemon = true) {
                        runCatching { d.sendTabSelected(contentId, HostClickCallback) }
                            .onFailure { Log.w(TAG, "tab select failed", it) }
                    }
                }
            },
        )
    }

    private fun parseInner(template: androidx.car.app.model.Template): HostTemplate =
        when (template) {
            is ListTemplate -> parseList(template)
            is GridTemplate -> parseGrid(template)
            is PaneTemplate -> parsePane(template)
            is MessageTemplate -> parseMessage(template)
            is SearchTemplate -> parseSearch(template)
            is androidx.car.app.model.SectionedItemTemplate -> parseSectionedItem(template)
            is NavigationTemplate -> parseNavigation(template)
            else -> HostTemplate.Pane(title = template.javaClass.simpleName)
        }

    private fun parseMediaPlayback(template: androidx.car.app.media.model.MediaPlaybackTemplate): HostTemplate.MediaPlayback =
        HostTemplate.MediaPlayback(
            title = carText(runCatching { template.header }.getOrNull()?.title),
        )

    private fun parseSignIn(template: androidx.car.app.model.signin.SignInTemplate): HostTemplate.SignIn =
        HostTemplate.SignIn(
            title = carText(runCatching { template.title }.getOrNull()),
            message = carText(runCatching { template.instructions }.getOrNull()),
            loading = runCatching { template.isLoading }.getOrDefault(false),
            actions = listActions(runCatching { template.actions }.getOrNull().orEmpty()) +
                actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )

    @OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
    private fun parseInCall(template: androidx.car.app.dialer.InCallTemplate): HostTemplate.InCall =
        HostTemplate.InCall(
            title = carText(runCatching { template.title }.getOrNull()),
            texts = runCatching { template.texts }.getOrNull().orEmpty().mapNotNull { carText(it) },
            loading = runCatching { template.isLoading }.getOrDefault(false),
            actions = listActions(runCatching { template.actions }.getOrNull().orEmpty()),
        )

    @OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
    private fun parseKeypad(template: androidx.car.app.dialer.TelephoneKeypadTemplate): HostTemplate.Keypad {
        val primary = runCatching { template.primaryAction }.getOrNull()
        val primaryDelegate = primary?.let { runCatching { it.onClickDelegate }.getOrNull() }
        return HostTemplate.Keypad(
            title = carText(runCatching { template.header }.getOrNull()?.title),
            phoneNumber = runCatching { template.phoneNumber }.getOrNull().orEmpty(),
            onPrimaryAction = primaryDelegate?.let { d ->
                {
                    thread(name = "ma-auto-carhost-dial", isDaemon = true) {
                        runCatching { d.sendClick(HostClickCallback) }
                    }
                }
            },
            primaryTitle = carText(primary?.let { runCatching { it.title }.getOrNull() }),
        )
    }

    private fun parseMapWithContent(template: androidx.car.app.navigation.model.MapWithContentTemplate): HostTemplate.MapWithContent =
        HostTemplate.MapWithContent(
            content = runCatching { template.contentTemplate }.getOrNull()
                ?.let { runCatching { parseInner(it) }.getOrNull() },
            actions = actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )

    fun parseNavigation(template: NavigationTemplate): HostTemplate.Navigation {
        val routing = runCatching { template.navigationInfo }.getOrNull() as? RoutingInfo
        val step = routing?.currentStep
        val loading = routing?.isLoading == true
        val navigating = routing != null && !loading
        val lanesText = step?.lanes
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("  ") { lane -> laneArrows(lane.directions) }
            ?.takeIf { it.isNotBlank() }
        return HostTemplate.Navigation(
            navigating = navigating,
            loading = loading,
            cue = carText(step?.cue),
            road = carText(step?.road),
            distanceText = routing?.currentDistance?.let { formatDistance(it.displayDistance, it.displayUnit) },
            etaText = runCatching { template.destinationTravelEstimate }.getOrNull()?.let { formatEstimate(it) },
            lanesText = lanesText,
            actions = actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )
    }

    private fun parsePlaceList(template: PlaceListMapTemplate): HostTemplate.PlaceList =
        HostTemplate.PlaceList(
            title = carText(runCatching { template.title }.getOrNull()),
            rows = itemListRows(runCatching { template.itemList }.getOrNull()),
            loading = runCatching { template.isLoading }.getOrDefault(false),
            actions = actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )

    private fun parseList(template: ListTemplate): HostTemplate.TemplateList {
        val sections = mutableListOf<HostUiSection>()
        runCatching { template.singleList }.getOrNull()?.let { list ->
            sections += HostUiSection(rows = itemListRows(list))
        }
        runCatching { template.sectionedLists }.getOrNull().orEmpty().forEach { section ->
            sections += parseSection(section)
        }
        return HostTemplate.TemplateList(
            title = carText(runCatching { template.title }.getOrNull()),
            sections = sections,
            loading = runCatching { template.isLoading }.getOrDefault(false),
            actions = listActions(runCatching { template.actions }.getOrNull().orEmpty()) +
                actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )
    }

    private fun parseSection(section: SectionedItemList): HostUiSection =
        HostUiSection(
            header = carText(runCatching { section.header }.getOrNull()),
            rows = itemListRows(runCatching { section.itemList }.getOrNull()),
        )

    private fun parseGrid(template: GridTemplate): HostTemplate.Grid =
        HostTemplate.Grid(
            title = carText(runCatching { template.title }.getOrNull()),
            items = gridItems(runCatching { template.singleList }.getOrNull()),
            loading = runCatching { template.isLoading }.getOrDefault(false),
            actions = listActions(runCatching { template.actions }.getOrNull().orEmpty()) +
                actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )

    private fun parsePane(template: PaneTemplate): HostTemplate.Pane {
        val pane = runCatching { template.pane }.getOrNull()
        return HostTemplate.Pane(
            title = carText(runCatching { template.title }.getOrNull()),
            rows = paneRows(pane),
            loading = runCatching { pane?.isLoading }.getOrDefault(false) == true,
            actions = paneActions(pane) +
                actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )
    }

    private fun parseSearch(template: SearchTemplate): HostTemplate.Search {
        val delegate = runCatching { template.searchCallbackDelegate }.getOrNull()
        return HostTemplate.Search(
            hint = runCatching { template.searchHint }.getOrNull(),
            initialText = runCatching { template.initialSearchText }.getOrNull().orEmpty(),
            rows = itemListRows(runCatching { template.itemList }.getOrNull()),
            loading = runCatching { template.isLoading }.getOrDefault(false),
            showKeyboardByDefault = runCatching { template.isShowKeyboardByDefault }.getOrDefault(false),
            onSearchTextChanged = delegate?.let { d ->
                { text ->
                    thread(name = "ma-auto-carhost-search", isDaemon = true) {
                        runCatching {
                            d.sendSearchTextChanged(text, HostClickCallback)
                        }.onFailure { Log.w(TAG, "search text change failed", it) }
                    }
                }
            },
            onSearchSubmitted = delegate?.let { d ->
                { text ->
                    thread(name = "ma-auto-carhost-search", isDaemon = true) {
                        runCatching {
                            d.sendSearchSubmitted(text, HostClickCallback)
                        }.onFailure { Log.w(TAG, "search submit failed", it) }
                    }
                }
            },
            actions = actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )
    }

    private fun parseMessage(template: MessageTemplate): HostTemplate.Message =
        HostTemplate.Message(
            title = carText(runCatching { template.title }.getOrNull()),
            message = carText(runCatching { template.message }.getOrNull()),
            loading = runCatching { template.isLoading }.getOrDefault(false),
            actions = listActions(runCatching { template.actions }.getOrNull().orEmpty()) +
                actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )

    private fun parseLongMessage(template: LongMessageTemplate): HostTemplate.Message =
        HostTemplate.Message(
            title = carText(runCatching { template.title }.getOrNull()),
            message = carText(runCatching { template.message }.getOrNull()),
            actions = listActions(runCatching { template.actions }.getOrNull().orEmpty()) +
                actionStripActions(runCatching { template.actionStrip }.getOrNull()),
        )

    private fun itemListRows(list: ItemList?): List<HostUiRow> {
        val items = runCatching { list?.items }.getOrNull().orEmpty()
        return items.mapNotNull { item ->
            val row = item as? Row ?: return@mapNotNull null
            parseRow(row)
        }
    }

    private fun parseRow(row: Row): HostUiRow? {
        val title = carText(runCatching { row.title }.getOrNull()) ?: return null
        val texts = runCatching { row.texts }.getOrNull().orEmpty().mapNotNull { carText(it) }
        val delegate = runCatching { row.onClickDelegate }.getOrNull()
        return HostUiRow(
            title = title,
            texts = texts,
            browse = runCatching { row.isBrowsable }.getOrDefault(false),
            onClick = delegate?.let { d ->
                {
                    thread(name = "ma-auto-carhost-click", isDaemon = true) {
                        runCatching { d.sendClick(HostClickCallback) }
                            .onFailure { Log.w(TAG, "row click failed: $title", it) }
                    }
                }
            },
        )
    }

    private fun gridItems(list: ItemList?): List<HostUiGridItem> {
        val items = runCatching { list?.items }.getOrNull().orEmpty()
        return items.mapNotNull { item ->
            val grid = item as? GridItem ?: return@mapNotNull null
            val title = carText(runCatching { grid.title }.getOrNull()) ?: return@mapNotNull null
            val delegate = runCatching { grid.onClickDelegate }.getOrNull()
            HostUiGridItem(
                title = title,
                text = carText(runCatching { grid.text }.getOrNull()),
                loading = runCatching { grid.isLoading }.getOrDefault(false),
                onClick = delegate?.let { d ->
                    {
                        thread(name = "ma-auto-carhost-click", isDaemon = true) {
                            runCatching { d.sendClick(HostClickCallback) }
                                .onFailure { Log.w(TAG, "grid click failed: $title", it) }
                        }
                    }
                },
            )
        }
    }

    private fun paneRows(pane: Pane?): List<HostUiRow> =
        runCatching { pane?.rows }.getOrNull().orEmpty().mapNotNull { parseRow(it) }

    private fun paneActions(pane: Pane?): List<HostUiAction> =
        listActions(runCatching { pane?.actions }.getOrNull().orEmpty())

    private fun listActions(actions: List<Action>): List<HostUiAction> =
        actions.mapNotNull { parseAction(it) }

    private fun actionStripActions(strip: ActionStrip?): List<HostUiAction> =
        runCatching { strip?.actions }.getOrNull().orEmpty().mapNotNull { parseAction(it) }

    private fun parseAction(action: Action): HostUiAction? {
        val title = carText(runCatching { action.title }.getOrNull()) ?: return null
        val delegate = runCatching { action.onClickDelegate }.getOrNull() ?: return null
        return HostUiAction(title) {
            thread(name = "ma-auto-carhost-click", isDaemon = true) {
                runCatching { delegate.sendClick(HostClickCallback) }
                    .onFailure { Log.w(TAG, "action click failed: $title", it) }
            }
        }
    }

    @Suppress("unused")
    private fun parseHeader(header: Header?): HostUiHeader? {
        if (header == null) return null
        return HostUiHeader(
            title = carText(runCatching { header.title }.getOrNull()),
            startAction = runCatching { header.startHeaderAction }.getOrNull()?.let { parseAction(it) },
            endActions = runCatching { header.endHeaderActions }.getOrNull().orEmpty().mapNotNull { parseAction(it) },
        )
    }

    private fun carText(text: CarText?): String? = runCatching {
        text?.toCharSequence()?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun laneArrows(directions: List<LaneDirection>): String = directions.joinToString("") { dir ->
        when (runCatching { dir.shape }.getOrNull()) {
            LaneDirection.SHAPE_STRAIGHT -> STRAIGHT_ARROW
            LaneDirection.SHAPE_SLIGHT_LEFT -> SLIGHT_LEFT_ARROW
            LaneDirection.SHAPE_SLIGHT_RIGHT -> SLIGHT_RIGHT_ARROW
            LaneDirection.SHAPE_NORMAL_LEFT -> LEFT_ARROW
            LaneDirection.SHAPE_NORMAL_RIGHT -> RIGHT_ARROW
            LaneDirection.SHAPE_SHARP_LEFT -> SHARP_LEFT_ARROW
            LaneDirection.SHAPE_SHARP_RIGHT -> SHARP_RIGHT_ARROW
            LaneDirection.SHAPE_U_TURN_LEFT -> UTURN_LEFT_ARROW
            LaneDirection.SHAPE_U_TURN_RIGHT -> UTURN_RIGHT_ARROW
            else -> UNKNOWN_ARROW
        }
    }

    private fun formatDistance(display: Double, unit: Int): String = when (unit) {
        Distance.UNIT_METERS -> "${display.toInt()} m"
        Distance.UNIT_KILOMETERS,
        Distance.UNIT_KILOMETERS_P1,
        -> "${((display * 10).toInt() / 10.0)} km"
        Distance.UNIT_MILES,
        Distance.UNIT_MILES_P1,
        -> "${((display * 10).toInt() / 10.0)} mi"
        Distance.UNIT_FEET -> "${display.toInt()} ft"
        else -> "${display.toInt()} m"
    }

    private fun formatEstimate(estimate: TravelEstimate): String? {
        val remaining = runCatching { estimate.remainingTimeSeconds }.getOrNull() ?: return null
        if (remaining <= 0 || remaining == Long.MAX_VALUE) return null
        val minutes = (remaining / 60).toInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private const val TAG = "MaAuto.HostTemplates"

    private const val STRAIGHT_ARROW = "↑"
    private const val SLIGHT_LEFT_ARROW = "⬉"
    private const val SLIGHT_RIGHT_ARROW = "⬊"
    private const val LEFT_ARROW = "←"
    private const val RIGHT_ARROW = "→"
    private const val SHARP_LEFT_ARROW = "↰"
    private const val SHARP_RIGHT_ARROW = "↱"
    private const val UTURN_LEFT_ARROW = "↩"
    private const val UTURN_RIGHT_ARROW = "↪"
    private const val UNKNOWN_ARROW = "•"
}

/**
 * App-side click callback: the delegate reports back into the app's own
 * dispatch, never the host binder one. Shared because every click site needs
 * the same no-op — matching the old `CarAppHost.parseTemplate` behavior.
 */
private object HostClickCallback : OnDoneCallback

/** One template action (Search, End navigation): title plus the app's own click. */
data class HostAction(
    val title: String,
    val onClick: () -> Unit,
)

/**
 * Legacy Maps-only template state, kept until the nav renderer reads the
 * generic [HostTemplate.Navigation] directly.
 *
 * `mapsPresent=false` means the service itself is missing (empty tile);
 * `connected=false` means the bind/handshake failed (tile until the next
 * session). Otherwise the card shows the app's map surface plus these fields.
 * Actions ride the app's own `OnClickDelegate`.
 */
data class HostNavState(
    val mapsPresent: Boolean = true,
    val connected: Boolean = true,
    val navigating: Boolean = false,
    val loading: Boolean = false,
    val cue: String? = null,
    val road: String? = null,
    val distanceText: String? = null,
    val etaText: String? = null,
    val lanesText: String? = null,
    val actions: List<HostAction> = emptyList(),
)
