package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.odf.OdfParagraph

@Composable
internal fun ElementHandle(
    modifier: Modifier,
    icon: (@Composable () -> Unit)? = null,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDrag: (Float, Float) -> Unit) {
    Box(
        modifier.size(18.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() }
                ) { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) icon()
    }
}

@Composable
internal fun SlideTextEditor(
    key: String,
    paragraphs: List<OdfParagraph>,
    fontScale: Float,
    onChange: (String) -> Unit,
    onFocus: () -> Unit = {}) {
    val initial = paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember(key) { mutableStateOf(TextFieldValue(initial)) }
    val baseSp = (paragraphs.firstOrNull()?.spans?.firstOrNull()?.fontSize ?: 18f) * fontScale
    val bold = paragraphs.firstOrNull()?.spans?.firstOrNull()?.bold == true
    val italic = paragraphs.firstOrNull()?.spans?.firstOrNull()?.italic == true
    val color = paragraphs.firstOrNull()?.spans?.firstOrNull()?.color?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = tfv,
        onValueChange = { tfv = it; onChange(it.text) },
        textStyle = TextStyle(
            color = color,
            fontSize = baseSp.coerceAtLeast(8f).sp,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
    )
}
