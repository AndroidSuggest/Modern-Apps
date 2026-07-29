package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.Text
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private data class DragState(val from: Int, val pos: Offset, val id: Int, val count: Int)

@Composable
fun InventoryOverlay(inventoryJson: String, recipesJson: String, onClose: () -> Unit, startTab: Int = 0) {
    val inv = remember(inventoryJson) {
        try { Json { ignoreUnknownKeys = true }.decodeFromString<InventoryState>(inventoryJson) } catch (_: Exception) { InventoryState() }
    }
    val slots = inv.slots
    var leftTab by remember { mutableStateOf(startTab) }   // 0 Inventory, 1 Outfit, 2 Crafting
    var catTab by remember { mutableStateOf(0) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val density = LocalDensity.current

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Row(
            Modifier.align(Alignment.Center).fillMaxWidth(0.94f).fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1E1A))
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps on the panel
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Left tabs (top→bottom: Crafting, Outfit, Inventory).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabButton("Crafting", leftTab == 2) { leftTab = 2 }
                TabButton("Outfit", leftTab == 1) { leftTab = 1 }
                TabButton("Inventory", leftTab == 0) { leftTab = 0 }
                Spacer(Modifier.weight(1f))
                TabButton("Close", false) { onClose() }
            }

            // Center content.
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                when (leftTab) {
                    0 -> InventoryGrid(slots, bounds, onDragStart = { i, off ->
                            val s = slots.getOrNull(i) ?: InvSlot(0, 0)
                            if (s.id != 0) drag = DragState(i, (bounds[i]?.topLeft ?: Offset.Zero) + off, s.id, s.count)
                        }, onDragMove = { d -> drag = drag?.let { it.copy(pos = it.pos + d) } },
                        onDragEnd = {
                            drag?.let { dr ->
                                val target = bounds.entries.firstOrNull { it.value.contains(dr.pos) }?.key
                                if (target != null && target != dr.from) try { VoxelsNative.moveItem(dr.from, target) } catch (_: Exception) {}
                            }
                            drag = null
                        }, onDragCancel = { drag = null })
                    1 -> Text("Outfit — coming soon", color = Color.White.copy(0.7f), modifier = Modifier.padding(24.dp))
                    else -> CraftingTable(recipesJson)
                }
            }

            // Right catalog (creative): tap a block to add a stack.
            Column(Modifier.width(236.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TabButton("Natural", catTab == 0) { catTab = 0 }
                    TabButton("Ores", catTab == 1) { catTab = 1 }
                    TabButton("Build", catTab == 2) { catTab = 2 }
                }
                Spacer(Modifier.height(8.dp))
                val cat = when (catTab) { 0 -> catalogNatural; 1 -> catalogOres; else -> catalogBuilding }
                CatalogGrid(cat) { id -> try { VoxelsNative.giveBlock(id) } catch (_: Exception) {} }
            }
        }

        // Floating dragged item.
        drag?.let { d ->
            val icon = rememberBlockIcon(d.id)
            val half = with(density) { 24.dp.toPx() }
            Box(Modifier.offset { IntOffset((d.pos.x - half).roundToInt(), (d.pos.y - half).roundToInt()) }.size(48.dp)) {
                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(96.dp).height(40.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF3A6B3A) else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = if (selected) 0.5f else 0.15f), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White.copy(0.95f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SlotBox(index: Int, slot: InvSlot, bounds: MutableMap<Int, Rect>, drag: Boolean,
                    onDragStart: (Int, Offset) -> Unit, onDragMove: (Offset) -> Unit,
                    onDragEnd: () -> Unit, onDragCancel: () -> Unit) {
    val icon = rememberBlockIcon(slot.id)
    Box(
        Modifier.size(46.dp).onGloballyPositioned { bounds[index] = it.boundsInWindow() }
            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .pointerInput(index) {
                detectDragGestures(
                    onDragStart = { off -> onDragStart(index, off) },
                    onDrag = { change, amount -> change.consume(); onDragMove(amount) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (slot.id != 0) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(30.dp), filterQuality = FilterQuality.None)
            else Text(blockNames[slot.id]?.take(2) ?: "", color = Color.White, fontSize = 10.sp)
            if (slot.count > 1) Box(Modifier.align(Alignment.BottomEnd)) { Text("${slot.count}", color = Color.White, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun InventoryGrid(slots: List<InvSlot>, bounds: MutableMap<Int, Rect>,
                          onDragStart: (Int, Offset) -> Unit, onDragMove: (Offset) -> Unit,
                          onDragEnd: () -> Unit, onDragCancel: () -> Unit) {
    fun cell(i: Int) = @Composable { SlotBox(i, slots.getOrNull(i) ?: InvSlot(0, 0), bounds, true, onDragStart, onDragMove, onDragEnd, onDragCancel) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Inventory — drag to rearrange", color = Color.White.copy(0.6f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 9) { cell(9 + row * 9 + col)() }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in 0 until 9) { cell(col)() } // hotbar
        }
    }
}

@Composable
private fun CatalogGrid(ids: List<Int>, onPick: (Int) -> Unit) {
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ids.chunked(4).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowIds.forEach { id ->
                    val icon = rememberBlockIcon(id)
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.4f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .clickable { onPick(id) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (icon != null) Image(bitmap = icon, contentDescription = blockNames[id], modifier = Modifier.size(32.dp), filterQuality = FilterQuality.None)
                        else Text(blockNames[id]?.take(2) ?: "", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(id: Int, count: Int) {
    val icon = if (id != 0) rememberBlockIcon(id) else null
    Box(
        Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp), filterQuality = FilterQuality.None)
        if (count > 1) Box(Modifier.align(Alignment.BottomEnd).padding(1.dp)) { Text("$count", color = Color.White, fontSize = 11.sp) }
    }
}

@Composable
private fun CraftingTable(recipesJson: String) {
    val recipes = remember(recipesJson) {
        try {
            val arr = org.json.JSONArray(recipesJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Recipe(o.getInt("in"), o.getInt("inN"), o.getInt("out"), o.getInt("outN"))
            }
        } catch (_: Exception) { emptyList() }
    }
    var sel by remember { mutableStateOf(0) }
    val r = recipes.getOrNull(sel)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Left: product picker.
        Column(Modifier.width(160.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Products", color = Color.White.copy(0.6f), fontSize = 12.sp)
            recipes.forEachIndexed { i, rec ->
                val icon = rememberBlockIcon(rec.outId)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (i == sel) Color(0xFF3A6B3A) else Color.White.copy(alpha = 0.08f))
                        .clickable { sel = i }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp), filterQuality = FilterQuality.None)
                    Text(blockNames[rec.outId] ?: "", color = Color.White.copy(0.95f), fontSize = 12.sp)
                }
            }
        }
        // Right: the crafting table (2x2 grid) with the selected recipe placed + its product.
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Crafting Table", color = Color.White.copy(0.6f), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 2) {
                                val idx = row * 2 + col
                                if (r == null) { GridCell(0, 0) }
                                else if (r.inN > 4) { GridCell(if (idx == 0) r.inId else 0, if (idx == 0) r.inN else 0) }
                                else { GridCell(if (idx < r.inN) r.inId else 0, 0) }
                            }
                        }
                    }
                }
                Text("→", color = Color.White.copy(0.8f), fontSize = 24.sp)
                GridCell(r?.outId ?: 0, r?.outN ?: 0)
            }
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A6B3A))
                    .clickable { if (r != null) try { VoxelsNative.craft(sel) } catch (_: Exception) {} }
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) { Text("Craft", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

private data class Recipe(val inId: Int, val inN: Int, val outId: Int, val outN: Int)
