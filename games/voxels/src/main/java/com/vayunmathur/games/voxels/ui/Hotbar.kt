package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
data class InventoryState(val selected: Int = 0, val slots: List<InvSlot> = List(9) { InvSlot(0, 0) }, val armor: List<InvSlot> = emptyList())

// Ids 163..178 are tools/armor whose slot `count` is durability (not a stack size).
fun isDurabilityItem(id: Int) = id in 163..178 || id == 186 || id == 188

val maxDurability = mapOf(
    163 to 60, 164 to 60, 165 to 132, 166 to 132, 167 to 250, 168 to 250, 169 to 1562, 170 to 1562,
    171 to 240, 172 to 240, 173 to 240, 174 to 240, 175 to 528, 176 to 528, 177 to 528, 178 to 528,
    186 to 64, 188 to 432
)

@Composable
fun DurabilityBar(id: Int, count: Int, modifier: Modifier = Modifier) {
    val max = maxDurability[id] ?: return
    if (count <= 0 || count >= max) return
    val frac = (count.toFloat() / max).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth(0.78f).height(3.dp).background(Color.Black.copy(0.65f), RoundedCornerShape(1.dp))) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color(1f - frac, frac, 0.15f), RoundedCornerShape(1.dp)))
    }
}

val blockNames = mapOf(
    0 to "·", 1 to "Stone", 2 to "Dirt", 3 to "Grass", 4 to "Wood", 5 to "Leaves",
    6 to "Sand", 7 to "Glass", 8 to "Cobble", 9 to "Brick", 10 to "Planks", 11 to "Snow", 12 to "Water", 13 to "Bedrock",
    14 to "Gravel", 15 to "Mossy", 16 to "Diorite", 17 to "Pol. Diorite", 18 to "Coal Ore", 19 to "Iron Ore",
    20 to "Diamond Ore", 21 to "Redstone", 22 to "Emerald Ore", 23 to "Iron", 24 to "Diamond", 25 to "Emerald",
    26 to "Birch Log", 27 to "Birch Plank", 28 to "Birch Leaf", 29 to "Spruce Log", 30 to "Spruce Plank", 31 to "Spruce Leaf",
    32 to "Netherrack", 33 to "Bookshelf", 34 to "Crafting Table", 35 to "Furnace",
    36 to "Red Sand", 37 to "Red Sandstone", 38 to "Sandstone", 39 to "Podzol", 40 to "Coarse Dirt", 41 to "Mycelium",
    42 to "Packed Ice", 43 to "Ice", 44 to "Blue Ice", 45 to "Mud", 46 to "Rooted Dirt",
    47 to "Dark Oak Log", 48 to "Dark Oak Leaf", 49 to "Dark Oak Plank", 50 to "Acacia Log", 51 to "Jungle Log", 52 to "Jungle Plank",
    53 to "Granite Bricks", 54 to "Deepslate Bricks", 55 to "Nether Bricks", 56 to "End Stone Bricks", 57 to "Cobbled Deepslate",
    58 to "Hay Bale", 59 to "Farmland", 60 to "Packed Dirt",
    61 to "Tube Coral", 62 to "Brain Coral", 63 to "Bubble Coral", 64 to "Fire Coral", 65 to "Horn Coral",
    66 to "Kelp", 67 to "Sea Lantern", 68 to "Prismarine", 69 to "Dark Prismarine",
    70 to "Dripstone", 71 to "Moss Block", 72 to "Sculk", 73 to "Amethyst", 74 to "Calcite", 75 to "Tuff",
    76 to "Magma", 77 to "Glowstone", 78 to "Obsidian", 79 to "Clay", 80 to "Azalea Leaves", 81 to "Warding Stone",
    // Items (128+): consumables + materials.
    128 to "Estus Flask", 129 to "Heart Container", 130 to "Apple", 131 to "Bread", 132 to "Cooked Fish",
    133 to "Golden Apple", 134 to "Brownie", 135 to "Carrot", 136 to "Melon Slice", 137 to "Leather", 138 to "Gunpowder",
    146 to "Baked Potato", 147 to "Cookie", 148 to "Cooked Salmon", 149 to "Fried Egg", 150 to "Cooked Rabbit",
    151 to "Apple Empanada", 152 to "Glow Berry Crumble", 153 to "Choc-Chip Cookie",
    154 to "Iron Ingot", 155 to "Diamond", 156 to "Emerald", 157 to "Coal", 158 to "Redstone", 159 to "Stick",
    160 to "Blessing of Swiftness", 161 to "Blessing of the Warrior", 162 to "Blessing of the Deep",
    163 to "Wood Pickaxe", 164 to "Wood Sword", 165 to "Stone Pickaxe", 166 to "Stone Sword",
    167 to "Iron Pickaxe", 168 to "Iron Sword", 169 to "Diamond Pickaxe", 170 to "Diamond Sword",
    171 to "Iron Helmet", 172 to "Iron Chestplate", 173 to "Iron Leggings", 174 to "Iron Boots",
    175 to "Diamond Helmet", 176 to "Diamond Chestplate", 177 to "Diamond Leggings", 178 to "Diamond Boots",
    82 to "Jukebox", 83 to "Chest", 84 to "Lava", 85 to "End Stone", 88 to "Beacon", 89 to "Purpur",
    179 to "Disc: Golden", 180 to "Disc: Lullaby", 181 to "Disc: Forest", 182 to "Disc: Deep Mining",
    183 to "Disc: Winter", 184 to "Disc: Piano", 185 to "Disc: Gift",
    186 to "Flint & Steel", 187 to "Nether Star", 188 to "Elytra", 189 to "Firework Rocket",
    190 to "Snowball", 191 to "Ender Pearl"
)

