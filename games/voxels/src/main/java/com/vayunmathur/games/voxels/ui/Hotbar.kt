package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.voxels.R
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

internal val voxelsJson = Json { ignoreUnknownKeys = true }

@Serializable
data class InvSlot(val id: Int, val count: Int)

@Serializable
data class InventoryState(val selected: Int = 0, val slots: List<InvSlot> = List(9) { InvSlot(0, 0) }, val armor: List<InvSlot> = emptyList())

// Ids 163..178 and 197..202 are tools/armor whose slot `count` is durability (not a stack size).
fun isDurabilityItem(id: Int) = id in 1059..1074 || id in 1093..1098 || id in 1135..1140 || id == 1082 || id == 1084 || id in 1148..1149 || id == 1151

val maxDurability = mapOf(
    1059 to 60, 1060 to 60, 1061 to 132, 1062 to 132, 1063 to 250, 1064 to 250, 1065 to 1562, 1066 to 1562,
    1067 to 240, 1068 to 240, 1069 to 240, 1070 to 240, 1071 to 528, 1072 to 528, 1073 to 528, 1074 to 528,
    1082 to 64, 1084 to 432, 1148 to 238, 1149 to 64, 1151 to 64,
    1135 to 700, 1136 to 700, 1137 to 380, 1138 to 380, 1139 to 380, 1140 to 380,
    1093 to 2031, 1094 to 2031, 1095 to 666, 1096 to 666, 1097 to 666, 1098 to 666
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
    1024 to "Estus Flask", 1025 to "Heart Container", 1026 to "Apple", 1027 to "Bread", 1028 to "Cooked Fish",
    1029 to "Golden Apple", 1030 to "Brownie", 1031 to "Carrot", 1032 to "Melon Slice", 1033 to "Leather", 1034 to "Gunpowder",
    1042 to "Baked Potato", 1043 to "Cookie", 1044 to "Cooked Salmon", 1045 to "Fried Egg", 1046 to "Cooked Rabbit",
    1047 to "Apple Empanada", 1048 to "Glow Berry Crumble", 1049 to "Choc-Chip Cookie",
    1050 to "Iron Ingot", 1051 to "Diamond", 1052 to "Emerald", 1053 to "Coal", 1054 to "Redstone", 1055 to "Stick",
    // The Blessing pantheon (attunements, see blessing.rs).
    1056 to "Blessing of Clement", 1057 to "Blessing of Ares", 1058 to "Blessing of Yamm",
    1099 to "Blessing of Daedalus", 1100 to "Blessing of Icarus", 1101 to "Blessing of Yama",
    1102 to "Blessing of Talos", 1103 to "Blessing of the God King", 1104 to "Blessing of Arachnae",
    1105 to "Blessing of Prometheus", 1106 to "Blessing of Lu Ban", 1107 to "Blessing of Eros",
    1108 to "Blessing of Will", 1109 to "Blessing of Hyacinthus", 1110 to "Blessing of Aeolus",
    1111 to "Blessing of Cronus", 1112 to "Blessing of Demeter", 1113 to "Blessing of Glaucus",
    1114 to "Blessing of Apollo", 1115 to "Blessing of Artemis", 1116 to "Blessing of Warding",
    1117 to "Blessing of Paris",
    1141 to "Blessing of Athena", 1142 to "Blessing of Sekhmet", 1143 to "Blessing of Camazotz",
    1144 to "Blessing of Tangaroa", 1145 to "Blessing of Anubis",
    // Matcha's kitchen.
    1118 to "Raw Meat", 1119 to "Cooked Meat", 1120 to "Ramen", 1121 to "Japanese Curry",
    1122 to "Green Curry", 1123 to "Gnocchi", 1124 to "Naan", 1125 to "Pupusa", 1126 to "Latke",
    1127 to "Bruschetta", 1128 to "French Toast", 1129 to "Sweet Berry Danish", 1130 to "Melon Sorbet",
    1131 to "Stroganoff",
    // Matcha's bronze tier.
    97 to "Copper Ore", 98 to "Gold Ore", 99 to "Copper Block", 100 to "Gold Block", 101 to "Bronze Block",
    1132 to "Copper Ingot", 1133 to "Gold Ingot", 1134 to "Bronze Ingot",
    1135 to "Bronze Pickaxe", 1136 to "Bronze Sword",
    1137 to "Bronze Helmet", 1138 to "Bronze Chestplate", 1139 to "Bronze Leggings", 1140 to "Bronze Boots",
    1059 to "Wood Pickaxe", 1060 to "Wood Sword", 1061 to "Stone Pickaxe", 1062 to "Stone Sword",
    1063 to "Iron Pickaxe", 1064 to "Iron Sword", 1065 to "Diamond Pickaxe", 1066 to "Diamond Sword",
    1067 to "Iron Helmet", 1068 to "Iron Chestplate", 1069 to "Iron Leggings", 1070 to "Iron Boots",
    1071 to "Diamond Helmet", 1072 to "Diamond Chestplate", 1073 to "Diamond Leggings", 1074 to "Diamond Boots",
    82 to "Jukebox", 83 to "Chest", 84 to "Lava", 85 to "End Stone", 88 to "Beacon", 89 to "Purpur",
    1075 to "Disc: Golden", 1076 to "Disc: Lullaby", 1077 to "Disc: Forest", 1078 to "Disc: Deep Mining",
    1079 to "Disc: Winter", 1080 to "Disc: Piano", 1081 to "Disc: Gift",
    1082 to "Flint & Steel", 1083 to "Nether Star", 1084 to "Elytra", 1085 to "Firework Rocket",
    1086 to "Snowball", 1087 to "Ender Pearl",
    // Matcha alloy tier.
    90 to "Silver Ore", 91 to "Sulfur Ore", 92 to "Cinnabar Ore",
    93 to "Silver Block", 94 to "Steel Block", 95 to "Adamant Block", 96 to "Blast Furnace",
    1088 to "Sulfur", 1089 to "Silver Ingot", 1090 to "Quicksilver", 1091 to "Steel Ingot", 1092 to "Adamant Ingot",
    1093 to "Adamant Pickaxe", 1094 to "Adamant Sword",
    1095 to "Adamant Helmet", 1096 to "Adamant Chestplate", 1097 to "Adamant Leggings", 1098 to "Adamant Boots",
    // Matcha's building set: slabs and stairs, cut on a stonecutter.
    119 to "Wool", 120 to "Suspicious Sand", 1148 to "Shears", 1149 to "Fishing Rod", 1150 to "Raw Fish", 1151 to "Brush",
    121 to "Wheat Crop", 122 to "Carrot Crop", 123 to "Melon Crop", 1146 to "Wheat Seeds", 1147 to "Wheat",
    102 to "Stone Slab", 103 to "Stone Stairs", 104 to "Cobble Slab", 105 to "Cobble Stairs",
    106 to "Plank Slab", 107 to "Plank Stairs", 108 to "Brick Slab", 109 to "Brick Stairs",
    110 to "Sandstone Slab", 111 to "Sandstone Stairs",
    112 to "Deepslate Brick Slab", 113 to "Deepslate Brick Stairs",
    114 to "Nether Brick Slab", 115 to "Nether Brick Stairs",
    116 to "Purpur Slab", 117 to "Purpur Stairs", 118 to "Stonecutter"
)

