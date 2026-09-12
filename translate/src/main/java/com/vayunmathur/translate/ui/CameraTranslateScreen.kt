package com.vayunmathur.translate.ui

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.translate.R
import com.vayunmathur.translate.platform.TranslateViewModel

@Composable
fun CameraTranslateScreen(
    viewModel: TranslateViewModel,
    onBack: () -> Unit,
    onOpenLanguagePicker: (Boolean) -> Unit,
) {
    PermissionsChecker(
        permissions = arrayOf(Manifest.permission.CAMERA),
        text = stringResource(R.string.grant_camera_access),
    ) {
        CameraContent(viewModel, onBack, onOpenLanguagePicker)
    }
}

@Composable
private fun CameraContent(
    viewModel: TranslateViewModel,
    onBack: () -> Unit,
    onOpenLanguagePicker: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember {
        // The overlay maths below assumes a centre-crop preview; make that explicit
        // rather than relying on PreviewView's default.
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    CameraPreviewLayer(
        viewModel = viewModel,
        previewView = previewView,
        onBack = onBack,
        onOpenLanguagePicker = onOpenLanguagePicker,
    )
}
