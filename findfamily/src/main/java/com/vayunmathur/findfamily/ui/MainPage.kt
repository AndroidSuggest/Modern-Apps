package com.vayunmathur.findfamily.ui

import com.vayunmathur.library.util.localizedAmPmMarker
import kotlinx.datetime.format.DateTimeFormat
import com.vayunmathur.library.util.DateNameStyle
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.BottomSheetDefaults
import com.vayunmathur.library.ui.BottomSheetScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.FloatingActionButtonMenu
import com.vayunmathur.library.ui.FloatingActionButtonMenuItem
import com.vayunmathur.library.ui.HistoryScrubberCard
import com.vayunmathur.library.ui.HistoryStep
import com.vayunmathur.library.ui.rememberHistoryScrubberState
import com.vayunmathur.library.ui.IconLink
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.dynamicLightColorScheme
import com.vayunmathur.library.ui.rememberBottomSheetScaffoldState
import com.vayunmathur.library.ui.rememberSliderState
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.findfamily.ui.dialogs.interactionSourceClickable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.toGeoPoint
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.findfamily.ui.dialogs.SecurityCodeDialog
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.FamilyListUiState
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.PersonUiState
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.findfamily.util.UwbSessionManager
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.IconVerify
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.formatSpeed
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import com.vayunmathur.library.util.localizedMonthNames
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Peek height with the sheet collapsed — sits a bit higher so more of the
// family list is visible up front while keeping the map usable.
private val SheetPeekHeight = 200.dp
// Compact peek used in history mode: just the contact's name.
private val HistoryPeekHeight = 84.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainPage(
    platform: Platform,
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    initialUserId: Long? = null,
    initialWaypointId: Long? = null
) {
    // Mirror the original `remember(initialUserId)` behaviour: apply the
    // navigation-supplied selection whenever it changes.
    LaunchedEffect(initialUserId, initialWaypointId) {
        ffViewModel.applyInitialSelection(initialUserId, initialWaypointId)
    }

    val selectedUserId by ffViewModel.selectedUserId.collectAsState()
    val selectedWaypointId by ffViewModel.selectedWaypointId.collectAsState()
    val isShowingPresent by ffViewModel.isShowingPresent.collectAsState()
    val historicalPosition by ffViewModel.historicalPosition.collectAsState()
    var showSecurityCode by remember { mutableStateOf(false) }

    val waypointName by ffViewModel.waypointName.collectAsState()
    val waypointRange by ffViewModel.waypointRange.collectAsState()

    // History mode = a contact is selected and we're viewing their past track.
    val historyMode = selectedUserId != null && !isShowingPresent

    BackHandler(selectedUserId != null || (selectedWaypointId != null && selectedWaypointId != 0L)) {
        if (historyMode) {
            ffViewModel.setShowingPresent(true)
        } else {
            ffViewModel.clearSelection()
        }
    }

    val temporaryLinks by ffViewModel.temporaryLinks.collectAsState()
    val waypoints by ffViewModel.waypoints.collectAsState()

    val connectedUsers by ffViewModel.connectedUsers.collectAsState()
    val awaitingRequestUsers by ffViewModel.awaitingRequestUsers.collectAsState()
    val usersByLocationName by ffViewModel.usersByLocationName.collectAsState()
    val userPositions by ffViewModel.latestLocationByUser.collectAsState()

    val scaffoldState = rememberBottomSheetScaffoldState()

    // The sheets are stateless so the store-listing previews can render them (the map
    // behind them cannot be rendered off-device). The ViewModel supplies the actions it
    // already implements; the two that need the nav stack or the clipboard go here.
    val familyActions = remember(ffViewModel, backStack, platform) {
        object : FamilyListActions by ffViewModel {
            override fun acceptRequest(userId: Long) {
                backStack.add(Route.AddPersonDialog(userId))
            }

            override fun copyLink(link: TemporaryLink) {
                // Links are post-quantum only, so the fragment carries just the PQC
                // private bundle — no classic `#key=`. The fragment never hits the server.
                platform.copy("https://findfamily.cc/view/${link.id}#pqc_key=${link.pqcKey}")
            }
        }
    }

    // In history mode drop the sheet entirely (peek 0); the name goes in the app bar.
    val peekHeight = if (historyMode) 0.dp else SheetPeekHeight

    // The FAB sits on top of the always-light map, so color it from a light dynamic
    // scheme regardless of the app's (possibly dark) theme. Captured OUTSIDE the
    // scaffold to avoid the library's in-scaffold color-resolution quirk. Remembered
    // so we don't rebuild the whole palette on every recomposition.
    val context = LocalContext.current
    val lightScheme = remember(context) { dynamicLightColorScheme(context) }
    val fabContainerColor = lightScheme.primaryContainer
    val fabExpandedColor = lightScheme.primary
    val fabContentColor = lightScheme.onPrimaryContainer

    // The sheet's offset when settled at its peek, captured ONCE. Overlays then sit
    // at their default position plus (currentSheetOffset - peekOffset), clamped <= 0,
    // so they move 1:1 with the sheet. Peek is constant (128) whenever overlays show,
    // so a single capture stays correct across list/detail/back-from-history.
    val collapsedSheetOffset = remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(scaffoldState) {
        snapshotFlow {
            val st = scaffoldState.bottomSheetState
            val settled = st.currentValue == SheetValue.PartiallyExpanded &&
                st.targetValue == SheetValue.PartiallyExpanded
            val hist = selectedUserId != null && !isShowingPresent
            if (settled && !hist) runCatching { st.requireOffset() }.getOrNull() else null
        }.collect { off ->
            if (off != null && collapsedSheetOffset.floatValue.isNaN()) {
                collapsedSheetOffset.floatValue = off
            }
        }
    }

    // Leaving history sets the peek back to non-zero, but the collapsed sheet needs
    // a nudge to animate back to its peek. Retry until the anchor is ready.
    LaunchedEffect(historyMode) {
        if (!historyMode) {
            repeat(10) {
                if (runCatching { scaffoldState.bottomSheetState.partialExpand() }.isSuccess) {
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetSwipeEnabled = !historyMode,
        sheetDragHandle = if (historyMode) null else { { BottomSheetDefaults.DragHandle() } },
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // Default (solid) app bar so the map stays cut off beneath it while panning.
        topBar = {
            TopAppBar(
                title = {
                    if (historyMode) {
                        val historyUser by ffViewModel.userByIdState(selectedUserId!!)
                        Text(stringResource(R.string.history_title, historyUser?.name ?: ""))
                    } else if (selectedUserId == null && selectedWaypointId == null) {
                        Text(stringResource(R.string.app_name))
                    }
                },
                navigationIcon = {
                    if (selectedUserId != null || selectedWaypointId != null) {
                        IconNavigation {
                            if (historyMode) {
                                ffViewModel.setShowingPresent(true)
                            } else {
                                ffViewModel.clearSelection()
                            }
                        }
                    }
                },
                actions = {
                    if (selectedUserId == null && (selectedWaypointId == null || selectedWaypointId == 0L)) {
                        BackupButtons(
                            dbConfigs = listOf("passwords-db" to ffViewModel.backupPassphrase),
                            dbCodec = SqlCipherDbCodec,
                            extraFiles = emptyList()
                        )
                    } else if (selectedUserId != null && !historyMode) {
                        if (selectedUserId != Networking.userid) {
                            val user by ffViewModel.userByIdState(selectedUserId!!)
                            // Find Nearby (UWB) needs both the public
                            // android.ranging API (Android 16+) and an actual
                            // UWB radio. Hide the entry point otherwise.
                            if (UwbSessionManager.isAvailable(context)) {
                                IconButton({
                                    backStack.add(Route.UwbRangingPage(selectedUserId!!))
                                }) {
                                    IconNavigationArrow()
                                }
                            }
                            IconButton({ showSecurityCode = true }) {
                                IconVerify()
                            }
                            IconButton({
                                user?.let { ffViewModel.deleteUser(it) }
                                ffViewModel.setSelectedUserId(null)
                            }) {
                                IconDelete()
                            }
                        }
                    } else if (selectedWaypointId != null && selectedWaypointId != 0L) {
                        val waypoint by ffViewModel.waypointByIdState(selectedWaypointId!!)
                        IconButton({
                            waypoint?.let { ffViewModel.deleteWaypoint(it) }
                            ffViewModel.setSelectedWaypointId(null)
                        }) {
                            IconDelete()
                        }
                    }
                }
            )
        },
        sheetContent = {
            if (selectedUserId == null && selectedWaypointId == null) {
                FamilyListSheet(
                    FamilyListUiState(
                        connectedUsers = connectedUsers,
                        awaitingRequestUsers = awaitingRequestUsers,
                        temporaryLinks = temporaryLinks,
                        waypoints = waypoints,
                        locationByUser = userPositions,
                        userNamesByLocationName = usersByLocationName
                    ),
                    familyActions
                )
            } else if (historyMode) {
                // History mode has no sheet; the name is shown in the app bar.
            } else if (selectedUserId != null) {
                val selectedUser by ffViewModel.userByIdState(selectedUserId!!)
                val requestPickContact = platform.requestPickContact { name, photo ->
                    selectedUser?.let { ffViewModel.updateContactNamePhoto(it.id, name, photo) }
                }
                selectedUser?.let { user ->
                    val personActions = object : PersonActions by ffViewModel {
                        override fun changeConnectedContact() = requestPickContact()
                    }
                    PersonDetailSheet(PersonUiState(user, userPositions[user.id]), personActions)
                }
            } else if (selectedWaypointId != null) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    OutlinedTextField(
                        waypointName,
                        { ffViewModel.setWaypointName(it) },
                        Modifier.fillMaxWidth(),
                        isError = waypointName.isBlank(),
                        supportingText = if (waypointName.isBlank()) {
                            { Text(stringResource(R.string.waypoint_name_blank_error)) }
                        } else null
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        waypointRange,
                        { ffViewModel.setWaypointRange(it) },
                        Modifier.fillMaxWidth(),
                        suffix = { Text(stringResource(R.string.waypoint_range_suffix)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        isError = waypointRange.toDoubleOrNull() == null,
                        supportingText = if (waypointRange.toDoubleOrNull() == null) {
                            { Text(stringResource(R.string.waypoint_range_error)) }
                        } else null
                    )
                }
            }
        }
    ) { _ ->
        // Full-bleed map; overlays (FAB, history bar) sit just above the collapsed
        // sheet peek and lift upward as the sheet expands.
        Box(Modifier.fillMaxSize()) {
            // Lift overlays above their peek baseline as the sheet expands:
            // (current - settledPeekOffset), clamped to <= 0. Uses the settled peek
            // offset (sampled above) so transitions never push overlays off-screen.
            val sheetLiftPx: () -> Int = {
                val base = collapsedSheetOffset.floatValue
                val cur = runCatching { scaffoldState.bottomSheetState.requireOffset() }.getOrNull()
                if (cur != null && !base.isNaN()) (cur - base).roundToInt().coerceAtMost(0) else 0
            }

            val selectedUserObj = if (selectedUserId != null) {
                val user by ffViewModel.userByIdState(selectedUserId!!)
                user?.let { SelectedUser(it, isShowingPresent, historicalPosition) }
            } else null

            val selectedWaypointObj = if (selectedWaypointId != null) {
                val waypoint by ffViewModel.waypointByIdState(selectedWaypointId!!) { Waypoint.NEW_WAYPOINT }
                waypoint?.let { wp -> SelectedWaypoint(wp, waypointRange.toDoubleOrNull() ?: 0.0) {
                    ffViewModel.setWaypointCoord(it)
                } }
            } else null

            MapView(
                ffViewModel,
                onUserClick = {
                    ffViewModel.selectUser(it)
                },
                onMapClick = {
                    ffViewModel.clearSelection()
                },
                selectedUser = selectedUserObj,
                selectedWaypoint = selectedWaypointObj
            )

            if (historyMode) {
                HistoryScrubber(
                    backStack,
                    ffViewModel,
                    selectedUserId!!
                ) { ffViewModel.setHistoricalPosition(it) }
            }

            // FAB floats just above the sheet peek, lifting as the sheet expands.
            // Wrapped in the light scheme so it reads correctly over the light map.
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = peekHeight + 16.dp)
                    .offset { IntOffset(0, sheetLiftPx()) }
            ) {
                MaterialTheme(colorScheme = lightScheme) {
                    if (selectedUserId == null && selectedWaypointId == null) {
                        var expanded by remember { mutableStateOf(false) }
                        FloatingActionButtonMenu(expanded, {
                            ToggleFloatingActionButton(
                                expanded,
                                { expanded = it },
                                containerColor = { progress -> lerp(fabContainerColor, fabExpandedColor, progress) }
                            ) {
                                if (!expanded)
                                    IconAdd(tint = fabContentColor)
                                else
                                    IconClose(tint = fabContentColor)
                            }
                        }) {
                            FloatingActionButtonMenuItem({
                                backStack.add(Route.AddPersonDialog())
                            },
                                { Text(stringResource(R.string.fab_person)) },
                                { IconPerson() })
                            FloatingActionButtonMenuItem({
                                ffViewModel.beginCreateWaypoint()
                            },
                                { Text(stringResource(R.string.fab_location)) },
                                { IconLocationOn() })
                            FloatingActionButtonMenuItem({
                                backStack.add(Route.AddLinkDialog)
                            },
                                { Text(stringResource(R.string.fab_link)) },
                                { IconLink() })
                        }
                    } else if (selectedWaypointId != null) {
                        FloatingActionButton(
                            { ffViewModel.saveCurrentWaypoint() },
                            containerColor = fabContainerColor,
                            contentColor = fabContentColor
                        ) {
                            IconSave()
                        }
                    } else if (selectedUserId != null && isShowingPresent) {
                        // Enter history mode; exit is via back.
                        FloatingActionButton(
                            { ffViewModel.setShowingPresent(false) },
                            containerColor = fabContainerColor,
                            contentColor = fabContentColor
                        ) {
                            IconRestore()
                        }
                    }
                }
            }
        }
    }

    // Map animation logic
    LaunchedEffect(selectedUserId, isShowingPresent, historicalPosition) {
        if (selectedUserId != null) {
    val targetPosition = if (isShowingPresent) {
                userPositions[selectedUserId!!]?.coord?.toGeoPoint()
            } else {
                historicalPosition
            }
            targetPosition?.let {
                camera.animateTo(
                    camera.position.copy(
                        target = it,
                        zoom = 15.0
                    )
                )
            }
        }
    }

    LaunchedEffect(selectedWaypointId) {
        if (selectedWaypointId != null && selectedWaypointId != 0L) {
            val waypoint = waypoints.find { it.id == selectedWaypointId }
            waypoint?.coord?.toGeoPoint()?.let {
                camera.animateTo(
                    camera.position.copy(
                        target = it,
                        zoom = 15.0
                    )
                )
            }
        }
    }

    if (showSecurityCode && selectedUserId != null && selectedUserId != Networking.userid) {
        val user by ffViewModel.userByIdState(selectedUserId!!)
        user?.let { SecurityCodeDialog(it, ffViewModel) { showSecurityCode = false } }
    }
}

/**
 * The sheet shown when nobody is selected: everyone sharing with you, then inbound
 * requests, temporary links and saved places.
 */
@Composable
fun FamilyListSheet(state: FamilyListUiState, actions: FamilyListActions) {
    LazyColumn(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Keys are namespaced per section. All four lists live in one LazyColumn but their
        // ids come from independent tables, so a user and a waypoint that happen to share an
        // id would collide and Compose would throw "Key N was already used".
        items(
            state.connectedUsers,
            key = { "user-${it.id}" }
        ) {
            UserCard(it, state.locationByUser[it.id], true) {
                actions.selectUser(it.id)
            }
        }
        if (state.awaitingRequestUsers.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_location_sharing_requests)) }
        }
        items(
            state.awaitingRequestUsers,
            key = { "request-${it.id}" }
        ) {
            AwaitingRequestCard(it.id) { actions.acceptRequest(it.id) }
        }
        if (state.temporaryLinks.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_temporary_links)) }
        }
        items(state.temporaryLinks, key = { "link-${it.id}" }) {
            TemporaryLinkCard(it, { actions.copyLink(it) }, { actions.deleteTemporaryLink(it) })
        }
        if (state.waypoints.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_saved_places)) }
        }
        items(state.waypoints, key = { "waypoint-${it.id}" }) {
            WaypointCard(it, state.userNamesByLocationName[it.name].orEmpty()) {
                actions.beginEditWaypoint(it)
            }
        }
    }
}

