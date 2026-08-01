package com.vayunmathur.appstore.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.AppDetailActions
import com.vayunmathur.appstore.util.AppDetailUiState
import com.vayunmathur.appstore.util.BrowseActions
import com.vayunmathur.appstore.util.BrowseUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Stand-in launcher icon.
 *
 * Real icons come either from PackageManager or over the network from F-Droid/Play, and a
 * preview has neither, so the sample rows carry a generated tile rather than a blank
 * square. It goes in through `installedIcons`, which is the one icon path that does not
 * touch the network.
 */
private fun tile(top: Long, bottom: Long): Drawable =
    GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(top.toInt(), bottom.toInt()),
    )

/**
 * Store listing images for `:appstore`, rendered from Compose previews instead of from
 * an instrumented test on a device.
 *
 * `./gradlew :appstore:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/appstore/`, where `release.sh` picks them up.
 *
 * Two things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Browse`/`Preview2Detail`/... sort into listing
 *    order no matter how the plugin formats the rest of the filename. Renumber the
 *    functions if you reorder the listing.
 *  - Everything must be a literal. These render with no ViewModel, no database and no
 *    network, so the state below is the whole input — which is also what makes the output
 *    reproducible from a clean checkout.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Android Studio
 *    renders top-level previews happily, but the screenshot engine discovers previews as
 *    JUnit tests and needs a real class to attach them to — top-level functions land in a
 *    synthetic `…Kt` facade and are silently skipped.
 *
 * Two of the four slots go to the security-tiers explainer, because the tier a package
 * got and why is the thing that distinguishes this store from any other F-Droid client.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-browse", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Browse() {
        DynamicTheme(darkTheme = true) {
            SearchAndBrowseScreen(
                state = BrowseUiState(
                    apps = CATALOGUE,
                    installedPackages = setOf(
                        "com.vayunmathur.calculator",
                        "com.beemdevelopment.aegis",
                    ),
                    downloadProgress = mapOf("com.nutomic.syncthingandroid" to 0.42f),
                    installedIcons = ICONS,
                ),
                actions = BrowseActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-detail", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Detail() {
        DynamicTheme(darkTheme = true) {
            AppDetailScreen(
                state = AppDetailUiState(
                    app = AEGIS.copy(
                        versionName = "3.4.1",
                        versionCode = 341,
                        sizeBytes = 9_871_360,
                        license = "GPL-3.0-only",
                        categories = listOf("Security", "Internet"),
                        sourceCode = "https://github.com/beemdevelopment/Aegis",
                        website = "https://getaegis.app",
                        whatsNew = "Faster vault unlock and a fix for importing " +
                            "Authenticator Plus backups.",
                        description = "Aegis is a free, secure and open source app for " +
                            "Android to manage your 2-step verification tokens.\n\n" +
                            "Vaults are encrypted with AES-256 and can be unlocked with " +
                            "a password or your fingerprint. Backups are written to a " +
                            "location you choose, and nothing ever leaves the device.",
                    ),
                    installedInfo = InstalledInfo(
                        packageName = "com.beemdevelopment.aegis",
                        name = "Aegis Authenticator",
                        versionName = "3.4.0",
                        versionCode = 340,
                    ),
                    // What the last install actually proved, in the verifier's own words.
                    verification = VerificationResult.Verified(
                        "hash matches for 1 of 1 file(s); " +
                            "signed by the key F-Droid's signed app list expects"
                    ),
                    installedIcon = tile(0xFF3A6EA5, 0xFF17324D),
                ),
                actions = AppDetailActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-guarantees", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Guarantees() {
        DynamicTheme(darkTheme = true) {
            SecurityTiersPage(
                ownSigningCertificates = setOf(SAMPLE_FINGERPRINT),
                onBack = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-tiers", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Tiers() {
        DynamicTheme(darkTheme = true) {
            SecurityTiersPage(
                ownSigningCertificates = setOf(SAMPLE_FINGERPRINT),
                onBack = {},
                // Items 0 and 1 are the intro and the shared-guarantees card, which the
                // previous shot already covers; 2 is the first per-tier card.
                initialFirstVisibleItem = 2,
            )
        }
    }
}

/**
 * Only ever passed to [com.vayunmathur.appstore.data.security.ApkCertificates.abbreviate],
 * which shows the first eight bytes, so this is a plausible-looking constant rather than
 * anyone's real signing certificate.
 */
