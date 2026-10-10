package com.vayunmathur.everysync.provider

import android.content.Context
import androidx.compose.runtime.Composable
import com.vayunmathur.everysync.auth.AccountConfig

/**
 * An E2EE vault source EverySync can sync into the on-device stores.
 *
 * The only implementation is the personal-vaults relay ([AuthType.RELAY]):
 * there is no remote auth and no cloud API — the relay is an untrusted log
 * and all authority is client-side (owner-signed roster/epochs/transfers).
 */
interface SyncProvider {
    val id: String
    val displayName: String
    val icon: @Composable () -> Unit
    val authType: AuthType
    val capabilities: Set<DataType>

    /** Run one sync pass for [config] in the requested [direction]. */
    suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection)
}