/** The sheet shown when one person is selected: their status plus the sharing controls. */
@Composable
fun PersonDetailSheet(state: PersonUiState, actions: PersonActions) {
    val user = state.user
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
        UserCard(user, state.location, true) {}
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.share_your_location),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                user.sendingEnabled,
                { send -> actions.setUserSharing(user, send) }
            )
        }
        // Auto-toggle: "Turn on/off after" + duration dropdown (Never default)
        Spacer(Modifier.height(4.dp))
        AutoToggleRow(user, actions)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            { actions.changeConnectedContact() },
            Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.change_connected_contact))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmallEmphasized
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalTime::class)
@Composable
fun BoxScope.HistoryScrubber(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    userid: Long,
    setHistoricalPosition: (GeoPoint) -> Unit
) {
    val state = rememberHistoryScrubberState(
        initialInstant = Clock.System.now(),
        initialNowMode = true,
        disallowFuture = true
    )

    val steps = listOf(
        HistoryStep(stringResource(R.string.history_step_minus_5m), -5 * 60L),
        HistoryStep(stringResource(R.string.history_step_minus_1m), -60L),
        HistoryStep(stringResource(R.string.history_step_minus_10s), -10L),
        HistoryStep(stringResource(R.string.history_step_plus_10s), 10L),
        HistoryStep(stringResource(R.string.history_step_plus_1m), 60L),
        HistoryStep(stringResource(R.string.history_step_plus_5m), 5 * 60L)
    )

    HistoryScrubberCard(
        state = state,
        steps = steps,
        onDateChipClick = { backStack.add(Route.UserPageHistoryDatePicker(state.date)) }
    )

    ResultEffect<LocalDate>("HistoryDatePicker") {
        state.setDate(it)
    }

    val locs by ffViewModel.locationHistory.collectAsState()

    LaunchedEffect(state.instant, locs) {
        if (locs.isNotEmpty()) {
            val closest = locs.minBy { (it.timestamp - state.instant).absoluteValue }
            setHistoricalPosition(closest.coord.toGeoPoint())
        }
    }
}