private const val SAMPLE_FINGERPRINT =
    "7a3f19c4d8025be6104fbb27a9c31d5e08f7462a1c9d3b80e5fa62471d0c98b3"

private val AEGIS = UnifiedApp(
    packageName = "com.beemdevelopment.aegis",
    source = AppSource.FDROID,
    name = "Aegis Authenticator",
    summary = "Two-factor authentication with an encrypted, exportable vault",
    author = "Beem Development",
)

/**
 * A browse list as it looks after a sync: this repo's own apps first (tier 1), then the
 * F-Droid builds that were independently reproduced (tier 2), then Play (tier 3). That is
 * the order `combinedBrowse` produces, so the sample keeps it.
 */
private val CATALOGUE = listOf(
    UnifiedApp(
        packageName = "com.vayunmathur.calculator",
        source = AppSource.MODERN_APPS,
        name = "Calculator",
        summary = "Scientific calculator with graphing, history and memory",
        author = "Vayun Mathur",
        versionName = "1.8.0",
        versionCode = 180,
    ),
    UnifiedApp(
        packageName = "com.vayunmathur.email",
        source = AppSource.MODERN_APPS,
        name = "Email",
        summary = "IMAP mail with threads, drafts, snooze and an offline outbox",
        author = "Vayun Mathur",
        versionName = "1.8.0",
        versionCode = 180,
    ),
    UnifiedApp(
        packageName = "com.vayunmathur.maps",
        source = AppSource.MODERN_APPS,
        name = "Maps",
        summary = "Offline vector maps with search and turn-by-turn navigation",
        author = "Vayun Mathur",
        versionName = "1.8.0",
        versionCode = 180,
    ),
    AEGIS,
    UnifiedApp(
        packageName = "com.kunzisoft.keepass.libre",
        source = AppSource.FDROID,
        name = "KeePassDX",
        summary = "Password manager for KeePass databases, fully offline",
        author = "Kunzisoft",
    ),
    UnifiedApp(
        packageName = "com.nutomic.syncthingandroid",
        source = AppSource.FDROID,
        name = "Syncthing",
        summary = "Continuous file synchronisation between your own devices",
        author = "Syncthing Community",
    ),
    UnifiedApp(
        packageName = "org.mozilla.firefox",
        source = AppSource.PLAYSTORE,
        name = "Firefox",
        summary = "Web browser with tracking protection and add-ons",
        author = "Mozilla",
    ),
    UnifiedApp(
        packageName = "org.thoughtcrime.securesms",
        source = AppSource.PLAYSTORE,
        name = "Signal",
        summary = "End-to-end encrypted messaging and calls",
        author = "Signal Messenger",
    ),
)

private val ICONS: Map<String, Drawable> = mapOf(
    "com.vayunmathur.calculator" to tile(0xFF6D5BD0, 0xFF2E2467),
    "com.vayunmathur.email" to tile(0xFF1E88A8, 0xFF0B3C4C),
    "com.vayunmathur.maps" to tile(0xFF2E9E63, 0xFF0F4630),
    "com.beemdevelopment.aegis" to tile(0xFF3A6EA5, 0xFF17324D),
    "com.kunzisoft.keepass.libre" to tile(0xFFB0562A, 0xFF4C2312),
    "com.nutomic.syncthingandroid" to tile(0xFF3E6FB5, 0xFF17304F),
    "org.mozilla.firefox" to tile(0xFFE05C2A, 0xFF5E2210),
    "org.thoughtcrime.securesms" to tile(0xFF3A76F0, 0xFF15306A),
)
