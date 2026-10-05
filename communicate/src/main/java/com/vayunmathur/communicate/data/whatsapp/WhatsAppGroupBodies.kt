package com.vayunmathur.communicate.data.whatsapp

/**
 * Group-notification bodies (split from WhatsAppClientInbound.kt for file length).
 * Behavior identical, call sites unchanged.
 */

/** Human-readable body for one group-change child, or null when unhandled. */
internal fun groupChangeBody(child: WhatsAppProtocol.Node): String? {
    return when (child.tag) {
        "subject" -> subjectBody(child)
        "description" -> descriptionBody(child)
        "add", "remove", "promote", "demote" -> membershipBody(child)
        // Go wrapGroupInfoChange: ephemeral setting
        "ephemeral" -> ephemeralBody(child)
        // Go wrapGroupInfoChange: announce mode
        "announce" -> announceBody(child)
        // Go wrapGroupInfoChange: locked (restrict edit to admins)
        "locked" -> lockedBody(child)
        // Go wrapGroupInfoChange: link/unlink community
        "link" -> "[Group linked: ${child.attrs["link_type"]}]"
        "unlink" -> "[Group unlinked: ${child.attrs["unlink_type"]}]"
        else -> null
    }
}

/** Subject-change body. */
private fun subjectBody(child: WhatsAppProtocol.Node): String? {
    val newName = child.attrs["subject"] ?: child.data?.let { String(it, Charsets.UTF_8) }
    return newName?.let { "[Group name changed to: $it]" }
}

/** Description-change body. */
private fun descriptionBody(child: WhatsAppProtocol.Node): String {
    val newDesc = child.data?.let { String(it, Charsets.UTF_8) } ?: ""
    return "[Group description changed: $newDesc]"
}

/** Membership-change body. */
private fun membershipBody(child: WhatsAppProtocol.Node): String {
    val participants = child.content.filterIsInstance<WhatsAppProtocol.Node>()
        .mapNotNull { it.attrs["jid"] }
    return "[Group: ${participants.joinToString()} ${child.tag}ed]"
}

/** Announce-mode body. */
private fun announceBody(child: WhatsAppProtocol.Node): String {
    val isAnnounce = child.attrs["announce"] == "true" || child.attrs["value"] == "on"
    return if (isAnnounce) "[Only admins can send messages now]"
    else "[All participants can send messages now]"
}

/** Locked-state body. */
private fun lockedBody(child: WhatsAppProtocol.Node): String {
    val isLocked = child.attrs["locked"] == "true" || child.attrs["value"] == "on"
    return if (isLocked) "[Only admins can edit group info now]"
    else "[All participants can edit group info now]"
}

/** Ephemeral-setting change body. */
internal fun ephemeralBody(child: WhatsAppProtocol.Node): String {
    val expiration = child.attrs["expiration"]?.toLongOrNull() ?: 0
    if (expiration <= 0) return "[Disappearing messages turned off]"
    val duration = when {
        expiration >= SECONDS_PER_WEEK -> "${expiration / SECONDS_PER_WEEK} week(s)"
        expiration >= SECONDS_PER_DAY -> "${expiration / SECONDS_PER_DAY} day(s)"
        expiration >= SECONDS_PER_HOUR -> "${expiration / SECONDS_PER_HOUR} hour(s)"
        else -> "$expiration seconds"
    }
    return "[Disappearing messages set to $duration]"
}