// Music disc item id -> track asset in assets/music/.
val discTrack = mapOf(
    179 to "golden.ogg", 180 to "lullaby.ogg", 181 to "mcl_forest.ogg", 182 to "mcl_mining.ogg",
    183 to "mcl_winter.ogg", 184 to "mcl_piano.ogg", 185 to "mcl_gift.ogg"
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
    32 to "netherrack.png", 33 to "bookshelf.png", 34 to "crafting_table_top.png", 35 to "furnace_top.png",
    36 to "red_sand.png", 37 to "red_sandstone.png", 38 to "sandstone.png", 39 to "podzol_top.png", 40 to "coarse_dirt.png",
    41 to "mycelium_side.png", 42 to "packed_ice.png", 43 to "ice.png", 44 to "blue_ice.png", 45 to "mud.png", 46 to "rooted_dirt.png",
    47 to "dark_oak_log_top.png", 48 to "dark_oak_leaves.png", 49 to "dark_oak_planks.png",
    50 to "acacia_log_top.png", 51 to "jungle_log_top.png", 52 to "jungle_planks.png",
    53 to "granite_bricks.png", 54 to "deepslate_bricks.png", 55 to "nether_bricks.png", 56 to "end_stone_bricks.png",
    57 to "cobbled_deepslate.png", 58 to "hay_block_top.png", 59 to "farmland.png", 60 to "packed_dirt.png",
    61 to "tube_coral.png", 62 to "brain_coral.png", 63 to "bubble_coral.png", 64 to "fire_coral.png", 65 to "horn_coral.png",
    66 to "kelp.png", 67 to "sea_lantern.png", 68 to "prismarine.png", 69 to "dark_prismarine.png",
    70 to "dripstone.png", 71 to "moss_block.png", 72 to "sculk.png", 73 to "amethyst.png", 74 to "calcite.png",
    75 to "tuff.png", 76 to "magma.png", 77 to "glowstone.png", 78 to "obsidian.png", 79 to "clay.png", 80 to "azalea_leaves.png", 81 to "warding_stone.png",
    128 to "honey_bottle.png", 129 to "heart_container.png", 130 to "apple.png", 131 to "bread.png", 132 to "cooked_cod.png",
    133 to "golden_apple.png", 134 to "brownie.png", 135 to "carrot.png", 136 to "glistering_melon_slice.png", 137 to "leather.png", 138 to "gunpowder.png",
    146 to "baked_potato.png", 147 to "cookie.png", 148 to "cooked_salmon.png", 149 to "fried_egg.png", 150 to "cooked_rabbit.png",
    151 to "apple_empanada.png", 152 to "glow_berry_crumble.png", 153 to "chocolate_chip_cookie.png",
    154 to "iron_ingot.png", 155 to "diamond.png", 156 to "emerald.png", 157 to "coal.png", 158 to "redstone.png", 159 to "stick.png",
    160 to "blessing_swift.png", 161 to "blessing_warrior.png", 162 to "blessing_deep.png",
    163 to "wood_pickaxe.png", 164 to "wood_sword.png", 165 to "stone_pickaxe.png", 166 to "stone_sword.png",
    167 to "iron_pickaxe.png", 168 to "iron_sword.png", 169 to "diamond_pickaxe.png", 170 to "diamond_sword.png",
    171 to "iron_helmet.png", 172 to "iron_chestplate.png", 173 to "iron_leggings.png", 174 to "iron_boots.png",
    175 to "diamond_helmet.png", 176 to "diamond_chestplate.png", 177 to "diamond_leggings.png", 178 to "diamond_boots.png",
    82 to "jukebox.png", 83 to "chest.png", 84 to "lava.png", 85 to "end_stone.png", 88 to "beacon.png", 89 to "purpur_block.png",
    179 to "music_disc_13.png", 180 to "music_disc_cat.png", 181 to "music_disc_blocks.png", 182 to "music_disc_chirp.png",
    183 to "music_disc_5.png", 184 to "music_disc_11.png", 185 to "music_disc_bounce.png",
    186 to "flint_and_steel.png", 187 to "nether_star.png", 188 to "elytra.png", 189 to "firework_rocket.png",
    190 to "snowball.png", 191 to "ender_pearl.png"
)

