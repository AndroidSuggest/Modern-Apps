package com.vayunmathur.appstore.data.accrescent

/**
 * Fixed trust anchors for the Accrescent source, mirroring Accrescent's own client
 * (`app/src/main/java/app/accrescent/client/data/Constants.kt`).
 *
 * These are the only values this store takes on faith for Accrescent: the repodata host,
 * the gRPC API host, and — the actual root of trust — the ed25519 public key the signed
 * allowlist ([AccrescentRepoData]) must verify against. Everything else (names, icons,
 * download URLs) comes from the gRPC API and is treated as untrusted metadata; the security
 * decision is made from the signed repodata plus on-device APK signature + min-version checks.
 */
object AccrescentRepo {
    const val REPOSITORY_URL = "https://repo.accrescent.app"
    const val APP_STORE_API_DOMAIN = "appstore-api.accrescent.app"

    /** signify-format ed25519 public key the repodata signature is verified against. */
    const val REPODATA_PUBKEY = "RWT8aZ/NdUmXCPqQ0we7UyCe34q1xRfncBFVK5dI3ok9BkL1bFF3mgh3"

    const val REPODATA_VERSION = 1

    /** repodata.<version>.json / .sig. */
    val REPODATA_JSON_URL = "$REPOSITORY_URL/repodata.$REPODATA_VERSION.json"
    val REPODATA_SIG_URL = "$REPOSITORY_URL/repodata.$REPODATA_VERSION.json.sig"

    /**
     * Anti-rollback floor: the store refuses any repodata whose timestamp is older than this,
     * even on first run when nothing is stored yet. Matches Accrescent's `MIN_TIMESTAMP`.
     */
    const val MIN_TIMESTAMP = 1761251784L
}
