package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vayunmathur.office.R

// --- Presentation / slides / floating objects (split from OfficeViewModel.kt for file length) ---

fun OfficeViewModel.addSlide(afterIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    slides.add(afterIndex + 1, OdfSlide(
        name = "Slide ${slides.size + 1}",
        elements = listOf(OdfSlideElement.Frame(OdfFrame(
            x = 50f, y = 50f, width = 600f, height = 100f,
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New Slide", bold = true, fontSize = 28f))))
        )))
    ))
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.deleteSlide(index: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    if (doc.slides.size <= 1) return
    val slides = doc.slides.toMutableList()
    slides.removeAt(index)
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.duplicateSlide(index: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    slides.add(index + 1, slides[index].copy(name = "${slides[index].name} (copy)"))
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.moveSlideUp(index: Int) {
    if (index <= 0) return
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val item = slides.removeAt(index)
    slides.add(index - 1, item)
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.moveSlideDown(index: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    if (index >= doc.slides.size - 1) return
    val slides = doc.slides.toMutableList()
    val item = slides.removeAt(index)
    slides.add(index + 1, item)
    updateDocument(doc.copy(slides = slides))
}

/** Edits the text of a slide element (frame or shape), preserving the first span's formatting (I62). */
fun OfficeViewModel.updateSlideElementText(slideIndex: Int, elementIndex: Int, newText: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    fun rebuild(old: List<OdfParagraph>): List<OdfParagraph> {
        val template = old.firstOrNull()?.spans?.firstOrNull()?.copy(text = "") ?: OdfSpan(text = "")
        val style = old.firstOrNull()?.style ?: ParagraphStyle.BODY
        return newText.split("\n").map { line -> OdfParagraph(listOf(template.copy(text = line)), style = style) }
    }
    elements[elementIndex] = when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = rebuild(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val s = el.shape
            val t = rebuild(s.text)
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(text = t)
                is OdfShape.Ellipse -> s.copy(text = t)
                is OdfShape.Line -> s.copy(text = t)
                is OdfShape.CustomShape -> s.copy(text = t)
                is OdfShape.Polyline -> s.copy(text = t)
            })
        }
    }
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Adds an empty text box to a slide (I62). */
fun OfficeViewModel.addTextBoxToSlide(slideIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val frame = OdfFrame(
        x = 60f, y = 200f + slide.elements.size * 20f, width = 600f, height = 80f,
        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New text", fontSize = 20f))))
    )
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(slides = slides))
}

/** Adds a shape to a slide at a default centered rect. kind = "rect"|"ellipse"|"line". (Phase 3) */
fun OfficeViewModel.addShapeToSlide(slideIndex: Int, kind: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val x = 329f; val y = 247f; val w = 400f; val h = 300f
    val shape: OdfShape = when (kind) {
        "ellipse" -> OdfShape.Ellipse(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
        "line" -> OdfShape.Line(x, y, w, 0f, strokeColor = 0xFF333333L, strokeWidth = 2f, x2 = x + w, y2 = y)
        else -> OdfShape.Rect(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
    }
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Shape(shape))
    updateDocument(doc.copy(slides = slides))
}

/** Inserts an image as a floating frame on a slide. (Phase 3) */
fun OfficeViewModel.insertImageIntoSlide(slideIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val frame = OdfFrame(x = 300f, y = 200f, width = 400f, height = 300f, paragraphs = emptyList(),
        image = OdfImage(path = path, imageData = bytes))
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(slides = slides, images = doc.images + (path to bytes)))
}

/** Inserts a chart as a floating frame on a slide. (Phase 3) */
fun OfficeViewModel.insertChartIntoSlide(slideIndex: Int, chart: OdfChart) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val frame = OdfFrame(x = 250f, y = 180f, width = 520f, height = 360f, paragraphs = emptyList(), chart = chart)
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(slides = slides))
}

// --- Slide chrome (background / transition / speaker notes) ---

internal fun OfficeViewModel.mutateSlide(slideIndex: Int, transform: (OdfSlide) -> OdfSlide) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    slides[slideIndex] = transform(slide)
    updateDocument(doc.copy(slides = slides))
}

