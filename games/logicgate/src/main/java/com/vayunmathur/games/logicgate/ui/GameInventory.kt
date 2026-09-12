package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.data.ChipCategory
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.library.ui.Text

internal suspend fun AwaitPointerEventScope.awaitFirstDownGlobal(): PointerInputChange {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.type == PointerEventType.Press) {
            val d = ev.changes.firstOrNull { !it.isConsumed } ?: continue
            return d
        }
    }
}

@Composable
internal fun MobileFilterRow(selected: ChipGroup?, onSelect: (ChipGroup?) -> Unit, availableGroups: Set<ChipGroup>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(modifier = modifier.height(48.dp).horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val items: List<Pair<ChipGroup?, String>> = listOf(null to "ALL", ChipGroup.BIT to "BIT", ChipGroup.WORD to "WORD", ChipGroup.CUSTOM to "CUSTOM")
            .filter { it.first == null || it.first in availableGroups } // hide filters with no unlocked chips
        items.forEach { pair ->
            val g = pair.first
            val label = pair.second
            val isSel = selected == g
            Box(modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (isSel) Turing.rightTabOn else Color.Transparent).border(1.dp, if (isSel) Color(0xFF5A667A) else Color.White.copy(alpha = 0.32f), RoundedCornerShape(20.dp)).clickable { onSelect(g) }.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) Color.White else Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
internal fun MobileInventoryBar(
    allowed: List<String>, unlockedChips: Set<String>, selectedGroup: ChipGroup?,
    onChipDragStart: (chipId: String, global: Offset) -> Unit,
    onChipDrag: (chipId: String, global: Offset) -> Unit,
    onChipDrop: (chipId: String, global: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowScroll = rememberScrollState()
    Row(modifier = modifier.height(60.dp).background(Color(0xFF1A2332)).horizontalScroll(rowScroll).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val filtered = allowed.filter { it in unlockedChips }.filter { chipId ->
            if (selectedGroup == null) true else {
                val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                def?.let { groupForCategory(it.category) == selectedGroup } ?: true
            }
        }.sortedBy { try { ChipLibrary.get(it).nandCost } catch (_: Exception) { 999 } }
        filtered.forEach { chipId ->
            MobileDraggableChipItem(chipId = chipId, chipOnDragStart = { id: String, g: Offset -> onChipDragStart(id, g) }, chipOnDrag = { id: String, g: Offset -> onChipDrag(id, g) }, chipOnDrop = { id: String, g: Offset -> onChipDrop(id, g) })
        }
        if (filtered.isEmpty()) {
            Text(stringResource(R.string.no_chips_all), fontSize = 13.sp, color = Color(0xFF6B7D96), modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
internal fun MobileDraggableChipItem(chipId: String, chipOnDragStart: (String, Offset) -> Unit, chipOnDrag: (String, Offset) -> Unit, chipOnDrop: (String, Offset) -> Unit) {
    val def = ChipLibrary.get(chipId)
    val baseCol = when (def.category) {
        ChipCategory.PRIMITIVE -> Color(0xFF153A45)
        ChipCategory.FOUNDATION -> Color(0xFF144A38)
        ChipCategory.ROUTING -> Color(0xFF4A3514)
        ChipCategory.BUS -> Color(0xFF2B284A)
        ChipCategory.ARITH -> Color(0xFF5A2A14)
        ChipCategory.MEMORY -> Color(0xFF3A1E52)
        ChipCategory.CPU -> Color(0xFF5E1840)
    }
    val busW = def.dominantBusWidth()
    val busColor = when (busW) { 4 -> Color(0xFFFFA126); 8 -> Color(0xFF4FC3FF); else -> Color(0xFF2BE4B8) }
    var chipPosInWindow by remember { mutableStateOf(Offset.Zero) }
    val chipPosState by rememberUpdatedState(chipPosInWindow)
    var isDragging by remember { mutableStateOf(false) }
    // Solid color only, one text – block name – no circles inside
    Box(modifier = Modifier.onGloballyPositioned { c -> chipPosInWindow = c.positionInWindow() }.widthIn(min = 52.dp, max = 132.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(baseCol).border(if (isDragging) 1.4.dp else 0.8.dp, if (isDragging) Color.White else busColor.copy(alpha = 0.65f), RoundedCornerShape(10.dp)).pointerInput(chipId) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDownGlobal()
                val startWindow = chipPosState + down.position
                var dragTotal = Offset.Zero
                var decided = false      // direction resolved yet?
                var pullOut = false      // vertical drag => lift the chip out to place it
                var curWindow = startWindow
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.changedToUpIgnoreConsumed()) {
                        if (pullOut) chipOnDrop(chipId, curWindow)
                        isDragging = false; break
                    }
                    val delta = ch.position - ch.previousPosition
                    dragTotal += delta
                    curWindow += delta
                    if (!decided && dragTotal.getDistance() > slop) {
                        decided = true
                        // 45° split: more vertical -> pull out; more horizontal -> leave it to the row's scroll.
                        if (kotlin.math.abs(dragTotal.y) > kotlin.math.abs(dragTotal.x)) {
                            pullOut = true; isDragging = true; chipOnDragStart(chipId, curWindow)
                        } else break // don't consume: horizontalScroll parent takes over
                    }
                    if (pullOut) { ch.consume(); chipOnDrag(chipId, curWindow) }
                }
            }
        }
    }, contentAlignment = Alignment.Center) {
        Text(
            text = def.displayName,
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
