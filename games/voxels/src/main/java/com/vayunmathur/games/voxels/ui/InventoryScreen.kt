package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.voxels.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

private data class DragState(val from: Int, val pos: Offset, val id: Int, val count: Int)

@Composable
fun InventoryOverlay(
    inventoryJson: String, recipesJson: String, blessingsJson: String, blessingCatalogJson: String,
    onClose: () -> Unit, startTab: Int = 0,
    // This world's achievement tier. Null until the manager has loaded from assets.
    worldAchievements: AchievementsManager? = null,
) {
    val inv = remember(inventoryJson) {
        try { voxelsJson.decodeFromString<InventoryState>(inventoryJson) } catch (_: Exception) { InventoryState() }
    }
    val slots = inv.slots
    // 0 Inventory, 1 Outfit, 2 Crafting, 3 Blessings, 4 Achievements
    var leftTab by remember { mutableStateOf(startTab) }
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
                TabButton(stringResource(R.string.crafting), leftTab == 2) { leftTab = 2 }
                TabButton(stringResource(R.string.blessings), leftTab == 3) { leftTab = 3 }
                TabButton(stringResource(R.string.achievements), leftTab == 4) { leftTab = 4 }
                TabButton(stringResource(R.string.outfit), leftTab == 1) { leftTab = 1 }
                TabButton(stringResource(R.string.inventory), leftTab == 0) { leftTab = 0 }
                Spacer(Modifier.weight(1f))
                TabButton(stringResource(R.string.close), false) { onClose() }
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
                    1 -> OutfitView(inv.armor)
                    3 -> BlessingsView(blessingsJson, blessingCatalogJson, slots)
                    4 -> AchievementsView(worldAchievements)
                    else -> CraftingTable(recipesJson)
                }
            }

            // Right catalog (creative): tap a block to add a stack.
            Column(Modifier.width(236.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TabButton("Natural", catTab == 0) { catTab = 0 }
                    TabButton("Ores", catTab == 1) { catTab = 1 }
                    TabButton("Ocean", catTab == 3) { catTab = 3 }
                    TabButton("Items", catTab == 4) { catTab = 4 }
                    TabButton("Gear", catTab == 6) { catTab = 6 }
                    TabButton(stringResource(R.string.blessings), catTab == 5) { catTab = 5 }
                    TabButton("Music", catTab == 7) { catTab = 7 }
                    TabButton("Build", catTab == 2) { catTab = 2 }
                }
                Spacer(Modifier.height(8.dp))
                val cat = when (catTab) { 0 -> catalogNatural; 1 -> catalogOres; 3 -> catalogOcean; 4 -> catalogItems; 5 -> catalogBlessings; 6 -> catalogGear; 7 -> catalogMusic; else -> catalogBuilding }
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
            if (slot.count > 1 && !isDurabilityItem(slot.id)) Box(Modifier.align(Alignment.BottomEnd)) { Text("${slot.count}", color = Color.White, fontSize = 10.sp) }
            if (isDurabilityItem(slot.id)) DurabilityBar(slot.id, slot.count, Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun InventoryGrid(slots: List<InvSlot>, bounds: MutableMap<Int, Rect>,
                          onDragStart: (Int, Offset) -> Unit, onDragMove: (Offset) -> Unit,
                          onDragEnd: () -> Unit, onDragCancel: () -> Unit) {
    fun cell(i: Int) = @Composable { SlotBox(i, slots.getOrNull(i) ?: InvSlot(0, 0), bounds, true, onDragStart, onDragMove, onDragEnd, onDragCancel) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.inventory_drag_to_rearrange), color = Color.White.copy(0.6f), fontSize = 12.sp)
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
                Recipe(o.getInt("in"), o.getInt("inN"), o.optInt("in2", 0), o.optInt("in2N", 0), o.getInt("out"), o.getInt("outN"), o.optString("cat", "block"), o.optBoolean("known", true), o.optInt("requires", 0))
            }
        } catch (_: Exception) { emptyList() }
    }
    var sel by remember { mutableStateOf(0) }
    var cat by remember { mutableStateOf("block") }
    // Locked recipes are hidden by default so the list grows with the player, but they can be
    // browsed on demand: knowing what a tier leads to is half the reason to chase it.
    var showLocked by remember { mutableStateOf(false) }
    val r = recipes.getOrNull(sel)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Left: product picker, filtered to one shelf so the list stays navigable.
        Column(Modifier.width(190.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                recipeCategories.forEach { (key, label) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (cat == key) Color(0xFF3A6B3A) else Color.White.copy(0.08f))
                            .clickable { cat = key }.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) { Text(label, color = Color.White.copy(0.9f), fontSize = 11.sp) }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (showLocked) Color(0xFF3A6B3A) else Color.White.copy(0.08f))
                        .clickable { showLocked = !showLocked }.padding(horizontal = 8.dp, vertical = 5.dp)
                ) { Text(stringResource(R.string.recipes_show_locked), color = Color.White.copy(0.9f), fontSize = 11.sp) }
            }
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recipes.withIndex().filter { it.value.cat == cat && (it.value.known || showLocked) }.forEach { (i, rec) ->
                    val icon = rememberBlockIcon(rec.outId)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (i == sel) Color(0xFF3A6B3A) else Color.White.copy(alpha = 0.08f))
                            .clickable { sel = i }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (icon != null) {
                            Image(
                                bitmap = icon, contentDescription = null,
                                modifier = Modifier.size(26.dp).alpha(if (rec.known) 1f else 0.35f),
                                filterQuality = FilterQuality.None,
                            )
                        }
                        Text(
                            blockNames[rec.outId] ?: "",
                            color = Color.White.copy(if (rec.known) 0.95f else 0.4f), fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        // Right: the crafting table (2x2 grid) with the selected recipe placed + its product.
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.crafting_table), color = Color.White.copy(0.6f), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 2) {
                                val idx = row * 2 + col
                                when {
                                    r == null -> GridCell(0, 0)
                                    idx == 0 -> GridCell(r.inId, r.inN)
                                    idx == 1 && r.in2Id != 0 -> GridCell(r.in2Id, r.in2N)
                                    else -> GridCell(0, 0)
                                }
                            }
                        }
                    }
                }
                Text("→", color = Color.White.copy(0.8f), fontSize = 24.sp)
                GridCell(r?.outId ?: 0, r?.outN ?: 0)
            }
            // A locked recipe says what opens it, so the tree reads as a path rather than a wall.
            if (r != null && !r.known) {
                Text(
                    stringResource(R.string.recipe_requires, blockNames[r.requires] ?: "?"),
                    color = Color(0xFFD9B36C), fontSize = 12.sp,
                )
            }
            val unlocked = r != null && r.known
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (unlocked) Color(0xFF3A6B3A) else Color.White.copy(0.10f))
                    .clickable(enabled = unlocked) { try { VoxelsNative.craft(sel) } catch (_: Exception) {} }
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(R.string.craft),
                    color = Color.White.copy(if (unlocked) 1f else 0.4f),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private data class Recipe(
    val inId: Int, val inN: Int, val in2Id: Int, val in2N: Int,
    val outId: Int, val outN: Int, val cat: String,
    // Unlocked once `requires` has been crafted; 0 means available from the start.
    val known: Boolean,
    val requires: Int,
)