/** Sets speaker notes for a slide (one paragraph per line; blank clears them). */
fun OfficeViewModel.setSlideNotes(slideIndex: Int, text: String) = mutateSlide(slideIndex) { slide ->
    slide.copy(notes = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(it))) })
}

/** Current speaker-notes text for a slide, joined by newlines. */
fun OfficeViewModel.slideNotesText(slideIndex: Int): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return ""
    return doc.slides.getOrNull(slideIndex)?.notes?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: ""
}

/** Sets (or clears with null) the solid background color of a slide. */
fun OfficeViewModel.setSlideBackgroundColor(slideIndex: Int, color: Long?) = mutateSlide(slideIndex) { it.copy(backgroundColor = color) }

/** Sets the slide transition type (e.g. "fade","wipe","dissolve"; null clears) and speed. */
fun OfficeViewModel.setSlideTransition(slideIndex: Int, type: String?, speed: String?) =
    mutateSlide(slideIndex) { it.copy(transitionType = type, transitionSpeed = speed) }

/** Rotates ANY slide element by delta degrees: shapes rotate via their angle, image frames via the image. */
fun OfficeViewModel.setSlideElementRotation(slideIndex: Int, elementIndex: Int, deltaDegrees: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    elements[elementIndex] = rotateElement(el, deltaDegrees)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

internal fun OfficeViewModel.rotateElement(el: OdfSlideElement, delta: Float): OdfSlideElement = when (el) {
    is OdfSlideElement.Frame -> el.frame.image?.let {
        OdfSlideElement.Frame(el.frame.copy(image = it.copy(rotationDegrees = (it.rotationDegrees + delta) % 360f)))
    } ?: el
    is OdfSlideElement.Shape -> {
        val s = el.shape; val nd = (s.rotationDegrees + delta) % 360f
        OdfSlideElement.Shape(when (s) {
            is OdfShape.Rect -> s.copy(rotationDegrees = nd)
            is OdfShape.Ellipse -> s.copy(rotationDegrees = nd)
            is OdfShape.Line -> s.copy(rotationDegrees = nd)
            is OdfShape.CustomShape -> s.copy(rotationDegrees = nd)
            is OdfShape.Polyline -> s.copy(rotationDegrees = nd)
        })
    }
}

// --- Spreadsheet cell comments & sizing ---

/** Sets (or clears with blank text) a comment/note on a cell. */
fun OfficeViewModel.setCellComment(sheetIndex: Int, rowIndex: Int, cellIndex: Int, author: String, text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) {
        it.copy(annotation = if (text.isBlank()) null else OdfAnnotation(
            author = author.ifBlank { null },
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text))))
        ))
    }
}

/** Current comment text on a cell, or "". */
fun OfficeViewModel.cellCommentText(sheetIndex: Int, rowIndex: Int, cellIndex: Int): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return ""
    val cell = doc.sheets.getOrNull(sheetIndex)?.rows?.getOrNull(rowIndex)?.cells?.getOrNull(cellIndex) ?: return ""
    return cell.annotation?.paragraphs?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: ""
}

/** Sets a column width in px@96 (null resets to default). */
fun OfficeViewModel.setColumnWidth(sheetIndex: Int, col: Int, widthPx: Float?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val widths = sheet.columnWidths.toMutableList()
    while (widths.size <= col) widths.add(null)
    widths[col] = widthPx
    sheets[sheetIndex] = sheet.copy(columnWidths = widths)
    updateDocument(doc.copy(sheets = sheets))
}

/** Sets a row height in px@96 (null resets to default). */
fun OfficeViewModel.setRowHeight(sheetIndex: Int, row: Int, heightPx: Float?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val heights = sheet.rowHeights.toMutableList()
    while (heights.size <= row) heights.add(null)
    heights[row] = heightPx
    sheets[sheetIndex] = sheet.copy(rowHeights = heights)
    updateDocument(doc.copy(sheets = sheets))
}

// --- Spreadsheet floating objects (Phase 4) ---

