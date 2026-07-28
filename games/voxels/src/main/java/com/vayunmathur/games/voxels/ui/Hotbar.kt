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

private val blockNames = mapOf(
    0 to "·", 1 to "Stone", 2 to "Dirt", 3 to "Grass", 4 to "Wood", 5 to "Leaves",
    6 to "Sand", 7 to "Glass", 8 to "Cobble", 9 to "Brick", 10 to "Planks", 11 to "Snow", 12 to "Water", 13 to "Bedrock"
)

private val blockIconFile = mapOf(
    1 to "deepslate.png",
    2 to "dirt.png",
    3 to "grass_block_top.png",
    4 to "oak_log_top.png",
    5 to "oak_leaves.png",
    6 to "sand.png",
    7 to "ice.png",
    8 to "cobblestone.png",
    9 to "bricks.png",
    10 to "oak_planks.png",
    11 to "packed_ice.png",
    12 to "blue_ice.png",
    13 to "bedrock.png"
)

@Composable
fun Hotbar(
    inventoryJson: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
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
        state.slots.forEachIndexed { idx, slot ->
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
    }
}

@Composable
fun DebugOverlay(debugJson: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(6.dp)) {
        Text(text = debugJson, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB2FF59))
    }
}
