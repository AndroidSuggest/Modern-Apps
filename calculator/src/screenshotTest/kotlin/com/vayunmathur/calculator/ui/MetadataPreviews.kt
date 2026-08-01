package com.vayunmathur.calculator.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.util.FeatureKind
import com.vayunmathur.calculator.util.GraphActions
import com.vayunmathur.calculator.util.GraphFunction
import com.vayunmathur.calculator.util.GraphMarker
import com.vayunmathur.calculator.util.GraphPoint
import com.vayunmathur.calculator.util.GraphUiState
import com.vayunmathur.calculator.util.GraphViewport
import com.vayunmathur.calculator.util.HistoryEntry
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:calculator`, rendered from Compose previews instead of from
 * an instrumented test on a device.
 *
 * `./gradlew :calculator:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/calculator/`, where `release.sh` picks them up.
 *
 * Two things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Keypad`/`Preview2Graph`/... sort into listing
 *    order no matter how the plugin formats the rest of the filename. Renumber the
 *    functions if you reorder the listing.
 *  - Everything must be a literal. These render with no ViewModel, no database and no
 *    device, so the state below is the whole input — which is also what makes the output
 *    reproducible from a clean checkout.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Android Studio
 *    renders top-level previews happily, but the screenshot engine discovers previews as
 *    JUnit tests and needs a real class to attach them to — top-level functions land in a
 *    synthetic `…Kt` facade and are silently skipped, which surfaces as "did not discover
 *    any tests".
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching
 * the `cmd uimode night yes` the old on-device generator used. Note that Material You
 * sources its palette from the device wallpaper, which does not exist here, so these
 * render with the fallback scheme rather than a user's actual accent colour.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-keypad", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Keypad() {
        DynamicTheme(darkTheme = true) {
            CalculatorScreen(
                state = CalculatorUiState(
                    input = "sin(45) + 2^10",
                    preview = "1024.707",
                    memory = 12.5,
                    angleMode = AngleMode.DEGREES,
                ),
                actions = CalculatorActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-graph", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Graph() {
        val colors = CalculatorViewModel.FunctionColors
        DynamicTheme(darkTheme = true) {
            GraphScreen(
                state = GraphUiState(
                    functions = listOf(
                        GraphFunction(id = 0, text = "sin(x)", color = colors[0]),
                        GraphFunction(id = 1, text = "x^2/8 - 2", color = colors[1]),
                        GraphFunction(id = 2, text = "cos(x)/2", color = colors[2], enabled = false),
                    ),
                    markers = listOf(
                        GraphMarker(GraphPoint(0.0, 0.0), FeatureKind.INTERSECTION, listOf(0L, 1L)),
                        GraphMarker(GraphPoint(-1.571, -1.0), FeatureKind.MINIMUM, listOf(0L)),
                    ),
                    angleMode = AngleMode.RADIANS,
                    viewport = GraphViewport(centerX = 0.0, centerY = 0.0, scale = 42.0),
                ),
                actions = GraphActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-history", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3History() {
        DynamicTheme(darkTheme = true) {
            CalculatorScreen(
                state = CalculatorUiState(
                    input = "12!",
                    preview = "479001600",
                    angleMode = AngleMode.RADIANS,
                    history = listOf(
                        HistoryEntry("sin(45) + 2^10", "1024.707"),
                        HistoryEntry("nCr(52,5)", "2598960"),
                        HistoryEntry("|-17.5| * pi", "54.978"),
                        HistoryEntry("log(2,4096)", "12"),
                    ),
                ),
                actions = CalculatorActions.Noop,
                initialShowHistory = true,
            )
        }
    }
}