// Creative catalog, split into tabs.
val catalogNatural = listOf(3, 2, 40, 46, 60, 39, 41, 71, 1, 6, 38, 36, 37, 14, 16, 17, 15, 45, 79, 75, 70,
    4, 5, 26, 28, 29, 31, 47, 48, 50, 51, 80, 11, 42, 43, 44, 32, 84, 85, 13)
val catalogOres = listOf(18, 19, 20, 21, 22, 23, 24, 25, 73)
val catalogOcean = listOf(61, 62, 63, 64, 65, 66, 67, 68, 69)
val catalogItems = listOf(128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138,
    146, 147, 148, 149, 150, 151, 152, 153,
    154, 155, 156, 157, 158, 159, 160, 161, 162, 187, 189, 190, 191)
val catalogGear = listOf(163, 164, 165, 166, 167, 168, 169, 170,
    171, 172, 173, 174, 175, 176, 177, 178, 186, 188)
val catalogMusic = listOf(82, 179, 180, 181, 182, 183, 184, 185)
val catalogBuilding = listOf(10, 27, 30, 49, 52, 8, 57, 9, 53, 54, 55, 56, 58, 59, 72, 74, 76, 77, 78, 81, 7, 33, 34, 35, 88, 89)

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
                    if (slot.count > 1 && !isDurabilityItem(slot.id)) {
                        Box(Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 1.dp)) {
                            Text(text = "${slot.count}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                    if (isDurabilityItem(slot.id)) DurabilityBar(slot.id, slot.count, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
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

@Serializable
data class EffJson(val k: String = "", val amp: Int = 0, val t: Int = 0)

@Serializable
data class HealthJson(val hp: Float = 20f, val max: Float = 20f, val absorb: Float = 0f, val dead: Boolean = false, val estus: Int = 0, val effects: List<EffJson> = emptyList(), val boss: Float = -1f, val bossName: String = "", val elytra: Boolean = false, val gliding: Boolean = false)

// Small chip shown while an elytra is equipped: prompts to deploy, or confirms gliding.
@Composable
fun GlideIndicator(healthJson: String, modifier: Modifier = Modifier) {
    val hj = remember(healthJson) {
        try { Json { ignoreUnknownKeys = true }.decodeFromString<HealthJson>(healthJson) } catch (_: Exception) { HealthJson() }
    }
    if (!hj.elytra) return
    val (label, tint) = if (hj.gliding) "Gliding" to Color(0xFF7CE0FF) else "Tap ▲ mid-air to glide" to Color.White.copy(0.75f)
    Box(modifier.background(Color.Black.copy(0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun BossBar(healthJson: String, modifier: Modifier = Modifier) {
    val hj = remember(healthJson) {
        try { Json { ignoreUnknownKeys = true }.decodeFromString<HealthJson>(healthJson) } catch (_: Exception) { HealthJson() }
    }
    if (hj.boss < 0f) return
    val wither = hj.bossName == "The Wither"
    val textColor = if (wither) Color(0xFFBDBDBD) else Color(0xFFB388FF)
    val barColor = if (wither) Color(0xFF4A4A55) else Color(0xFF9B30FF)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(hj.bossName.ifEmpty { "Boss" }, color = textColor, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.width(220.dp).height(7.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(hj.boss.coerceIn(0f, 1f)).fillMaxHeight().background(barColor, RoundedCornerShape(3.dp)))
        }
    }
}

private val effectNames = mapOf(
    "regen" to "Regen", "poison" to "Poison", "resist" to "Resist", "strength" to "Strength",
    "speed" to "Speed", "haste" to "Haste", "absorb" to "Absorption", "fireres" to "Fire Res",
    "night" to "Night Vis", "jump" to "Leaping", "slow" to "Slowness"
)
private val effectColors = mapOf(
    "regen" to Color(0xFFE573B5), "poison" to Color(0xFF6DA33A), "resist" to Color(0xFF9E9E9E),
    "strength" to Color(0xFF932423), "speed" to Color(0xFF7CAFC6), "haste" to Color(0xFFD9C043),
    "absorb" to Color(0xFFF2C15A), "fireres" to Color(0xFFE0913A),
    "night" to Color(0xFF3B3BA0), "jump" to Color(0xFF34A02C), "slow" to Color(0xFF5A6472)
)

private fun roman(n: Int) = when (n) { 0 -> ""; 1 -> " II"; 2 -> " III"; 3 -> " IV"; else -> " ${n + 1}" }

@Composable
fun HealthOverlay(healthJson: String, modifier: Modifier = Modifier) {
    val h = remember(healthJson) {
        try { Json { ignoreUnknownKeys = true }.decodeFromString<HealthJson>(healthJson) } catch (_: Exception) { HealthJson() }
    }
    Column(modifier, horizontalAlignment = Alignment.Start) {
        // Effects (above the hearts).
        if (h.effects.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                h.effects.forEach { e ->
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background((effectColors[e.k] ?: Color.Gray).copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text("${effectNames[e.k] ?: e.k}${roman(e.amp)} ${e.t}s", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
        // Hearts (each = 2 HP); absorption shown as gold hearts appended.
        val totalHearts = (h.max / 2f).toInt().coerceIn(1, 30)
        Row {
            for (i in 0 until totalHearts) {
                val filled = h.hp / 2f - i
                val c = when { filled >= 1f -> Color(0xFFE23A45); filled >= 0.5f -> Color(0xFFEE8891); else -> Color(0xFF3A1516) }
                Text("♥", color = c, style = MaterialTheme.typography.bodyMedium)
            }
            val absHearts = (h.absorb / 2f).toInt().coerceIn(0, 10)
            for (i in 0 until absHearts) { Text("♥", color = Color(0xFFF2C15A), style = MaterialTheme.typography.bodyMedium) }
        }
        if (h.estus > 0) {
            Text("Estus ×${h.estus}", color = Color(0xFFFFC957), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DebugOverlay(debugJson: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(6.dp)) {
        Text(text = debugJson, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB2FF59))
    }
}
