# AGENTS.md — pdf/ (':pdf')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `pdf/` + allowed shared modules. Do not root-scan.

_Install: ./install pdf (dev by default)._

- Gradle: :pdf / dir: pdf/
- Package roots present: ui
- Entry files: MainActivity.kt
- Deps: :library:image, :library:ocr
- Metadata: metadata_data/pdf.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/pdf/MainActivity.kt
- com/vayunmathur/pdf/model/CapturedImage.kt
- com/vayunmathur/pdf/ui/CapturePdfScreen.kt
- com/vayunmathur/pdf/ui/CropScreen.kt
- com/vayunmathur/pdf/ui/CropScreenSection.kt
- com/vayunmathur/pdf/ui/CutGlueScreen.kt
- com/vayunmathur/pdf/ui/PdfOutlineDrawer.kt
- com/vayunmathur/pdf/ui/PdfViewerWideLayout.kt
- com/vayunmathur/pdf/ui/SafePdfBlendModes.kt
- com/vayunmathur/pdf/ui/SafePdfDrawContext.kt
- com/vayunmathur/pdf/ui/SafePdfDrawFills.kt
- com/vayunmathur/pdf/ui/SafePdfDrawImages.kt
- com/vayunmathur/pdf/ui/SafePdfDrawState.kt
- com/vayunmathur/pdf/ui/SafePdfDrawStrokes.kt
- com/vayunmathur/pdf/ui/SafePdfDrawText.kt
- com/vayunmathur/pdf/ui/SafePdfEditOverlay.kt
- com/vayunmathur/pdf/ui/SafePdfEditPreview.kt
- com/vayunmathur/pdf/ui/SafePdfEditToolbar.kt
- com/vayunmathur/pdf/ui/SafePdfFormFields.kt
- com/vayunmathur/pdf/ui/SafePdfLaunchers.kt
- com/vayunmathur/pdf/ui/SafePdfNonEditOverlay.kt
- com/vayunmathur/pdf/ui/SafePdfPageCanvas.kt
- com/vayunmathur/pdf/ui/SafePdfPageItem.kt
- com/vayunmathur/pdf/ui/SafePdfPageViewer.kt
- com/vayunmathur/pdf/ui/SafePdfSelectionGlyphs.kt
- com/vayunmathur/pdf/ui/SafePdfStyleDialog.kt
- com/vayunmathur/pdf/ui/SafePdfTextSelection.kt
- com/vayunmathur/pdf/ui/SafePdfToolMenus.kt
- com/vayunmathur/pdf/ui/SafePdfViewerChrome.kt
- com/vayunmathur/pdf/ui/SafePdfViewerDialogs.kt
- com/vayunmathur/pdf/ui/SafePdfViewerLoad.kt
- com/vayunmathur/pdf/ui/SafePdfViewerModels.kt
- com/vayunmathur/pdf/ui/SafePdfViewerScreen.kt
- com/vayunmathur/pdf/ui/SafePdfViewerState.kt
- com/vayunmathur/pdf/ui/SafePdfZoomState.kt
- com/vayunmathur/pdf/ui/components/CameraPreview.kt
- com/vayunmathur/pdf/ui/components/SubcroppedImage.kt
- com/vayunmathur/pdf/util/AutoFrameDetector.kt
- com/vayunmathur/pdf/util/ComposePdfDocument.kt
- com/vayunmathur/pdf/util/PdfExporter.kt
- com/vayunmathur/pdf/util/PdfNative.kt
- com/vayunmathur/pdf/util/PdfStateStore.kt
- com/vayunmathur/pdf/util/PdfViewModel.kt
- com/vayunmathur/pdf/util/SafePdfDocument.kt
- com/vayunmathur/pdf/util/SafePdfImages.kt
- com/vayunmathur/pdf/util/SafePdfListings.kt
- com/vayunmathur/pdf/util/SafePdfModels.kt
- com/vayunmathur/pdf/util/SafePdfParser.kt

## Verify (this module only)
```
./gradlew :pdf:compileDevKotlin
./gradlew :pdf:lint
./gradlew :pdf:checkMetadata
```


