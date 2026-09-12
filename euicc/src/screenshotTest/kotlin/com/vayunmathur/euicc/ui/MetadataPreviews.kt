package com.vayunmathur.euicc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.data.Notification
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.platform.EuiccScreenState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store-listing images for `:euicc`, rendered from Compose previews instead of an
 * instrumented test on a device (which is impossible here — the LPA needs a
 * platform-signed install and a real eUICC).
 *
 * `./gradlew :euicc:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/euicc/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a
 * class (not a top-level function) or the screenshot engine silently skips it.
 * Everything is a literal so the output is reproducible from a clean checkout.
 */
class MetadataPreviews {

    private val sampleProfiles = listOf(
        Profile(
            iccid = "9810320000000000001",
            iccidDisplay = "8901234567890000001",
            state = "enabled",
            profileClass = "operational",
            nickname = "Work",
            serviceProvider = "Example Mobile",
            name = "Example Prepaid",
        ),
        Profile(
            iccid = "9810320000000000002",
            iccidDisplay = "8901234567890000002",
            state = "disabled",
            profileClass = "operational",
            serviceProvider = "Travel eSIM",
            name = "Global Data 5GB",
        ),
    )

    private val sampleNotifications = listOf(
        Notification(seqNumber = 3, operation = "enable", address = "smdp.example.com"),
        Notification(seqNumber = 4, operation = "install", address = "rsp.travelesim.com"),
    )

    private val sampleInfo = EuiccInfo(svn = "2.2.0")

    @PreviewTest
    @Preview(name = "1-profiles", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Profiles() {
        DynamicTheme(darkTheme = true) {
            EuiccHomeScreen(
                state = EuiccScreenState(
                    loading = false,
                    eid = "89044000001234567890123456789012",
                    info = sampleInfo,
                    profiles = sampleProfiles,
                    notifications = sampleNotifications,
                ),
                backStack = NavBackStack(arrayOf(Route.Home)),
                onReload = {},
                onAddSim = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-empty", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Empty() {
        DynamicTheme(darkTheme = true) {
            EuiccHomeScreen(
                state = EuiccScreenState(
                    loading = false,
                    eid = "89044000001234567890123456789012",
                    info = sampleInfo,
                    profiles = emptyList(),
                    notifications = emptyList(),
                ),
                backStack = NavBackStack(arrayOf(Route.Home)),
                onReload = {},
                onAddSim = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-expanded", device = EXPANDED, showSystemUi = true)
    @Composable
    fun Preview3Expanded() {
        DynamicTheme(darkTheme = true) {
            // Expanded windows keep the profile list visible while the selected
            // profile's detail opens beside it instead of covering it.
            val state = EuiccScreenState(
                loading = false,
                eid = "89044000001234567890123456789012",
                info = sampleInfo,
                profiles = sampleProfiles,
                notifications = sampleNotifications,
            )
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    EuiccHomeScreen(
                        state = state,
                        backStack = NavBackStack(arrayOf(Route.Home)),
                        onReload = {},
                        onAddSim = {},
                    )
                }
                Box(Modifier.weight(0.6f).fillMaxHeight()) {
                    ProfileDetailScreen(
                        iccid = sampleProfiles.first().iccid,
                        state = state,
                        backStack = NavBackStack(arrayOf(Route.Home)),
                        onEnable = {},
                        onDisable = {},
                        onErase = {},
                        onRename = { _, _ -> },
                    )
                }
            }
        }
    }
}
