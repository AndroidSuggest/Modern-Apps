package com.vayunmathur.everysync.provider

import kotlinx.serialization.Serializable

/** Every domain EverySync can land on-device: system providers or app IPC vaults, all relay-synced. */
@Serializable
enum class DataType {
    CONTACTS,
    CALENDAR,
    /** E2EE relay vault tasks (no system provider; the todo/ app owns storage). */
    TODOS,
    /** E2EE relay vault passwords (passwords app owns storage via its relay ContentProvider). */
    PASSWORDS,
    /** E2EE relay vault notes (notes app owns storage via its relay ContentProvider). */
    NOTES,
}

/** How an account authenticates. Only relay pairing remains: the relay is an untrusted log. */
enum class AuthType {
    /**
     * No remote auth: all authority is client-side (owner-signed roster/epochs/
     * transfers). The vault key lives in [com.vayunmathur.everysync.auth.TokenStore]
     * per (account, type).
     */
    RELAY,
}

/** Direction a sync runs in. */
enum class SyncDirection {
    PULL,
    PUSH,
    BOTH,
}
