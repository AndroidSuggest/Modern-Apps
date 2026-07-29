package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InvSlot(val id: Int, val count: Int)

@Serializable
data class InventoryState(val selected: Int = 0, val slots: List<InvSlot> = List(9) { InvSlot(0, 0) })

val blockNames = mapOf(
    0 to "·", 1 to "Stone", 2 to "Dirt", 3 to "Grass", 4 to "Wood", 5 to "Leaves",
    6 to "Sand", 7 to "Glass", 8 to "Cobble", 9 to "Brick", 10 to "Planks", 11 to "Snow", 12 to "Water", 13 to "Bedrock",
    14 to "Gravel", 15 to "Mossy", 16 to "Diorite", 17 to "Pol. Diorite", 18 to "Coal Ore", 19 to "Iron Ore",
    20 to "Diamond Ore", 21 to "Redstone", 22 to "Emerald Ore", 23 to "Iron", 24 to "Diamond", 25 to "Emerald",
    26 to "Birch Log", 27 to "Birch Plank", 28 to "Birch Leaf", 29 to "Spruce Log", 30 to "Spruce Plank", 31 to "Spruce Leaf",
    32 to "Netherrack", 33 to "Bookshelf", 34 to "Crafting Table", 35 to "Furnace"
)

val blockIconFile = mapOf(
    1 to "deepslate.png", 2 to "dirt.png", 3 to "grass_block_top.png", 4 to "oak_log_top.png", 5 to "oak_leaves.png",
    6 to "sand.png", 7 to "ice.png", 8 to "cobblestone.png", 9 to "bricks.png", 10 to "oak_planks.png",
    11 to "packed_ice.png", 12 to "blue_ice.png", 13 to "bedrock.png",
    14 to "gravel.png", 15 to "mossy_cobblestone.png", 16 to "diorite.png", 17 to "polished_diorite.png",
    18 to "coal_ore.png", 19 to "iron_ore.png", 20 to "diamond_ore.png", 21 to "redstone_ore.png", 22 to "emerald_ore.png",
    23 to "iron_block.png", 24 to "diamond_block.png", 25 to "emerald_block.png",
    26 to "birch_log_top.png", 27 to "birch_planks.png", 28 to "birch_leaves.png",
    29 to "spruce_log_top.png", 30 to "spruce_planks.png", 31 to "spruce_leaves.png",
    32 to "netherrack.png", 33 to "bookshelf.png", 34 to "crafting_table_top.png", 35 to "furnace_top.png"
)

// Creative catalog, split into tabs.
val catalogNatural = listOf(3, 2, 1, 6, 14, 16, 17, 15, 4, 5, 26, 28, 29, 31, 11, 32, 13)
val catalogOres = listOf(18, 19, 20, 21, 22, 23, 24, 25)
val catalogBuilding = listOf(10, 27, 30, 8, 9, 7, 33, 34, 35)

@Composable
fun rememberBlockIcon(id: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val ctx = LocalContext.current
    return remember(id) {
        val fileName = blockIconFile[id] ?: return@remember null
        try {
            android.graphics.BitmapFactory.decodeStream(ctx.assets.open("block/$fileName"))?.asImageBitmap()
        } catch (_: Exception) { null }
    }
}

@Composable
fun Hotbar(
    inventoryJson: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpenInventory: () -> Unit = {}
) {
    val state = try {
        Json { ignoreUnknownKeys = true }.decodeFromString<InventoryState>(inventoryJson)
    } catch (_: Exception) {
        InventoryState()
    }
    val ctx = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
    ) {
        state.slots.take(9).forEachIndexed { idx, slot ->
            val isSel = idx == state.selected
            val iconBitmap = remember(slot.id) {
                val fileName = blockIconFile[slot.id]
                if (fileName != null) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeStream(ctx.assets.open("block/$fileName"))
                        bmp?.asImageBitmap()
                    } catch (_: Exception) { null }
                } else null
            }
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f) else Color.Black.copy(alpha = 0.55f))
                    .border(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    .clickable { onSelect(idx) },
                contentAlignment = Alignment.Center
            ) {
                if (slot.id != 0) {
                    if (iconBitmap != null) {
                        Image(bitmap = iconBitmap, contentDescription = blockNames[slot.id], modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)), filterQuality = FilterQuality.None)
                    } else {
                        Box(Modifier.size(28.dp).background(Color(0xFF7A7A7A), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                            Text(text = blockNames[slot.id]?.take(2) ?: "${slot.id}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                    if (slot.count > 1) {
                        Box(Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 1.dp)) {
                            Text(text = "${slot.count}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
            }
        }
        // Single inventory "…" tile at the end of the hotbar (opens the full inventory).
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .clickable { onOpenInventory() },
            contentAlignment = Alignment.Center
        ) { Text("…", color = Color.White, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
fun DebugOverlay(debugJson: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(6.dp)) {
        Text(text = debugJson, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB2FF59))
    }
}
