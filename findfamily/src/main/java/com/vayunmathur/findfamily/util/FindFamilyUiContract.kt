package com.vayunmathur.findfamily.util

import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import kotlin.time.Duration

/**
 * The UI contract between [FindFamilyViewModel] and the bottom-sheet screens on the map
 * page.
 *
 * The sheets take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. That matters more here than elsewhere: the map underneath the sheet is a
 * tile renderer that Layoutlib cannot draw, so the sheets are the only part of the main
 * screen a preview can show.
 *
 * This lives in `util` rather than `ui` so the dependency runs one way: `ui` depends on
 * `util`, and the ViewModel implements these interfaces.
 */

/** Everything the collapsed/expanded family sheet draws. */
data class FamilyListUiState(
    val connectedUsers: List<User> = emptyList(),
    val awaitingRequestUsers: List<User> = emptyList(),
    val temporaryLinks: List<TemporaryLink> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    /** Most recent location report per user id, for the speed/battery/last-seen line. */
    val locationByUser: Map<Long, LocationValue> = emptyMap(),
    /** Names of the people currently at each saved place, keyed by place name. */
    val userNamesByLocationName: Map<String, List<String>> = emptyMap(),
)

/**
 * Family-sheet callbacks. Every method has a no-op default so a preview can render the
 * sheet without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * [FindFamilyViewModel] implements the three that are pure state changes; the two that
 * need the nav back stack or the clipboard are supplied by the caller.
 */
interface FamilyListActions {
    fun selectUser(userId: Long) {}
    fun acceptRequest(userId: Long) {}
    fun copyLink(link: TemporaryLink) {}
    fun deleteTemporaryLink(link: TemporaryLink) {}
    fun beginEditWaypoint(waypoint: Waypoint) {}

    companion object {
        val Noop: FamilyListActions = object : FamilyListActions {}
    }
}

/** Everything the single-person sheet draws. */
data class PersonUiState(
    val user: User,
    val location: LocationValue? = null,
)

/** Person-sheet callbacks. Same no-op-default arrangement as [FamilyListActions]. */
interface PersonActions {
    fun setUserSharing(user: User, enabled: Boolean) {}

    /** Flip sharing after [duration]; null means Never. */
    fun setUserAutoToggle(user: User, duration: Duration?) {}

    /** Re-pick which device contact this connection is named after. */
    fun changeConnectedContact() {}

    companion object {
        val Noop: PersonActions = object : PersonActions {}
    }
}