@Composable
fun AwaitingRequestCard(id: Long, onAccept: () -> Unit) {
    Card {
        ListItem(
            { Text(stringResource(R.string.request_from, id.encodeBase26())) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = {
                IconButton(onAccept) {
                    IconAdd()
                }
            }
        )
    }
}

@Composable
fun TemporaryLinkCard(temporaryLink: TemporaryLink, onCopy: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card {
        ListItem(
            { Text(temporaryLink.name, fontWeight = FontWeight.Bold) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = {
                Text(stringResource(R.string.expires, timestring(temporaryLink.deleteAt, true, context)))
            },
            trailingContent = {
                Row {
                    IconButton(onCopy) {
                        IconCopy()
                    }
                    IconButton(onDelete) {
                        IconDelete()
                    }
                }
            }
        )
    }
}

@Composable
fun WaypointCard(waypoint: Waypoint, userNamesHere: List<String>, onSelect: () -> Unit) {
    val usersString = when (userNamesHere.size) {
        0 -> stringResource(R.string.nobody_here)
        1 -> stringResource(R.string.user_is_here, userNamesHere.first())
        else -> stringResource(R.string.users_are_here, userNamesHere.joinToString())
    }
    Card(Modifier.clickable(onClick = onSelect)) {
        ListItem(
            content = { Text(waypoint.name, fontWeight = FontWeight.Bold) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = { Text(usersString, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { IconEdit() }
        )
    }
}

@OptIn(ExperimentalTime::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UserCard(user: User, locationValue: LocationValue?, showSupportingContent: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val lastUpdatedTime = locationValue?.let { timestring(it.timestamp, false, context) } ?: stringResource(R.string.last_updated_never)
    val speedString = (locationValue?.speed ?: 0f).formatSpeed()
    val sinceTime = user.lastLocationChangeTime.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeSinceEntry = Clock.System.now() - user.lastLocationChangeTime
    val sinceString = when {
        user.locationName == "Unnamed Location" -> ""
        timeSinceEntry < 60.seconds -> stringResource(R.string.since_just_now)
        timeSinceEntry < 15.minutes -> stringResource(R.string.since_minutes_ago, timeSinceEntry.inWholeMinutes)
        else -> {
            val formattedTime = sinceTime.format(LocalDateTime.Format {
                amPmHour(Padding.NONE)
                chars(":")
                minute()
                chars(" ")
                localizedAmPmMarker(lowercase = true)
            })
            val formattedDate = when (sinceTime.date.toEpochDays() - Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()) {
                0L -> stringResource(R.string.today)
                1L -> stringResource(R.string.yesterday)
                else -> sinceTime.date.format(DateFormats.MONTH_DAY)
            }
            stringResource(R.string.since_time_date, formattedTime, formattedDate)
        }
    }
    Card(if (showSupportingContent) Modifier.clickable(onClick = onClick) else Modifier) {
        ListItem(
            leadingContent = { UserPicture(user, 40.dp) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        user.name,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            },
            supportingContent = {
                if (showSupportingContent) {
                    Text(
                        stringResource(
                            R.string.user_card_status,
                            lastUpdatedTime,
                            user.locationName,
                            sinceString
                        )
                    )
                }
            },
            trailingContent = {
                if (showSupportingContent) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(speedString, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(2.dp))
                        locationValue?.battery?.let { BatteryBar(it) }
                    }
                }
            }
        )
    }
}

@Composable
fun BatteryBar(percent: Float, width: Dp = 24.dp, height: Dp = 12.dp) {
    val color = when {
        percent > 50 -> Color.Green
        percent > 20 -> Color.Yellow
        else -> Color.Red
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width, height).border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidthFraction(percent).height(height).background(color, RoundedCornerShape(3.dp)))
        }
        Text(stringResource(R.string.battery_percentage, percent.toInt()), fontSize = 11.sp)
    }
}

private fun Modifier.fillMaxWidthFraction(percent: Float): Modifier =
    this.fillMaxWidth((percent / 100f).coerceIn(0f, 1f))

fun timestring(timestamp: Instant, future: Boolean, context: Context): String {
    val duration = (Clock.System.now() - timestamp).absoluteValue
    return when {
        duration.inWholeSeconds < 60 -> context.getString(if (future) R.string.time_very_soon else R.string.time_just_now)
        duration.inWholeMinutes < 60 -> context.getString(if (future) R.string.time_in_minutes else R.string.time_minutes_ago, duration.inWholeMinutes)
        duration.inWholeHours < 24 -> context.getString(if (future) R.string.time_in_hours else R.string.time_hours_ago, duration.inWholeHours)
        else -> context.getString(if (future) R.string.time_in_days else R.string.time_days_ago, duration.inWholeDays)
    }
}

private fun formatAutoToggleCountdown(remaining: Duration): String {
    val totalSeconds = remaining.inWholeSeconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoToggleRow(user: User, actions: PersonActions) {
    val neverLabel = stringResource(R.string.auto_toggle_never)
    val opts = remember {
        listOf(
            Pair("15 min", 15.minutes),
            Pair("30 min", 30.minutes),
            Pair("1 hour", 1.hours),
            Pair("2 hours", 2.hours),
            Pair("4 hours", 4.hours),
            Pair("6 hours", 6.hours),
            Pair("12 hours", 12.hours),
            Pair("1 day", 1.days),
            Pair("2 days", 2.days),
            Pair("1 week", 7.days),
        )
    }

    val resolvedLabels = mapOf(
        "15 min" to stringResource(R.string.expiry_15_minutes),
        "30 min" to stringResource(R.string.expiry_30_minutes),
        "1 hour" to stringResource(R.string.expiry_1_hour),
        "2 hours" to stringResource(R.string.expiry_2_hours),
        "4 hours" to stringResource(R.string.expiry_4_hours),
        "6 hours" to stringResource(R.string.expiry_6_hours),
        "12 hours" to stringResource(R.string.expiry_12_hours),
        "1 day" to stringResource(R.string.expiry_1_day),
        "2 days" to stringResource(R.string.expiry_2_days),
        "1 week" to stringResource(R.string.expiry_1_week),
    )

    val labelToDuration: Map<String, Duration> = opts.associate { (k, v) -> (resolvedLabels[k] ?: k) to v }

    // Live ticker for countdown — ticks every second while a timeout is active
    var now by remember { mutableStateOf(Clock.System.now()) }
    val endAt = user.sharingAutoToggleAt
    LaunchedEffect(endAt) {
        if (endAt == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            val current = Clock.System.now()
            now = current
            if (current >= endAt) break
        }
    }

    // If enabled, show live [hh:]mm:ss countdown; otherwise Never. Dropdown remains for picking new value.
    val currentLabel = if (endAt == null) {
        neverLabel
    } else {
        val remaining = endAt - now
        if (remaining.inWholeSeconds <= 0) neverLabel else formatAutoToggleCountdown(remaining)
    }

    val dropdownLabel = if (user.sendingEnabled) stringResource(R.string.disable_after) else stringResource(R.string.enable_after)

    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            dropdownLabel,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Box {
            OutlinedTextField(
                currentLabel, {},
                interactionSource = interactionSourceClickable { expanded = true },
                readOnly = true,
                modifier = Modifier.width(180.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            DropdownMenu(expanded, { expanded = false }) {
                // Never option first — disables auto-toggle; others reschedule countdown
                DropdownMenuItem({ Text(neverLabel) }, {
                    expanded = false
                    actions.setUserAutoToggle(user, null)
                })
                labelToDuration.forEach { (label, dur) ->
                    DropdownMenuItem({ Text(label) }, {
                        expanded = false
                        actions.setUserAutoToggle(user, dur)
                    })
                }
            }
        }
    }
}

object DateFormats {
    // example: Jun 4
    val MONTH_DAY: DateTimeFormat<LocalDate> get() = LocalDate.Format {
        monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT)))
        chars(" ")
        day()
    }

    // example: 10:05 am
    val TIME_SECOND_AM_PM: DateTimeFormat<LocalTime> get() = LocalTime.Format {
        amPmHour()
        chars(":")
        minute()
        chars(":")
        second()
        chars(" ")
        localizedAmPmMarker()
    }

    val DATE_INPUT: DateTimeFormat<LocalDate> get() = MONTH_DAY
}
