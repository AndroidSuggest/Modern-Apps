package com.vayunmathur.photos.ui

import androidx.annotation.StringRes
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.ImageAdjustments

internal enum class ToolCategory(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    Adjust(R.string.tool_cat_adjust, R.string.tool_cat_desc_adjust),
    Filters(R.string.tool_cat_filters, R.string.tool_cat_desc_filters),
    Retouch(R.string.tool_cat_retouch, R.string.tool_cat_desc_retouch),
    Select(R.string.tool_cat_select, R.string.tool_cat_desc_select),
    Transform(R.string.tool_cat_transform, R.string.tool_cat_desc_transform),
    Draw(R.string.tool_cat_draw, R.string.tool_cat_desc_draw),
    Paint(R.string.tool_cat_paint, R.string.tool_cat_desc_paint),
    Layers(R.string.tool_cat_layers, R.string.tool_cat_desc_layers),
}

internal enum class EditorMode {
    None,
    Adjust, Filters, Curves, HSL, Levels, ColorBalance, ChannelMixer, BlackWhite, GradientMap,
    Vibrance, PhotoFilter, SelectiveColor, Posterize, Threshold, Invert,
    LensBlur, Selective, FilterFx, Liquify,
    Healing, RedEye, DodgeBurn, Smudge,
    Selection,
    Crop,
    FreeTransform,
    Layers,
    MaskPaint,
    Fill, GradientTool, ShapeRect, ShapeEllipse, ShapeLine, Eyedropper,
}

internal data class ToolEntry(val mode: EditorMode, @StringRes val labelRes: Int)

internal enum class SelectionTool(@StringRes val labelRes: Int) {
    Rectangle(R.string.sel_tool_rectangle), Ellipse(R.string.sel_tool_ellipse), Lasso(R.string.sel_tool_lasso), Polygon(R.string.sel_tool_polygon), Wand(R.string.sel_tool_wand),
}

internal val categoryTools: Map<ToolCategory, List<ToolEntry>> = mapOf(
    ToolCategory.Adjust to listOf(
        ToolEntry(EditorMode.Adjust, R.string.tool_light),
        ToolEntry(EditorMode.Filters, R.string.tool_presets),
        ToolEntry(EditorMode.Curves, R.string.tool_curves),
        ToolEntry(EditorMode.HSL, R.string.tool_hsl),
        ToolEntry(EditorMode.Levels, R.string.levels),
        ToolEntry(EditorMode.ColorBalance, R.string.tool_balance),
        ToolEntry(EditorMode.ChannelMixer, R.string.tool_mixer),
        ToolEntry(EditorMode.BlackWhite, R.string.tool_bw),
        ToolEntry(EditorMode.GradientMap, R.string.tool_gradient),
        ToolEntry(EditorMode.Vibrance, R.string.vibrance),
        ToolEntry(EditorMode.PhotoFilter, R.string.tool_photo_filter),
        ToolEntry(EditorMode.SelectiveColor, R.string.tool_selective_color),
        ToolEntry(EditorMode.Posterize, R.string.tool_posterize),
        ToolEntry(EditorMode.Threshold, R.string.tool_threshold),
        ToolEntry(EditorMode.Invert, R.string.tool_invert),
    ),
    ToolCategory.Filters to listOf(
        ToolEntry(EditorMode.LensBlur, R.string.tool_lens_blur),
        ToolEntry(EditorMode.Selective, R.string.tool_selective),
        ToolEntry(EditorMode.FilterFx, R.string.tool_filters),
        ToolEntry(EditorMode.Liquify, R.string.tool_liquify),
    ),
    ToolCategory.Retouch to listOf(
        ToolEntry(EditorMode.Healing, R.string.tool_heal),
        ToolEntry(EditorMode.RedEye, R.string.tool_red_eye),
        ToolEntry(EditorMode.DodgeBurn, R.string.tool_dodge_burn),
        ToolEntry(EditorMode.Smudge, R.string.tool_smudge),
    ),
    ToolCategory.Select to listOf(
        ToolEntry(EditorMode.Selection, R.string.tool_marquee),
    ),
    ToolCategory.Transform to listOf(
        ToolEntry(EditorMode.Crop, R.string.tool_crop_rotate),
        ToolEntry(EditorMode.FreeTransform, R.string.tool_transform),
    ),
    ToolCategory.Layers to listOf(
        ToolEntry(EditorMode.Layers, R.string.tool_cat_layers),
        ToolEntry(EditorMode.MaskPaint, R.string.tool_mask_brush),
    ),
    ToolCategory.Paint to listOf(
        ToolEntry(EditorMode.Fill, R.string.tool_fill),
        ToolEntry(EditorMode.GradientTool, R.string.tool_gradient),
        ToolEntry(EditorMode.ShapeRect, R.string.sel_tool_rectangle),
        ToolEntry(EditorMode.ShapeEllipse, R.string.sel_tool_ellipse),
        ToolEntry(EditorMode.ShapeLine, R.string.tool_line),
        ToolEntry(EditorMode.Eyedropper, R.string.tool_eyedropper),
    ),
)

