# AGENTS.md — camera/ (':camera')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `camera/` + allowed shared modules. Do not root-scan.

_Install: ./install camera (dev by default)._

- Gradle: :camera / dir: camera/
- Package roots present: ui, domain, platform
- Entry files: MainActivity.kt
- Deps: :library:ml
- Metadata: metadata_data/camera.md (present)
- Rust: yes / screenshotTest: no

## Key files

- com/vayunmathur/camera/MainActivity.kt
- com/vayunmathur/camera/domain/LensModel.kt
- com/vayunmathur/camera/domain/LensSelectionLogic.kt
- com/vayunmathur/camera/platform/CameraCapabilities.kt
- com/vayunmathur/camera/platform/LensSelector.kt
- com/vayunmathur/camera/ui/CameraBottomControls.kt
- com/vayunmathur/camera/ui/CameraModeBars.kt
- com/vayunmathur/camera/ui/CameraModesPane.kt
- com/vayunmathur/camera/ui/CameraOverlays.kt
- com/vayunmathur/camera/ui/CameraPanoramaOverlay.kt
- com/vayunmathur/camera/ui/CameraPreviewBox.kt
- com/vayunmathur/camera/ui/CameraPreviewOverlays.kt
- com/vayunmathur/camera/ui/CameraScreen.kt
- com/vayunmathur/camera/ui/CameraScreenEffects.kt
- com/vayunmathur/camera/ui/CameraScreenKit.kt
- com/vayunmathur/camera/ui/CameraScreenState.kt
- com/vayunmathur/camera/ui/CameraSettingsBars.kt
- com/vayunmathur/camera/ui/CameraSettingsColumn.kt
- com/vayunmathur/camera/ui/CameraShutterPane.kt
- com/vayunmathur/camera/ui/CameraShutterRow.kt
- com/vayunmathur/camera/ui/CameraStatusIndicators.kt
- com/vayunmathur/camera/ui/CameraTopBar.kt
- com/vayunmathur/camera/ui/CameraWideLayout.kt
- com/vayunmathur/camera/ui/LensBar.kt
- com/vayunmathur/camera/ui/SettingsPage.kt
- com/vayunmathur/camera/util/BokehAnalyzer.kt
- com/vayunmathur/camera/util/CameraCaptureProcessing.kt
- com/vayunmathur/camera/util/CameraControls.kt
- com/vayunmathur/camera/util/CameraManualControls.kt
- com/vayunmathur/camera/util/CameraNightCapture.kt
- com/vayunmathur/camera/util/CameraNightMode.kt
- com/vayunmathur/camera/util/CameraPhotoCapture.kt
- com/vayunmathur/camera/util/CameraPhotoSessions.kt
- com/vayunmathur/camera/util/CameraSessions.kt
- com/vayunmathur/camera/util/CameraSettings.kt
- com/vayunmathur/camera/util/CameraSingleCapture.kt
- com/vayunmathur/camera/util/CameraSloMoSession.kt
- com/vayunmathur/camera/util/CameraStartup.kt
- com/vayunmathur/camera/util/CameraVideoRecording.kt
- com/vayunmathur/camera/util/CameraVideoSession.kt
- com/vayunmathur/camera/util/CameraViewModel.kt
- com/vayunmathur/camera/util/CodecSupport.kt
- com/vayunmathur/camera/util/GpuStitcher.kt
- com/vayunmathur/camera/util/MediaStoreSaver.kt
- com/vayunmathur/camera/util/MotionPhotoEncoder.kt
- com/vayunmathur/camera/util/MotionPhotoWriter.kt
- com/vayunmathur/camera/util/NightCaptureEngine.kt
- com/vayunmathur/camera/util/PanoramaEngine.kt
- com/vayunmathur/camera/util/PanoXmp.kt
- com/vayunmathur/camera/util/PhotoAnalyzer.kt
- com/vayunmathur/camera/util/PortraitBokeh.kt
- com/vayunmathur/camera/util/StitchNative.kt
- com/vayunmathur/camera/util/VideoProcessor.kt

## Verify (this module only)
```
./gradlew :camera:compileDevKotlin
./gradlew :camera:lint
./gradlew :camera:checkMetadata
```