// Music disc item id -> track asset in assets/music/.
val discTrack = mapOf(
    1075 to "golden.ogg", 1076 to "lullaby.ogg", 1077 to "mcl_forest.ogg", 1078 to "mcl_mining.ogg",
    1079 to "mcl_winter.ogg", 1080 to "mcl_piano.ogg", 1081 to "mcl_gift.ogg"
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
    1024 to "honey_bottle.png", 1025 to "heart_container.png", 1026 to "apple.png", 1027 to "bread.png", 1028 to "cooked_cod.png",
    1029 to "golden_apple.png", 1030 to "brownie.png", 1031 to "carrot.png", 1032 to "glistering_melon_slice.png", 1033 to "leather.png", 1034 to "gunpowder.png",
    1042 to "baked_potato.png", 1043 to "cookie.png", 1044 to "cooked_salmon.png", 1045 to "fried_egg.png", 1046 to "cooked_rabbit.png",
    1047 to "apple_empanada.png", 1048 to "glow_berry_crumble.png", 1049 to "chocolate_chip_cookie.png",
    1050 to "iron_ingot.png", 1051 to "diamond.png", 1052 to "emerald.png", 1053 to "coal.png", 1054 to "redstone.png", 1055 to "stick.png",
    1056 to "blessing_clement.png", 1057 to "blessing_ares.png", 1058 to "blessing_yamm.png",
    1099 to "blessing_daedalus.png", 1100 to "blessing_icarus.png", 1101 to "blessing_yama.png",
    1102 to "blessing_talos.png", 1103 to "blessing_god_king.png", 1104 to "blessing_arachnae.png",
    1105 to "blessing_prometheus.png", 1106 to "blessing_lu_ban.png", 1107 to "blessing_eros.png",
    1108 to "blessing_will.png", 1109 to "blessing_hyacinthus.png", 1110 to "blessing_aeolus.png",
    1111 to "blessing_cronus.png", 1112 to "blessing_demeter.png", 1113 to "blessing_glaucus.png",
    1114 to "blessing_apollo.png", 1115 to "blessing_artemis.png", 1116 to "blessing_warding.png",
    119 to "wool.png", 120 to "suspicious_sand.png", 1148 to "shears.png", 1149 to "fishing_rod.png",
    1150 to "raw_fish.png", 1151 to "brush.png",
    121 to "wheat_crop.png", 122 to "carrot_crop.png", 123 to "melon_crop.png",
    1146 to "wheat_seeds.png", 1147 to "wheat.png",
    1117 to "blessing_paris.png",
    1141 to "blessing_athena.png", 1142 to "blessing_sekhmet.png", 1143 to "blessing_camazotz.png",
    1144 to "blessing_tangaroa.png", 1145 to "blessing_anubis.png",
    1118 to "raw_meat.png", 1119 to "cooked_meat.png", 1120 to "ramen.png", 1121 to "japanese_curry.png",
    1122 to "green_curry.png", 1123 to "gnocchi.png", 1124 to "naan.png", 1125 to "pupusa.png",
    1126 to "latke.png", 1127 to "bruschetta.png", 1128 to "french_toast.png",
    1129 to "sweet_berry_danish.png", 1130 to "melon_sorbet.png", 1131 to "stroganoff.png",
    97 to "copper_ore.png", 98 to "gold_ore.png", 99 to "copper_block.png",
    100 to "gold_block.png", 101 to "bronze_block.png",
    1132 to "copper_ingot.png", 1133 to "gold_ingot.png", 1134 to "bronze_ingot.png",
    1135 to "bronze_pickaxe.png", 1136 to "bronze_sword.png", 1137 to "bronze_helmet.png",
    1138 to "bronze_chestplate.png", 1139 to "bronze_leggings.png", 1140 to "bronze_boots.png",
    1059 to "wood_pickaxe.png", 1060 to "wood_sword.png", 1061 to "stone_pickaxe.png", 1062 to "stone_sword.png",
    1063 to "iron_pickaxe.png", 1064 to "iron_sword.png", 1065 to "diamond_pickaxe.png", 1066 to "diamond_sword.png",
    1067 to "iron_helmet.png", 1068 to "iron_chestplate.png", 1069 to "iron_leggings.png", 1070 to "iron_boots.png",
    1071 to "diamond_helmet.png", 1072 to "diamond_chestplate.png", 1073 to "diamond_leggings.png", 1074 to "diamond_boots.png",
    82 to "jukebox.png", 83 to "chest.png", 84 to "lava.png", 85 to "end_stone.png", 88 to "beacon.png", 89 to "purpur_block.png",
    1075 to "music_disc_13.png", 1076 to "music_disc_cat.png", 1077 to "music_disc_blocks.png", 1078 to "music_disc_chirp.png",
    1079 to "music_disc_5.png", 1080 to "music_disc_11.png", 1081 to "music_disc_bounce.png",
    1082 to "flint_and_steel.png", 1083 to "nether_star.png", 1084 to "elytra.png", 1085 to "firework_rocket.png",
    1086 to "snowball.png", 1087 to "ender_pearl.png",
    90 to "silver_ore.png", 91 to "sulfur_ore.png", 92 to "cinnabar_ore.png",
    93 to "silver_block.png", 94 to "steel_block.png", 95 to "adamant_block.png", 96 to "blast_furnace.png",
    1088 to "sulfur.png", 1089 to "silver_ingot.png", 1090 to "quicksilver.png", 1091 to "steel_ingot.png",
    1092 to "adamant_ingot.png", 1093 to "adamant_pickaxe.png", 1094 to "adamant_sword.png",
    1095 to "adamant_helmet.png", 1096 to "adamant_chestplate.png", 1097 to "adamant_leggings.png", 1098 to "adamant_boots.png",
    // Slabs and stairs reuse their parent material's icon; the name tells them apart.
    102 to "deepslate.png", 103 to "deepslate.png",
    104 to "cobblestone.png", 105 to "cobblestone.png",
    106 to "oak_planks.png", 107 to "oak_planks.png",
    108 to "bricks.png", 109 to "bricks.png",
    110 to "sandstone.png", 111 to "sandstone.png",
    112 to "deepslate_bricks.png", 113 to "deepslate_bricks.png",
    114 to "nether_bricks.png", 115 to "nether_bricks.png",
    116 to "purpur_block.png", 117 to "purpur_block.png",
    118 to "stonecutter.png"
)

