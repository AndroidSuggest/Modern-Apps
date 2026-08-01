package com.vayunmathur.appstore.data.security

import androidx.annotation.StringRes
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppProvider
import com.vayunmathur.appstore.data.AppSource

/**
 * A rule the store applies to every app, whatever its source.
 *
 * Everything user-visible here is a string resource rather than a literal, because this is
 * the text that explains the store's security model and it has to be readable in the user's
 * own language to be worth anything.
 */
data class StoreRule(
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    /** Format arguments for [detail], when it takes any. */
    val detailArgs: List<Any> = emptyList(),
)

/**
 * Guarantees that hold for **every** install, from every source.
 *
 * These live here rather than being repeated on each tier so that a tier card only ever
 * describes what is *distinctive* about that tier. If something is true of all three, it
 * belongs in this list.
 */
object StoreGuarantees {

    val rules: List<StoreRule> = listOf(
        StoreRule(
            R.string.store_rule_modern_android_title,
            R.string.store_rule_modern_android_detail,
            listOf(AppProvider.MIN_TARGET_SDK),
        ),
        StoreRule(R.string.store_rule_signed_title, R.string.store_rule_signed_detail),
        StoreRule(R.string.store_rule_identity_title, R.string.store_rule_identity_detail),
        StoreRule(R.string.store_rule_same_key_title, R.string.store_rule_same_key_detail),
        StoreRule(R.string.store_rule_split_key_title, R.string.store_rule_split_key_detail),
        StoreRule(R.string.store_rule_precheck_title, R.string.store_rule_precheck_detail),
        StoreRule(R.string.store_rule_fail_blocks_title, R.string.store_rule_fail_blocks_detail),
        StoreRule(R.string.store_rule_updates_stay_title, R.string.store_rule_updates_stay_detail),
        StoreRule(R.string.store_rule_fixed_sources_title, R.string.store_rule_fixed_sources_detail),
    )
}

/**
 * How much a successful install can be verified, ranked by how feasible a supply-chain
 * attack against it is.
 *
 * The ranking is about **who is able to produce bytes this device will accept as an
 * update**, because that is the question that survives contact with reality once an app
 * is installed: review, scanning and store policy reduce the odds of a bad app arriving,
 * while the signing key decides who can replace a good one.
 *
 * Everything in [StoreGuarantees] applies here too and is deliberately not repeated.
 */
enum class SecurityTier {

    /**
     * Signed with the same key as this store app, checked against our own signing
     * certificate as read back from PackageManager at install time.
     */
    FIRST_PARTY,

    /**
     * F-Droid, restricted to builds F-Droid's verification server independently
     * reproduced bit-for-bit.
     */
    REPRODUCIBLE,

    /** Play, where Google holds the signing key and no publisher key exists to pin. */
    GOOGLE_PLAY;

    val rank: Int get() = ordinal + 1

    @get:StringRes
    val title: Int
        get() = when (this) {
            FIRST_PARTY -> R.string.tier_first_party_title
            REPRODUCIBLE -> R.string.tier_reproducible_title
            GOOGLE_PLAY -> R.string.tier_play_title
        }

    /** One line, shown under the badge on the app's page. */
    @get:StringRes
    val summary: Int
        get() = when (this) {
            FIRST_PARTY -> R.string.tier_first_party_summary
            REPRODUCIBLE -> R.string.tier_reproducible_summary
            GOOGLE_PLAY -> R.string.tier_play_summary
        }

    /** Who has to be compromised to push you malicious code. */
    @get:StringRes
    val threatModel: Int
        get() = when (this) {
            FIRST_PARTY -> R.string.tier_first_party_threat
            REPRODUCIBLE -> R.string.tier_reproducible_threat
            GOOGLE_PLAY -> R.string.tier_play_threat
        }

    /** Checks specific to this tier, on top of [StoreGuarantees.rules]. */
    val additionalChecks: List<Int>
        get() = when (this) {
            FIRST_PARTY -> listOf(
                R.string.tier_first_party_check_key,
                R.string.tier_first_party_check_hash,
            )
            REPRODUCIBLE -> listOf(
                R.string.tier_reproducible_check_index_signed,
                R.string.tier_reproducible_check_chained,
                R.string.tier_reproducible_check_rebuilt,
                R.string.tier_reproducible_check_hash_and_key,
            )
            GOOGLE_PLAY -> listOf(
                R.string.tier_play_check_hash,
                R.string.tier_play_check_key,
                R.string.tier_play_check_stamp,
            )
        }

    /** The honest downside of this tier. */
    @get:StringRes
    val caveat: Int
        get() = when (this) {
            FIRST_PARTY -> R.string.tier_first_party_caveat
            REPRODUCIBLE -> R.string.tier_reproducible_caveat
            GOOGLE_PLAY -> R.string.tier_play_caveat
        }

    companion object {
        fun of(source: AppSource): SecurityTier = when (source) {
            AppSource.MODERN_APPS -> FIRST_PARTY
            // Non-reproduced F-Droid packages are dropped during sync and never reach the
            // UI, so anything surviving from FDROID is tier 2 by construction.
            AppSource.FDROID -> REPRODUCIBLE
            AppSource.PLAYSTORE -> GOOGLE_PLAY
        }
    }
}