// Crafting shelves, in the order they appear as filter chips.
private val recipeCategories = listOf(
    "block" to "Blocks", "tool" to "Tools", "armor" to "Armor",
    "material" to "Materials", "food" to "Food", "blessing" to "Blessings",
)

private data class BlessingInfo(val id: Int, val name: String, val effect: String)

private fun parseBlessings(json: String, key: String?): List<BlessingInfo> = try {
    val arr = if (key != null) org.json.JSONObject(json).getJSONArray(key) else org.json.JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        BlessingInfo(o.getInt("id"), o.optString("name"), o.optString("effect"))
    }
} catch (_: Exception) { emptyList() }

/**
 * Blessings are permanent attunements rather than consumables: bind a charm from the inventory to
 * one of the slots, or release a slot to get the charm back.
 */
@Composable
private fun BlessingsView(blessingsJson: String, catalogJson: String, slots: List<InvSlot>) {
    val attuned = remember(blessingsJson) { parseBlessings(blessingsJson, "slots") }
    val catalog = remember(catalogJson) { parseBlessings(catalogJson, null) }
    // Charms sitting in the inventory that aren't bound yet.
    val held = remember(slots, attuned) {
        val boundIds = attuned.map { it.id }.toSet()
        slots.withIndex()
            .filter { (_, s) -> s.id != 0 && catalog.any { it.id == s.id } && s.id !in boundIds }
            .map { (i, s) -> i to s.id }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.attuned), color = Color.White.copy(0.6f), fontSize = 12.sp)
            attuned.forEachIndexed { i, b ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (b.id != 0) Color(0xFF3A5A6B) else Color.White.copy(0.06f))
                        .clickable(enabled = b.id != 0) {
                            try { VoxelsNative.releaseBlessing(i) } catch (_: Exception) {}
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val icon = if (b.id != 0) rememberBlockIcon(b.id) else null
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(30.dp), filterQuality = FilterQuality.None)
                    }
                    Column {
                        Text(
                            if (b.id != 0) b.name else stringResource(R.string.empty_slot),
                            color = Color.White.copy(if (b.id != 0) 0.95f else 0.45f), fontSize = 12.sp
                        )
                        if (b.id != 0) Text(b.effect, color = Color.White.copy(0.6f), fontSize = 10.sp)
                    }
                }
            }
            Text(stringResource(R.string.blessing_tap_hint), color = Color.White.copy(0.5f), fontSize = 10.sp)
        }

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.charms_held), color = Color.White.copy(0.6f), fontSize = 12.sp)
            if (held.isEmpty()) {
                Text(stringResource(R.string.no_charms), color = Color.White.copy(0.45f), fontSize = 11.sp)
            }
            held.forEach { (invIdx, id) ->
                val info = catalog.firstOrNull { it.id == id } ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.08f))
                        .clickable { try { VoxelsNative.attuneBlessing(invIdx) } catch (_: Exception) {} }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val icon = rememberBlockIcon(id)
                    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp), filterQuality = FilterQuality.None)
                    Column {
                        Text(info.name, color = Color.White.copy(0.95f), fontSize = 12.sp)
                        Text(info.effect, color = Color.White.copy(0.6f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutfitView(armor: List<InvSlot>) {
    val labels = listOf("Helmet", "Chestplate", "Leggings", "Boots")
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.equipped_armor), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(stringResource(R.string.hold_an_armor_piece_in_hand_to_equip_it), color = Color.White.copy(0.6f), fontSize = 12.sp)
        for (i in 0 until 4) {
            val s = armor.getOrNull(i) ?: InvSlot(0, 0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(0.4f))
                        .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = if (s.id != 0) rememberBlockIcon(s.id) else null
                    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp), filterQuality = FilterQuality.None)
                }
                Text(if (s.id != 0) (blockNames[s.id] ?: "?") else "${labels[i]}: empty", color = Color.White.copy(if (s.id != 0) 0.95f else 0.5f))
            }
        }
    }
}

