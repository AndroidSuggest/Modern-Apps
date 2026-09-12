package com.vayunmathur.games.hub.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.IconEmoji
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconFlashOn
import com.vayunmathur.library.ui.IconFlight
import com.vayunmathur.library.ui.IconHotel
import com.vayunmathur.library.ui.IconLightbulb
import com.vayunmathur.library.ui.IconMenuBook
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconShapeDiamondFill
import com.vayunmathur.library.ui.IconShield
import com.vayunmathur.library.ui.IconSportsEsports
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconVerify
import com.vayunmathur.library.ui.LocalContentColor

/**
 * Renders the player's chosen avatar [symbol] using only the shared `IconXxx`
 * wrappers — app modules must never call `Icon()` or import
 * `androidx.compose.material.icons.*` directly. Unknown or null symbols fall
 * back to [IconPerson] so a stale saved value can never render blank.
 */
@Composable
fun AvatarIcon(
    symbol: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    when (symbol) {
        "stadia_controller", "sports_esports" -> IconSportsEsports(modifier, tint)
        "emoji_events" -> IconEmojiEvents(modifier, tint)
        "military_tech" -> IconShield(modifier, tint)
        "star" -> IconStar(modifier, tint)
        "bolt" -> IconFlashOn(modifier, tint)
        "local_fire_department" -> IconFire(modifier, tint)
        "rocket" -> IconFlight(modifier, tint)
        "diamond" -> IconShapeDiamondFill(modifier, tint)
        "psychology" -> IconEmoji(modifier, tint)
        "lightbulb" -> IconLightbulb(modifier, tint)
        "school" -> IconMenuBook(modifier, tint)
        "workspace_premium" -> IconVerify(modifier, tint)
        "king_bed" -> IconHotel(modifier, tint)
        else -> IconPerson(modifier, tint)
    }
}
