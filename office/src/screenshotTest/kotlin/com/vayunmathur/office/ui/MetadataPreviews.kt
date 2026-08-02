package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.HomeScreen
import com.vayunmathur.office.OfficeLightTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

// --- Sample documents ------------------------------------------------------
//
// The text document is the real metadata_data/assets/sample1.docx, run through the app's own
// importer offline (see SampleDocx.kt). The spreadsheet and presentation stay hand-built: the
// spreadsheet's values would otherwise need the native formula engine, which Layoutlib cannot
// load, and no sample .pptx renders meaningfully without its media.

/**
 * `metadata_data/assets/sample1.docx`, imported by the app's own OOXML importer — so the
 * listing image is the app opening a real Word document, not a model built to look like one.
 *
 * The import runs offline and its result is checked in as [SampleDocxBlocks]; see that file
 * for why a preview cannot parse the .docx itself.
 */
private val SampleTextDocument = OdfDocument.TextDocument(
    title = "sample1.docx",
    content = SampleDocxBlocks,
)

private fun sheetCell(
    text: String,
    bold: Boolean = false,
    align: TextAlign? = null,
    background: Long? = null,
) = OdfCell(text = text, bold = bold, alignment = align, backgroundColor = background)

private val HeaderFill = 0xFFE3E9F5L

private val SampleSpreadsheet = OdfDocument.Spreadsheet(
    title = "Budget.ods",
    sheets = listOf(
        OdfSheet(
            name = "Q3 Budget",
            rows = listOf(
                OdfRow(listOf(
                    sheetCell("Department", bold = true, background = HeaderFill),
                    sheetCell("Planned", bold = true, align = TextAlign.End, background = HeaderFill),
                    sheetCell("Actual", bold = true, align = TextAlign.End, background = HeaderFill),
                    sheetCell("Variance", bold = true, align = TextAlign.End, background = HeaderFill),
                )),
                OdfRow(listOf(sheetCell("Research"), sheetCell("48,000"), sheetCell("46,120"), sheetCell("-1,880"))),
                OdfRow(listOf(sheetCell("Fieldwork"), sheetCell("62,500"), sheetCell("64,310"), sheetCell("1,810"))),
                OdfRow(listOf(sheetCell("Equipment"), sheetCell("18,750"), sheetCell("17,940"), sheetCell("-810"))),
                OdfRow(listOf(sheetCell("Travel"), sheetCell("12,400"), sheetCell("13,850"), sheetCell("1,450"))),
                OdfRow(listOf(sheetCell("Outreach"), sheetCell("6,150"), sheetCell("5,980"), sheetCell("-170"))),
                OdfRow(listOf(
                    sheetCell("Total", bold = true, background = HeaderFill),
                    // Shown as the formula engine would evaluate it; the preview's value source
                    // returns the literal text, so the cell reads the same either way.
                    OdfCell(text = "147,800", bold = true, formula = "of:=SUM([.B2:.B6])", backgroundColor = HeaderFill),
                    OdfCell(text = "148,200", bold = true, formula = "of:=SUM([.C2:.C6])", backgroundColor = HeaderFill),
                    OdfCell(text = "400", bold = true, formula = "of:=C7-B7", backgroundColor = HeaderFill),
                )),
            ),
        ),
        OdfSheet(name = "Sites", rows = listOf(OdfRow(listOf(sheetCell("Site"), sheetCell("Lead"))))),
        OdfSheet(name = "Notes", rows = listOf(OdfRow(listOf(sheetCell("Reviewed 2025-09-30"))))),
    ),
)

/**
 * The value source the real view gets from the native formula engine. Here every cell is
 * already an evaluated literal, so this just reads it back.
 */
private class LiteralSpreadsheetValues(
    private val doc: OdfDocument.Spreadsheet,
) : SpreadsheetValues {
    override fun display(sheet: Int, row: Int, col: Int): String =
        doc.sheets.getOrNull(sheet)?.rows?.getOrNull(row)?.cells?.getOrNull(col)?.text ?: ""

    override fun isNumeric(sheet: Int, row: Int, col: Int): Boolean =
        display(sheet, row, col).replace(",", "").toDoubleOrNull() != null
}

private fun slideParagraph(text: String, style: ParagraphStyle = ParagraphStyle.BODY, size: Float? = null) =
    OdfParagraph(listOf(OdfSpan(text, fontSize = size)), style)

// Slide coordinates are px@96 inside the default 1058x794 ODF page.
private fun titleFrame(title: String) = OdfSlideElement.Frame(
    OdfFrame(80f, 90f, 900f, 120f, listOf(slideParagraph(title, ParagraphStyle.HEADING1, size = 40f)))
)

private fun bulletsFrame(lines: List<String>) = OdfSlideElement.Frame(
    OdfFrame(80f, 260f, 900f, 420f, lines.map { slideParagraph("•  $it", size = 22f) })
)

private val SamplePresentation = OdfDocument.Presentation(
    title = "Season Review.odp",
    slides = listOf(
        OdfSlide(
            name = "Title",
            elements = listOf(
                titleFrame("Season 2025 in review"),
                bulletsFrame(
                    listOf(
                        "Fourteen sites monitored fortnightly",
                        "94% of planned visits completed",
                        "Two independent teams, paired readings",
                        "One site flagged for follow-up",
                    )
                ),
            ),
        ),
        OdfSlide(
            name = "Method",
            elements = listOf(
                titleFrame("How the readings were paired"),
                bulletsFrame(
                    listOf(
                        "Both teams visited within 48 hours",
                        "Instruments recalibrated before each visit",
                        "Variance checked against the agreed tolerance",
                    )
                ),
            ),
        ),
        OdfSlide(
            name = "Results",
            elements = listOf(
                titleFrame("Results"),
                bulletsFrame(
                    listOf(
                        "Coverage 94%, up four points on 2024",
                        "Variance within tolerance at 13 of 14 sites",
                        "Site 11 scheduled for re-survey",
                    )
                ),
            ),
        ),
    ),
)

/**
 * Store listing images for `:office`. See `common-conventions-preview-metadata`.
 *
 * The old generator pushed three real sample files onto a device and opened each through an
 * `ACTION_VIEW` intent. These render the same three document kinds from the literal models
 * above, in the same order (text = 1, spreadsheet = 2, presentation = 3).
 *
 * The document body is wrapped in [OfficeLightTheme] exactly as `DocumentScreen` wraps it:
 * the app chrome is dark, but the rendered "paper" stays light. The editor's own chrome (top
 * bar, menu bar, format toolbar) is not here — it is welded to the OfficeViewModel and to
 * the native `office_engine`, which Layoutlib cannot load.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    /** The "paper" wrapper DocumentScreen puts around every rendered document. */
    @Composable
    private fun Paper(content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            OfficeLightTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
    }

    @PreviewTest
    @Preview(name = "1-document", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Document() {
        Paper { TextDocumentView(doc = SampleTextDocument) }
    }

    @PreviewTest
    @Preview(name = "2-spreadsheet", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Spreadsheet() {
        Paper {
            SpreadsheetView(
                doc = SampleSpreadsheet,
                values = LiteralSpreadsheetValues(SampleSpreadsheet),
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-presentation", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Presentation() {
        Paper { PresentationView(doc = SamplePresentation) }
    }

    @PreviewTest
    @Preview(name = "4-home", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Home() {
        DynamicTheme(darkTheme = true) {
            HomeScreen()
        }
    }
}