internal fun OfficeViewModel.mutateSheetFloating(sheetIndex: Int, transform: (List<OdfSlideElement>) -> List<OdfSlideElement>) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    sheets[sheetIndex] = sheet.copy(floating = transform(sheet.floating))
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.addShapeToSheet(sheetIndex: Int, kind: String) {
    val x = 120f; val y = 120f; val w = 300f; val h = 200f
    val shape: OdfShape = when (kind) {
        "ellipse" -> OdfShape.Ellipse(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
        "line" -> OdfShape.Line(x, y, w, 0f, strokeColor = 0xFF333333L, strokeWidth = 2f, x2 = x + w, y2 = y)
        else -> OdfShape.Rect(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
    }
    mutateSheetFloating(sheetIndex) { it + OdfSlideElement.Shape(shape) }
}

fun OfficeViewModel.insertImageIntoSheet(sheetIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val frame = OdfFrame(x = 100f, y = 100f, width = 320f, height = 240f, paragraphs = emptyList(),
        image = OdfImage(path = path, imageData = bytes))
    sheets[sheetIndex] = sheet.copy(floating = sheet.floating + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(sheets = sheets, images = doc.images + (path to bytes)))
}

fun OfficeViewModel.insertChartIntoSheet(sheetIndex: Int, chart: OdfChart) {
    val frame = OdfFrame(x = 80f, y = 80f, width = 480f, height = 320f, paragraphs = emptyList(), chart = chart)
    mutateSheetFloating(sheetIndex) { it + OdfSlideElement.Frame(frame) }
}

fun OfficeViewModel.setSheetElementBounds(sheetIndex: Int, elementIndex: Int, x: Float, y: Float, w: Float, h: Float) {
    mutateSheetFloating(sheetIndex) { list ->
        if (elementIndex !in list.indices) list
        else list.toMutableList().also { it[elementIndex] = setElementBounds(it[elementIndex], x, y, w, h) }
    }
}

fun OfficeViewModel.deleteSheetElement(sheetIndex: Int, elementIndex: Int) {
    mutateSheetFloating(sheetIndex) { list -> list.filterIndexed { i, _ -> i != elementIndex } }
}

fun OfficeViewModel.updateSheetElementText(sheetIndex: Int, elementIndex: Int, newText: String) {
    mutateSheetFloating(sheetIndex) { list ->
        if (elementIndex !in list.indices) return@mutateSheetFloating list
        list.toMutableList().also { it[elementIndex] = setSlideElementTextOn(it[elementIndex], newText) }
    }
}

internal fun OfficeViewModel.setSlideElementTextOn(el: OdfSlideElement, newText: String): OdfSlideElement {
    fun rebuild(old: List<OdfParagraph>): List<OdfParagraph> {
        val template = old.firstOrNull()?.spans?.firstOrNull()?.copy(text = "") ?: OdfSpan(text = "")
        val style = old.firstOrNull()?.style ?: ParagraphStyle.BODY
        return newText.split("\n").map { line -> OdfParagraph(listOf(template.copy(text = line)), style = style) }
    }
    return when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = rebuild(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val s = el.shape; val t = rebuild(s.text)
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(text = t); is OdfShape.Ellipse -> s.copy(text = t)
                is OdfShape.Line -> s.copy(text = t); is OdfShape.CustomShape -> s.copy(text = t)
                is OdfShape.Polyline -> s.copy(text = t)
            })
        }
    }
}

internal fun OfficeViewModel.elementParas(el: OdfSlideElement): List<OdfParagraph> = when (el) {
    is OdfSlideElement.Frame -> el.frame.paragraphs
    is OdfSlideElement.Shape -> el.shape.text
}

internal fun OfficeViewModel.mutateSlideElementSpans(slideIndex: Int, elementIndex: Int, transform: (OdfSpan) -> OdfSpan) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    fun map(ps: List<OdfParagraph>) = ps.map { p -> p.copy(spans = p.spans.map(transform)) }
    elements[elementIndex] = when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val s = el.shape; val t = map(s.text)
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(text = t); is OdfShape.Ellipse -> s.copy(text = t)
                is OdfShape.Line -> s.copy(text = t); is OdfShape.CustomShape -> s.copy(text = t)
                is OdfShape.Polyline -> s.copy(text = t)
            })
        }
    }
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

