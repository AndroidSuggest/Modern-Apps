package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.carhost.CarTemplateView
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostUiAction
import com.vayunmathur.library.carhost.HostUiRow
import com.vayunmathur.library.carhost.HostUiSection
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface

/** Landscape head-unit display, ~Android Auto reference. */
private const val CAR = "spec:width=1024dp,height=600dp,dpi=160"

/**
 * Store-listing screenshots of the Android Auto car views for `:communicate`.
 *
 * Feeds representative [HostTemplate]s to the shared [CarTemplateView] renderer
 * from `:library:carhost`. Shows the merged thread list grouped by line, the
 * in-call controls, and the dialpad.
 */
class CarMetadataPreviews {

    @PreviewTest
    @Preview(name = "1-car-threads", device = CAR, showSystemUi = false)
    @Composable
    fun Preview1CarThreads() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.TemplateList(
                        title = "Messages",
                        sections = listOf(
                            HostUiSection(
                                header = "SIM",
                                rows = listOf(
                                    HostUiRow("Alex Rivera", listOf("On my way, 5 minutes out"), browse = true) {},
                                    HostUiRow("Mom", listOf("Call me when you can", "2 unread"), browse = true) {},
                                ),
                            ),
                            HostUiSection(
                                header = "WhatsApp",
                                rows = listOf(
                                    HostUiRow("Weekend Trip", listOf("Sam: booked the cabin!"), browse = true) {},
                                    HostUiRow("Priya", listOf("Sounds good 👍"), browse = true) {},
                                ),
                            ),
                            HostUiSection(
                                header = "Signal",
                                rows = listOf(
                                    HostUiRow("Jordan", listOf("See you at the gate"), browse = true) {},
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-car-incall", device = CAR, showSystemUi = false)
    @Composable
    fun Preview2CarInCall() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.InCall(
                        title = "Alex Rivera",
                        texts = listOf("Active"),
                        actions = listOf(
                            HostUiAction("Mute") {},
                            HostUiAction("Speaker") {},
                            HostUiAction("End") {},
                        ),
                    ),
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "3-car-keypad", device = CAR, showSystemUi = false)
    @Composable
    fun Preview3CarKeypad() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.Keypad(
                        title = "Dial",
                        phoneNumber = "+1 (415) 555-0132",
                        onPrimaryAction = {},
                        primaryTitle = "Call",
                    ),
                )
            }
        }
    }
}