// Creative catalog, split into tabs.
val catalogNatural = listOf(3, 2, 40, 46, 60, 39, 41, 71, 1, 6, 38, 36, 37, 14, 16, 17, 15, 45, 79, 75, 70,
    4, 5, 26, 28, 29, 31, 47, 48, 50, 51, 80, 11, 42, 43, 44, 32, 84, 85, 13, 120,
    121, 122, 123)
val catalogOres = listOf(18, 19, 20, 21, 22, 90, 91, 92, 97, 98, 23, 24, 25, 93, 94, 95, 99, 100, 101, 73)
val catalogOcean = listOf(61, 62, 63, 64, 65, 66, 67, 68, 69)
val catalogItems = listOf(1024, 1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034,
    1042, 1043, 1044, 1045, 1046, 1047, 1048, 1049,
    1050, 1051, 1052, 1053, 1054, 1055, 1083, 1085, 1086, 1087,
    1088, 1089, 1090, 1091, 1092,
    1118, 1119, 1120, 1121, 1122, 1123, 1124, 1125, 1126, 1127, 1128, 1129, 1130, 1131,
    1132, 1133, 1134, 1150, 1146, 1147)
val catalogBlessings = listOf(1056, 1057, 1058, 1099, 1100, 1101, 1102, 1103, 1104, 1105, 1106, 1107,
    1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116, 1117,
    1141, 1142, 1143, 1144, 1145)