internal fun OfficeViewModel.firstSpan(slideIndex: Int, elementIndex: Int): OdfSpan? {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return null
    val el = doc.slides.getOrNull(slideIndex)?.elements?.getOrNull(elementIndex) ?: return null
    return elementParas(el).firstOrNull()?.spans?.firstOrNull()
}

fun OfficeViewModel.toggleSlideElementBold(s: Int, e: Int) { val cur = firstSpan(s, e)?.bold == true; mutateSlideElementSpans(s, e) { it.copy(bold = !cur) } }
fun OfficeViewModel.toggleSlideElementItalic(s: Int, e: Int) { val cur = firstSpan(s, e)?.italic == true; mutateSlideElementSpans(s, e) { it.copy(italic = !cur) } }
fun OfficeViewModel.toggleSlideElementUnderline(s: Int, e: Int) { val cur = firstSpan(s, e)?.underline == true; mutateSlideElementSpans(s, e) { it.copy(underline = !cur) } }
fun OfficeViewModel.setSlideElementColor(s: Int, e: Int, color: Long?) { mutateSlideElementSpans(s, e) { it.copy(color = color) } }

/** Sets paragraph alignment for all paragraphs in a slide element. */
fun OfficeViewModel.setSlideElementAlignment(slideIndex: Int, elementIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    fun map(ps: List<OdfParagraph>) = ps.map { it.copy(alignment = alignment) }
    elements[elementIndex] = when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val sh = el.shape; val t = map(sh.text)
            OdfSlideElement.Shape(when (sh) {
                is OdfShape.Rect -> sh.copy(text = t); is OdfShape.Ellipse -> sh.copy(text = t)
                is OdfShape.Line -> sh.copy(text = t); is OdfShape.CustomShape -> sh.copy(text = t)
                is OdfShape.Polyline -> sh.copy(text = t)
            })
        }
    }
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Moves/resizes a slide element (frame or shape). Coordinates are px@96. (Phase 1) */
fun OfficeViewModel.setSlideElementBounds(slideIndex: Int, elementIndex: Int, x: Float, y: Float, w: Float, h: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    elements[elementIndex] = setElementBounds(el, x, y, w, h)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Removes a slide element. (Phase 1) */
fun OfficeViewModel.deleteSlideElement(slideIndex: Int, elementIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    if (elementIndex !in slide.elements.indices) return
    val elements = slide.elements.toMutableList()
    elements.removeAt(elementIndex)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Duplicates a slide element, offset slightly so it's visible. (C1) */
fun OfficeViewModel.duplicateSlideElement(slideIndex: Int, elementIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val el = slide.elements.getOrNull(elementIndex) ?: return
    val b = el.bounds()
    val copy = setElementBounds(el, b[0] + 20f, b[1] + 20f, b[2], b[3])
    val elements = slide.elements.toMutableList()
    elements.add(elementIndex + 1, copy)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Reorders a slide element in document order (= ODF render z-order). delta<0 = back, delta>0 = front. (C1) */
fun OfficeViewModel.reorderSlideElement(slideIndex: Int, elementIndex: Int, toFront: Boolean) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    if (elementIndex !in slide.elements.indices) return
    val elements = slide.elements.toMutableList()
    val item = elements.removeAt(elementIndex)
    if (toFront) elements.add(item) else elements.add(0, item)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Rotates an image element on a slide by the given delta degrees. (C4) */
fun OfficeViewModel.rotateSlideImage(slideIndex: Int, elementIndex: Int, deltaDegrees: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val el = slide.elements.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
    val img = el.frame.image ?: return
    val elements = slide.elements.toMutableList()
    elements[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = img.copy(rotationDegrees = (img.rotationDegrees + deltaDegrees) % 360f)))
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Rotates a text-document image block by delta degrees. (C4) */
fun OfficeViewModel.rotateTextImage(blockIndex: Int, deltaDegrees: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Image(block.image.copy(rotationDegrees = (block.image.rotationDegrees + deltaDegrees) % 360f))
    updateDocument(doc.copy(content = content))
}

/** Rotates an image element on a sheet's floating layer by delta degrees. (C4) */
fun OfficeViewModel.rotateSheetImage(sheetIndex: Int, elementIndex: Int, deltaDegrees: Float) {
    mutateSheetFloating(sheetIndex) { list ->
        val el = list.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return@mutateSheetFloating list
        val img = el.frame.image ?: return@mutateSheetFloating list
        list.toMutableList().also { it[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = img.copy(rotationDegrees = (img.rotationDegrees + deltaDegrees) % 360f))) }
    }
}

/** Replaces a text-document image's bytes with a new picture, resetting crop/rotation. (C4) */
fun OfficeViewModel.replaceTextImage(blockIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Image(block.image.copy(
        path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
        cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
    ))
    updateDocument(doc.copy(content = content, images = doc.images + (path to bytes)))
}

/** Replaces a slide image element's bytes with a new picture. (C4) */
fun OfficeViewModel.replaceSlideImage(slideIndex: Int, elementIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val el = slide.elements.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
    val frameImage = el.frame.image ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val elements = slide.elements.toMutableList()
    elements[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(
        path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
        cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
    )))
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides, images = doc.images + (path to bytes)))
}