/// This world's achievements. The app-level tier is what the Games Hub shows; this is the one that
/// starts at zero in a new world, which is the whole point of tracking both.
@Composable
private fun AchievementsView(manager: AchievementsManager?) {
    if (manager == null) {
        Text(stringResource(R.string.achievements_unavailable), color = Color.White.copy(0.6f), fontSize = 12.sp)
        return
    }
    val statuses by manager.getAchievementStatuses().collectAsState(initial = emptyList())
    // Earned first, then whatever is closest to being earned.
    val ordered = remember(statuses) {
        statuses.sortedWith(
            compareByDescending<com.vayunmathur.library.util.AchievementStatus> { it.isUnlocked }
                .thenByDescending { it.progress.toFloat() / it.achievement.targetProgress.coerceAtLeast(1) }
        )
    }
    val earned = ordered.count { it.isUnlocked }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.achievements_earned, earned, ordered.size),
            color = Color.White.copy(0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
               verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (st in ordered) {
                val done = st.isUnlocked
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (done) Color(0xFF2C4A2C) else Color.White.copy(0.06f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (done) "\u2713" else "\u00b7", color = Color.White.copy(if (done) 0.9f else 0.35f), fontSize = 14.sp)
                    Column(Modifier.weight(1f)) {
                        Text(st.achievement.name, color = Color.White.copy(if (done) 0.95f else 0.6f), fontSize = 12.sp)
                        Text(st.achievement.description, color = Color.White.copy(0.45f), fontSize = 10.sp)
                    }
                    // Only the counting achievements have anything useful to show here.
                    if (!done && st.achievement.targetProgress > 1) {
                        Text("${st.progress}/${st.achievement.targetProgress}",
                             color = Color.White.copy(0.5f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