val catalogGear = listOf(1059, 1060, 1061, 1062, 1063, 1064, 1065, 1066,
    1067, 1068, 1069, 1070, 1071, 1072, 1073, 1074, 1082, 1084,
    1135, 1136, 1137, 1138, 1139, 1140,
    1093, 1094, 1095, 1096, 1097, 1098, 1148, 1149, 1151)
val catalogMusic = listOf(82, 1075, 1076, 1077, 1078, 1079, 1080, 1081)
val catalogBuilding = listOf(10, 27, 30, 49, 52, 8, 57, 9, 53, 54, 55, 56, 58, 59, 72, 74, 76, 77, 78, 81, 7, 33, 34, 35, 96, 118, 88, 89,
    102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 119)

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
        voxelsJson.decodeFromString<InventoryState>(inventoryJson)
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
        try { voxelsJson.decodeFromString<HealthJson>(healthJson) } catch (_: Exception) { HealthJson() }
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
        try { voxelsJson.decodeFromString<HealthJson>(healthJson) } catch (_: Exception) { HealthJson() }
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
        try { voxelsJson.decodeFromString<HealthJson>(healthJson) } catch (_: Exception) { HealthJson() }
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
            Text(stringResource(R.string.estus, h.estus), color = Color(0xFFFFC957), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DebugOverlay(debugJson: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(6.dp)) {
        Text(text = debugJson, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB2FF59))
    }
}