/** Replaces a sheet floating image element's bytes with a new picture. (C4) */
fun OfficeViewModel.replaceSheetImage(sheetIndex: Int, elementIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val el = sheet.floating.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
    val frameImage = el.frame.image ?: return
    val floating = sheet.floating.toMutableList()
    floating[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(
        path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
        cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
    )))
    sheets[sheetIndex] = sheet.copy(floating = floating)
    updateDocument(doc.copy(sheets = sheets, images = doc.images + (path to bytes)))
}

/** Sets crop insets on an image frame element of a slide. (Phase 5) */
fun OfficeViewModel.setSlideImageCrop(slideIndex: Int, elementIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    elements[elementIndex] = cropElementImage(elements.getOrNull(elementIndex) ?: return, left, top, right, bottom)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Sets crop insets on an image frame element of a sheet's floating layer. (Phase 5) */
fun OfficeViewModel.setSheetImageCrop(sheetIndex: Int, elementIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
    mutateSheetFloating(sheetIndex) { list ->
        if (elementIndex !in list.indices) list
        else list.toMutableList().also { it[elementIndex] = cropElementImage(it[elementIndex], left, top, right, bottom) }
    }
}

internal fun OfficeViewModel.cropElementImage(el: OdfSlideElement, l: Float, t: Float, r: Float, b: Float): OdfSlideElement {
    val frameImage = (el as? OdfSlideElement.Frame)?.frame?.image
    return if (el is OdfSlideElement.Frame && frameImage != null)
        OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(cropLeftPct = l, cropTopPct = t, cropRightPct = r, cropBottomPct = b)))
    else el
}

/** Sets fill color on a slide shape/frame element. (extra) */
fun OfficeViewModel.setSlideElementFill(slideIndex: Int, elementIndex: Int, color: Long?) =
    setSlideElementColors(slideIndex, elementIndex, fill = color, setFill = true)

/** Sets stroke (border) color on a slide shape/frame element. (extra) */
fun OfficeViewModel.setSlideElementStroke(slideIndex: Int, elementIndex: Int, color: Long?) =
    setSlideElementColors(slideIndex, elementIndex, stroke = color, setStroke = true)

internal fun OfficeViewModel.setSlideElementColors(slideIndex: Int, elementIndex: Int, fill: Long? = null, stroke: Long? = null, setFill: Boolean = false, setStroke: Boolean = false) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    elements[elementIndex] = when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(
            fillColor = if (setFill) fill else el.frame.fillColor,
            strokeColor = if (setStroke) stroke else el.frame.strokeColor
        ))
        is OdfSlideElement.Shape -> {
            val s = el.shape
            val nf = if (setFill) fill else s.fillColor
            val ns = if (setStroke) stroke else s.strokeColor
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(fillColor = nf, strokeColor = ns)
                is OdfShape.Ellipse -> s.copy(fillColor = nf, strokeColor = ns)
                is OdfShape.Line -> s.copy(fillColor = nf, strokeColor = ns)
                is OdfShape.CustomShape -> s.copy(fillColor = nf, strokeColor = ns)
                is OdfShape.Polyline -> s.copy(fillColor = nf, strokeColor = ns)
            })
        }
    }
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}
