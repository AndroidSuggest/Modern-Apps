# AGENTS.md — office/ (':office')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `office/` + allowed shared modules. Do not root-scan.

_Install: ./install office (dev by default)._

- Gradle: :office / dir: office/
- Package roots present: ui
- Entry files: MainActivity.kt
- Deps: :library:network, :library:e2ee-p2p
- Metadata: metadata_data/office.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/office/MainActivity.kt
- com/vayunmathur/office/OfficeDocumentFindBars.kt
- com/vayunmathur/office/OfficeDocumentLaunchers.kt
- com/vayunmathur/office/OfficeDocumentMenuBar.kt
- com/vayunmathur/office/OfficeDocumentOverlays.kt
- com/vayunmathur/office/OfficeDocumentPane.kt
- com/vayunmathur/office/OfficeDocumentState.kt
- com/vayunmathur/office/OfficeDocumentUtils.kt
- com/vayunmathur/office/OfficeEnableOnlineDialog.kt
- com/vayunmathur/office/OfficeHomeScreens.kt
- com/vayunmathur/office/OfficeOnlineScreens.kt
- com/vayunmathur/office/OfficeOutlinePane.kt
- com/vayunmathur/office/odf/DocumentImporter.kt
- com/vayunmathur/office/odf/ExcelFormula.kt
- com/vayunmathur/office/odf/ExcelNumFmt.kt
- com/vayunmathur/office/odf/OdfMath.kt
- com/vayunmathur/office/odf/OdfParser.kt
- com/vayunmathur/office/odf/OdfParserChart.kt
- com/vayunmathur/office/odf/OdfParserInline.kt
- com/vayunmathur/office/odf/OdfParserSheet.kt
- com/vayunmathur/office/odf/OdfParserSlides.kt
- com/vayunmathur/office/odf/OdfParserStyles.kt
- com/vayunmathur/office/odf/OdfParserText.kt
- com/vayunmathur/office/odf/OdfWriter.kt
- com/vayunmathur/office/odf/OmmlToMathml.kt
- com/vayunmathur/office/odf/OoxmlChart.kt
- com/vayunmathur/office/odf/OoxmlColor.kt
- com/vayunmathur/office/odf/OoxmlDiagram.kt
- com/vayunmathur/office/odf/OoxmlDocx.kt
- com/vayunmathur/office/odf/OoxmlDocxStyles.kt
- com/vayunmathur/office/odf/OoxmlImporter.kt
- com/vayunmathur/office/odf/OoxmlMetadata.kt
- com/vayunmathur/office/odf/OoxmlPackage.kt
- com/vayunmathur/office/odf/OoxmlPptx.kt
- com/vayunmathur/office/odf/OoxmlTheme.kt
- com/vayunmathur/office/odf/OoxmlUnits.kt
- com/vayunmathur/office/odf/OoxmlXlsx.kt
- com/vayunmathur/office/odf/OoxmlXlsxHelper.kt
- com/vayunmathur/office/odf/OoxmlXml.kt
- com/vayunmathur/office/ui/AddBookmarkDialog.kt
- com/vayunmathur/office/ui/ChartEditorDialog.kt
- com/vayunmathur/office/ui/ColorPickerDialog.kt
- com/vayunmathur/office/ui/CommentDialog.kt
- com/vayunmathur/office/ui/DocumentViews.kt
- com/vayunmathur/office/ui/FloatingElementViews.kt
- com/vayunmathur/office/ui/FontSizePickerDialog.kt
- com/vayunmathur/office/ui/FootnoteDialog.kt
- com/vayunmathur/office/ui/GoToSlideDialog.kt
- com/vayunmathur/office/ui/HeaderFooterDialog.kt
- com/vayunmathur/office/ui/InsertHyperlinkDialog.kt
- com/vayunmathur/office/ui/InsertTableDialog.kt
- com/vayunmathur/office/ui/MathViews.kt
- com/vayunmathur/office/ui/OdfChartViews.kt
- com/vayunmathur/office/ui/OdfImageViews.kt
- com/vayunmathur/office/ui/OfficeBottomBar.kt
- com/vayunmathur/office/ui/OfficeCellFormat.kt
- com/vayunmathur/office/ui/OfficeEditorWideLayout.kt
- com/vayunmathur/office/ui/OfficeTextFormat.kt
- com/vayunmathur/office/ui/ParagraphViews.kt
- com/vayunmathur/office/ui/PresentationViews.kt
- com/vayunmathur/office/ui/SettingsDialog.kt
- com/vayunmathur/office/ui/SortDialog.kt
- com/vayunmathur/office/ui/SpecialCharsDialog.kt
- com/vayunmathur/office/ui/SpreadsheetViews.kt
- com/vayunmathur/office/ui/TextDocumentView.kt
- com/vayunmathur/office/util/DocumentTreeCrdt.kt
- com/vayunmathur/office/util/OfficeNative.kt
- com/vayunmathur/office/util/OfficeSync.kt
- com/vayunmathur/office/util/OfficeViewModel.kt
- com/vayunmathur/office/util/OfficeViewModelDocuments.kt
- com/vayunmathur/office/util/OfficeViewModelExport.kt
- com/vayunmathur/office/util/OfficeViewModelSheet.kt
- com/vayunmathur/office/util/OfficeViewModelSlides.kt
- com/vayunmathur/office/util/OfficeViewModelSyncA.kt
- com/vayunmathur/office/util/OfficeViewModelSyncB.kt
- com/vayunmathur/office/util/OfficeViewModelSyncC.kt
- com/vayunmathur/office/util/OfficeViewModelTables.kt
- com/vayunmathur/office/util/OfficeViewModelText.kt

## Verify (this module only)
```
./gradlew :office:compileDevKotlin
./gradlew :office:lint
./gradlew :office:checkMetadata
```


