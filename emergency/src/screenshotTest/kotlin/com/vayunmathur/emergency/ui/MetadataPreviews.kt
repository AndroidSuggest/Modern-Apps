package com.vayunmathur.emergency.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.emergency.data.EmergencyInfo
import com.vayunmathur.emergency.platform.EmergencyActions
import com.vayunmathur.emergency.platform.EmergencyUiState
import com.vayunmathur.emergency.platform.SosActions
import com.vayunmathur.emergency.platform.SosUiState
import com.vayunmathur.emergency.platform.sampleContact
import com.vayunmathur.emergency.platform.sampleHealthConnect
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:emergency`, rendered from Compose previews instead of an
 * instrumented test on a device (the gesture provider and SOS call placement need a
 * platform-signed install and cannot run here).
 *
 * `./gradlew :emergency:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/emergency/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it. Everything is
 * a literal so the output is reproducible from a clean checkout.
 */
class MetadataPreviews {

    private val sample = EmergencyInfo(
        name = "Sam Rivera",
        address = "1 Main St",
        bloodType = "O+",
        organDonor = "Yes",
        contacts = listOf(sampleContact()),
    )

    @PreviewTest
    @Preview(name = "1-view", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1View() {
        DynamicTheme(darkTheme = true) {
            ViewInfoScreen(
                state = EmergencyUiState(
                    info = sample,
                    loading = false,
                    health = sampleHealthConnect(),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-edit", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Edit() {
        DynamicTheme(darkTheme = true) {
            EditInfoScreen(
                state = EmergencyUiState(
                    info = sample,
                    loading = false,
                    hasContactsAccess = true,
                    health = sampleHealthConnect(),
                ),
                actions = EmergencyActions.Noop,
                onPickContact = {},
                onPickOwner = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-address", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4OwnerAddress() {
        DynamicTheme(darkTheme = true) {
            OwnerAddressDialog(
                name = "Sam Rivera",
                addresses = listOf("1 Main St, Springfield", "9 Lake Ave, Shelbyville"),
                onConfirm = {},
                onDismiss = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-sos", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Sos() {
        DynamicTheme(darkTheme = true) {
            SosScreen(
                state = SosUiState(
                    number = "911",
                    totalMillis = 5_000,
                    gestureEnabled = true,
                    loaded = true,
                ),
                actions = SosActions.Noop,
            )
        }
    }
}
