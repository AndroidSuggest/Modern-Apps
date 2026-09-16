package com.vayunmathur.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BlackAndWhiteAdj
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.ChannelMixerAdj
import com.vayunmathur.photos.data.ColorBalanceAdj
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.HslAdj
import com.vayunmathur.photos.data.InvertAdj
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.PosterizeAdj
import com.vayunmathur.photos.data.SelectiveAdj
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.data.ThresholdAdj
import com.vayunmathur.photos.data.VibranceAdj

// Ensure the right layer is active for the selected tool.
@Composable
internal fun EditPhotoModeEffect(state: EditPhotoEditorState) {
    LaunchedEffect(state.editorMode) {
        when (state.editorMode) {
            EditorMode.Adjust, EditorMode.Filters ->
                state.vm.ensureAdjustment({ it is BasicAdjustment }, { BasicAdjustment() })
            EditorMode.Curves -> state.vm.ensureAdjustment({ it is CurvesAdj }, { CurvesAdj() })
            EditorMode.HSL -> state.vm.ensureAdjustment({ it is HslAdj }, { HslAdj() })
            EditorMode.Levels -> state.vm.ensureAdjustment({ it is LevelsAdj }, { LevelsAdj() })
            EditorMode.ColorBalance -> state.vm.ensureAdjustment({ it is ColorBalanceAdj }, { ColorBalanceAdj() })
            EditorMode.ChannelMixer -> state.vm.ensureAdjustment({ it is ChannelMixerAdj }, { ChannelMixerAdj() })
            EditorMode.BlackWhite -> state.vm.ensureAdjustment({ it is BlackAndWhiteAdj }, { BlackAndWhiteAdj() })
            EditorMode.Vibrance -> state.vm.ensureAdjustment({ it is VibranceAdj }, { VibranceAdj() })
            EditorMode.PhotoFilter -> state.vm.ensureAdjustment({ it is PhotoFilterAdj }, { PhotoFilterAdj() })
            EditorMode.SelectiveColor -> state.vm.ensureAdjustment({ it is SelectiveColorAdj }, { SelectiveColorAdj() })
            EditorMode.Posterize -> state.vm.ensureAdjustment({ it is PosterizeAdj }, { PosterizeAdj() })
            EditorMode.Threshold -> state.vm.ensureAdjustment({ it is ThresholdAdj }, { ThresholdAdj() })
            EditorMode.Invert -> state.vm.ensureAdjustment({ it is InvertAdj }, { InvertAdj() })
            EditorMode.LensBlur -> state.vm.ensureAdjustment({ it is BlurAdj }, { BlurAdj() })
            EditorMode.Selective -> state.vm.ensureAdjustment({ it is SelectiveAdj }, { SelectiveAdj() })
            EditorMode.Healing, EditorMode.RedEye, EditorMode.DodgeBurn, EditorMode.Smudge, EditorMode.FilterFx, EditorMode.Liquify,
            EditorMode.Fill, EditorMode.GradientTool, EditorMode.ShapeRect, EditorMode.ShapeEllipse, EditorMode.ShapeLine -> {
                val idx = state.document.layers.indexOfLast { it is PixelLayer }
                if (idx >= 0 && idx != state.document.activeLayerIndex) state.vm.setActiveLayer(idx)
            }
            EditorMode.FreeTransform -> {
                val idx = state.document.layers.indexOfLast { it is PixelLayer }
                if (idx >= 0 && idx != state.document.activeLayerIndex) state.vm.setActiveLayer(idx)
                state.ftTL = Offset(0f, 0f); state.ftTR = Offset(1f, 0f); state.ftBL = Offset(0f, 1f); state.ftBR = Offset(1f, 1f)
            }
            else -> {}
        }
    }
}