internal enum class AdjustmentType(
    @StringRes val label: Int,
    val min: Float,
    val max: Float,
    val get: (ImageAdjustments) -> Float,
    val set: (ImageAdjustments, Float) -> ImageAdjustments,
) {
    Brightness(R.string.adj_brightness, -100f, 100f, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    Contrast(R.string.adj_contrast, -100f, 100f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    Saturation(R.string.adj_saturation, -100f, 100f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    Warmth(R.string.adj_warmth, -100f, 100f, { it.warmth }, { a, v -> a.copy(warmth = v) }),
    Exposure(R.string.adj_exposure, -100f, 100f, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    Highlights(R.string.adj_highlights, -100f, 100f, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    Shadows(R.string.adj_shadows, -100f, 100f, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    Sharpness(R.string.adj_sharpness, 0f, 100f, { it.sharpness }, { a, v -> a.copy(sharpness = v) }),
    Vignette(R.string.adj_vignette, 0f, 100f, { it.vignette }, { a, v -> a.copy(vignette = v) }),
    Grain(R.string.adj_grain, 0f, 100f, { it.grain }, { a, v -> a.copy(grain = v) }),
    Fade(R.string.adj_fade, 0f, 100f, { it.fade }, { a, v -> a.copy(fade = v) }),
    Tint(R.string.adj_tint, -100f, 100f, { it.tint }, { a, v -> a.copy(tint = v) }),
}

@StringRes
internal fun EditorMode.descriptionRes(): Int = when (this) {
    EditorMode.Adjust -> R.string.editor_desc_adjust
    EditorMode.Filters -> R.string.editor_desc_filters
    EditorMode.Curves -> R.string.editor_desc_curves
    EditorMode.HSL -> R.string.editor_desc_hsl
    EditorMode.Levels -> R.string.editor_desc_levels
    EditorMode.ColorBalance -> R.string.editor_desc_color_balance
    EditorMode.ChannelMixer -> R.string.editor_desc_channel_mixer
    EditorMode.BlackWhite -> R.string.editor_desc_black_white
    EditorMode.GradientMap -> R.string.editor_desc_gradient_map
    EditorMode.Vibrance -> R.string.editor_desc_vibrance
    EditorMode.PhotoFilter -> R.string.editor_desc_photo_filter
    EditorMode.SelectiveColor -> R.string.editor_desc_selective_color
    EditorMode.Posterize -> R.string.editor_desc_posterize
    EditorMode.Threshold -> R.string.editor_desc_threshold
    EditorMode.Invert -> R.string.editor_desc_invert
    EditorMode.LensBlur -> R.string.editor_desc_lens_blur
    EditorMode.Selective -> R.string.editor_desc_selective
    EditorMode.FilterFx -> R.string.editor_desc_filter_fx
    EditorMode.Liquify -> R.string.editor_desc_liquify
    EditorMode.Healing -> R.string.editor_desc_healing
    EditorMode.RedEye -> R.string.editor_desc_red_eye
    EditorMode.DodgeBurn -> R.string.editor_desc_dodge_burn
    EditorMode.Smudge -> R.string.editor_desc_smudge
    EditorMode.Selection -> R.string.editor_desc_selection
    EditorMode.Crop -> R.string.editor_desc_crop
    EditorMode.FreeTransform -> R.string.editor_desc_free_transform
    EditorMode.Layers -> R.string.editor_desc_layers
    EditorMode.MaskPaint -> R.string.editor_desc_mask_paint
    EditorMode.Fill -> R.string.editor_desc_fill
    EditorMode.GradientTool -> R.string.editor_desc_gradient_tool
    EditorMode.ShapeRect -> R.string.editor_desc_shape_rect
    EditorMode.ShapeEllipse -> R.string.editor_desc_shape_ellipse
    EditorMode.ShapeLine -> R.string.editor_desc_shape_line
    EditorMode.Eyedropper -> R.string.tap_the_image_to_pick_a_color
    EditorMode.None -> R.string.editor_desc_none
}
