package com.vayunmathur.games.voxels

import com.vayunmathur.games.voxels.R
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.games.voxels.ui.*
import com.vayunmathur.games.voxels.util.VoxelsAchievementsManager
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.GameHubComposeHook
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // World to load: passed by MenuActivity. Fall back to a default world if launched directly.
        val worldDir = intent.getStringExtra("world_dir")
            ?: java.io.File(filesDir, "worlds/default").apply { mkdirs() }.absolutePath
        val worldSeed = intent.getIntExtra("world_seed", 0xB10CCA)
        if (VoxelsNative.isAvailable) {
            try { VoxelsNative.nativeInit(worldDir, worldSeed) } catch (e: Exception) {
                android.util.Log.e("VoxelsMain", "nativeInit failed", e)
            }
        }
        com.vayunmathur.games.voxels.util.SoundFx.init(this)
        com.vayunmathur.games.voxels.util.MusicFx.startAmbient(this)
        setContent {
            VoxelsTheme {
                var inventoryJson by remember { mutableStateOf("""{"selected":0,"slots":[{"id":3,"count":64},{"id":2,"count":64},{"id":1,"count":64},{"id":4,"count":16},{"id":10,"count":32},{"id":6,"count":32},{"id":7,"count":16},{"id":8,"count":16},{"id":9,"count":16}]}""") }
                var debugJson by remember { mutableStateOf("Voxels Engine\nInitializing Vulkan...\nMatcha Atlas 64x64") }
                var healthJson by remember { mutableStateOf("{}") }
                var showDebug by remember { mutableStateOf(true) }
                var flying by remember { mutableStateOf(false) }
                var sneaking by remember { mutableStateOf(false) }
                var inventoryOpen by remember { mutableStateOf(false) }
                var paused by remember { mutableStateOf(false) }
                val activity = LocalContext.current as? android.app.Activity
                var invStartTab by remember { mutableStateOf(0) }
                var recipesJson by remember { mutableStateOf("[]") }
                var blessingsJson by remember { mutableStateOf("""{"slots":[]}""") }
                var blessingCatalogJson by remember { mutableStateOf("[]") }
                var smeltingJson by remember { mutableStateOf("[]") }
                var smeltJson by remember { mutableStateOf("{}") }
                var containerJson by remember { mutableStateOf("""{"slots":[]}""") }
                var furnaceOpen by remember { mutableStateOf(false) }
                var furnaceIsBlast by remember { mutableStateOf(false) }
                var chestOpen by remember { mutableStateOf(false) }
                var cutsJson by remember { mutableStateOf("[]") }
                var stonecutterOpen by remember { mutableStateOf(false) }
                var tradesJson by remember { mutableStateOf("{}") }
                var tradeOpen by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { if (VoxelsNative.isAvailable) try { recipesJson = VoxelsNative.getRecipesJson(); smeltingJson = VoxelsNative.getSmeltingJson(); blessingCatalogJson = VoxelsNative.getBlessingCatalogJson(); cutsJson = VoxelsNative.getCutsJson() } catch (_: Exception) {} }
                var achievementsManager by remember { mutableStateOf<VoxelsAchievementsManager?>(null) }
                val newAchievement by (achievementsManager?.newAchievement?.collectAsState() ?: remember { mutableStateOf(null) })

                LaunchedEffect(Unit) {
                    try {
                        val json = assets.open("achievements.json").bufferedReader().readText()
                        achievementsManager = VoxelsAchievementsManager(this@MainActivity, json)
                    } catch (_: Exception) {}
                }

                LaunchedEffect(Unit) {
                    var prevHp = 20f
                    // Footsteps and atmosphere cues arrive as monotonic counters; a change means the
                    // engine wants a sound. See ambience.rs.
                    var prevStep = -1
                    var prevCue = -1
                    while (isActive) {
                        if (VoxelsNative.isAvailable) {
                            try {
                                try {
                                    val amb = org.json.JSONObject(VoxelsNative.getAmbienceJson())
                                    val stepN = amb.optInt("stepN", prevStep)
                                    if (prevStep >= 0 && stepN != prevStep) {
                                        com.vayunmathur.games.voxels.util.SoundFx.playStep(amb.optInt("stepMat", 0))
                                    }
                                    prevStep = stepN
                                    val cueN = amb.optInt("cueN", prevCue)
                                    if (prevCue >= 0 && cueN != prevCue) {
                                        when (amb.optInt("cueKind", 0)) {
                                            1 -> com.vayunmathur.games.voxels.util.SoundFx.playCave()
                                            2 -> com.vayunmathur.games.voxels.util.SoundFx.playStalk()
                                        }
                                    }
                                    prevCue = cueN
                                } catch (_: Exception) {}
                                inventoryJson = VoxelsNative.getInventoryJson()
                                debugJson = VoxelsNative.getDebugJson()
                                if (furnaceOpen) smeltJson = VoxelsNative.getSmeltJson()
                                if (chestOpen) containerJson = VoxelsNative.getContainerJson()
                                if (inventoryOpen) {
                                    blessingsJson = VoxelsNative.getBlessingsJson()
                                    // Recipes reveal themselves as the player picks up ingredients.
                                    recipesJson = VoxelsNative.getRecipesJson()
                                }
                                try {
                                    healthJson = VoxelsNative.getHealthJson()
                                    val hp = org.json.JSONObject(healthJson).optDouble("hp", prevHp.toDouble()).toFloat()
                                    if (hp < prevHp - 0.5f) {
                                        if (prevHp - hp > 6f) com.vayunmathur.games.voxels.util.SoundFx.playExplode()
                                        else com.vayunmathur.games.voxels.util.SoundFx.playHurt()
                                    }
                                    prevHp = hp
                                } catch (_: Exception) {}
                                try { flying = org.json.JSONObject(debugJson).optBoolean("flying", flying) } catch (_: Exception) {}
                                val stats = VoxelsNative.getStatsJson()
                                try {
                                    val obj = org.json.JSONObject(stats)
                                    val placed = obj.optInt("placed", 0)
                                    val broken = obj.optInt("broken", 0)
                                    val walked = obj.optInt("walked", 0)
                                    achievementsManager?.let { mgr ->
                                        if (broken > 0) mgr.onAchievementUnlocked("first_block")
                                        if (placed > 0) mgr.onAchievementUnlocked("first_place")
                                        mgr.onProgressUpdated("builder_100", placed)
                                        mgr.onProgressUpdated("miner_100", broken)
                                        mgr.onProgressUpdated("explorer_100", walked)
                                        if (obj.optBoolean("night", false)) mgr.onAchievementUnlocked("night_survivor")
                                        if (obj.optInt("depth", 128) <= 8) mgr.onAchievementUnlocked("deep_diver")
                                        if (obj.optBoolean("silver", false)) mgr.onAchievementUnlocked("silver_tongue")
                                        if (obj.optBoolean("steel", false)) mgr.onAchievementUnlocked("steelworker")
                                        if (obj.optBoolean("adamant", false)) mgr.onAchievementUnlocked("alchemist")
                                        if (obj.optBoolean("blessing", false)) mgr.onAchievementUnlocked("blessed")
                                        if (obj.optBoolean("fullArmor", false)) mgr.onAchievementUnlocked("fully_armed")
                                        if (obj.optBoolean("nether", false)) mgr.onAchievementUnlocked("nether_bound")
                                        if (obj.optBoolean("end", false)) mgr.onAchievementUnlocked("the_end")
                                        if (obj.optBoolean("dragon", false)) mgr.onAchievementUnlocked("dragonslayer")
                                        if (obj.optBoolean("wither", false)) mgr.onAchievementUnlocked("withering")
                                        if (obj.optInt("beacon", 0) >= 4) mgr.onAchievementUnlocked("beacon_master")
                                        if (obj.optBoolean("elytra", false)) mgr.onAchievementUnlocked("sky_bound")
                                        if (obj.optBoolean("maxHearts", false)) mgr.onAchievementUnlocked("heart_of_gold")
                                        if (obj.optInt("attuned", 0) > 0) mgr.onAchievementUnlocked("attuned")
                                        if (obj.optInt("attuned", 0) >= 3) mgr.onAchievementUnlocked("pantheon")
                                        if (obj.optBoolean("traded", false)) mgr.onAchievementUnlocked("first_trade")
                                        if (obj.optBoolean("trader", false)) mgr.onAchievementUnlocked("master_trader")
                                        if (obj.optBoolean("sheared", false)) mgr.onAchievementUnlocked("first_shear")
                                        if (obj.optBoolean("fished", false)) mgr.onAchievementUnlocked("first_catch")
                                        if (obj.optBoolean("brushed", false)) mgr.onAchievementUnlocked("first_dig")
                                        if (obj.optBoolean("harvested", false)) mgr.onAchievementUnlocked("first_harvest")
                                        if (obj.optBoolean("rested", false)) mgr.onAchievementUnlocked("first_rest")
                                        mgr.onProgressUpdated("recipes_50", obj.optInt("recipes", 0))
                                    }
                                } catch (_: Exception) {}
                            } catch (_: Exception) {}
                        }
                        delay(150)
                    }
                }

                GameHubComposeHook("voxels", achievementsManager)

                Box(Modifier.fillMaxSize()) {
                    if (VoxelsNative.isAvailable) {
                        AndroidView(factory = { ctx ->
                            // No opaque background: a SurfaceView shows its Vulkan layer through a
                            // transparent hole in the window. Painting the view black covered that hole.
                            VoxelSurfaceView(ctx)
                        }, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFF0E1A0F)), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.rust_lib_missing_vulkan_unavailable), color = Color.White)
                        }
                    }

                    // Fullscreen world interaction + floating look: drag on the right half looks around;
                    // tap places a block and long-press breaks one — on either half of the screen.
                    if (VoxelsNative.isAvailable) {
                        FloatingLookJoystick(
                            modifier = Modifier.fillMaxSize(),
                            onLookRate = { ry, rp -> try { VoxelsNative.onLookInput(ry, rp) } catch (_: Exception) {} },
                            onPlace = { off -> try {
                                val code = VoxelsNative.placeBlockAt(off.x, off.y)
                                when (code) {
                                    1 -> com.vayunmathur.games.voxels.util.SoundFx.playPlace()
                                    11 -> { invStartTab = 2; inventoryOpen = true } // crafting table
                                    12, 14 -> { // furnace / blast furnace
                                        furnaceIsBlast = code == 14
                                        smeltJson = try { VoxelsNative.getSmeltJson() } catch (_: Exception) { "{}" }
                                        furnaceOpen = true
                                    }
                                    13 -> { // jukebox: play the held disc (or stop)
                                        val inv = try { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<com.vayunmathur.games.voxels.ui.InventoryState>(inventoryJson) } catch (_: Exception) { null }
                                        val held = inv?.slots?.getOrNull(inv.selected)?.id ?: 0
                                        com.vayunmathur.games.voxels.util.MusicFx.toggle(this@MainActivity, com.vayunmathur.games.voxels.ui.discTrack[held])
                                    }
                                    15 -> stonecutterOpen = true // stonecutter
                                    // Every villager has its own profession and level, so the stall is
                                    // read fresh from the one that was tapped.
                                    20 -> {
                                        tradesJson = try { VoxelsNative.getTradesJson() } catch (_: Exception) { "{}" }
                                        tradeOpen = true
                                    }
                                    30 -> { // chest
                                        containerJson = try { VoxelsNative.getContainerJson() } catch (_: Exception) { """{"slots":[]}""" }
                                        chestOpen = true
                                        com.vayunmathur.games.voxels.util.SoundFx.playPlace()
                                    }
                                    41 -> com.vayunmathur.games.voxels.util.SoundFx.playPlace() // ignited a portal
                                }
                            } catch (_: Exception) {} },
                            onBreak = { off -> try { if (VoxelsNative.breakBlockAt(off.x, off.y)) com.vayunmathur.games.voxels.util.SoundFx.playBreak() } catch (_: Exception) {} }
                        )
                    }

                    Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 96.dp)) {
                        Joystick(modifier = Modifier, isLook = false,
                            onMove = { x, y -> if (VoxelsNative.isAvailable) try { VoxelsNative.onMoveInput(x, y) } catch (_: Exception) {} },
                            onLookDelta = { _, _ -> })
                    }

                    // Two stacked action buttons where the look joystick used to be. Walking: Jump / Sneak.
                    // Flying: Up / Down. Double-tap the top button to toggle flying.
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HoldButton(
                            label = if (flying) "Up" else "Jump",
                            dimmed = !flying && sneaking,
                            onPress = { if (VoxelsNative.isAvailable) try { VoxelsNative.setJump(true) } catch (_: Exception) {} },
                            onRelease = { if (VoxelsNative.isAvailable) try { VoxelsNative.setJump(false) } catch (_: Exception) {} },
                            onDoubleTap = {
                                if (VoxelsNative.isAvailable) try { VoxelsNative.toggleFly() } catch (_: Exception) {}
                                flying = !flying
                                if (flying) {
                                    sneaking = false
                                    if (VoxelsNative.isAvailable) try { VoxelsNative.setSneak(false) } catch (_: Exception) {}
                                }
                            }
                        )
                        if (flying) {
                            HoldButton(
                                label = stringResource(R.string.down),
                                onPress = { if (VoxelsNative.isAvailable) try { VoxelsNative.setFlyDown(true) } catch (_: Exception) {} },
                                onRelease = { if (VoxelsNative.isAvailable) try { VoxelsNative.setFlyDown(false) } catch (_: Exception) {} }
                            )
                        } else {
                            HoldButton(
                                label = if (sneaking) "Sneaking" else "Sneak",
                                dimmed = sneaking,
                                onPress = {
                                    sneaking = !sneaking
                                    if (VoxelsNative.isAvailable) try { VoxelsNative.setSneak(sneaking) } catch (_: Exception) {}
                                }
                            )
                        }
                    }

                    // Health/effects HUD just above the hotbar.
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 78.dp)) {
                        HealthOverlay(healthJson)
                    }
                    // Boss health bar (Ender Dragon) at the top.
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 70.dp)) {
                        BossBar(healthJson)
                    }
                    // Elytra glide indicator, above the hotbar.
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp)) {
                        GlideIndicator(healthJson)
                    }

                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                        Hotbar(inventoryJson = inventoryJson,
                            onSelect = { slot -> if (VoxelsNative.isAvailable) try { VoxelsNative.selectSlot(slot) } catch (_: Exception) {} },
                            onOpenInventory = { invStartTab = 0; inventoryOpen = true })
                    }

                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 36.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IconButton(onClick = { paused = true }) { IconMenu() }
                                IconButton(onClick = { showDebug = !showDebug }) { IconSettings() }
                            }
                        }
                        if (showDebug) { DebugOverlay(debugJson = debugJson) }
                    }

                    newAchievement?.let { ach ->
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 80.dp)) {
                            AchievementNotification(ach) { achievementsManager?.dismissNotification() }
                        }
                    }

                    if (inventoryOpen && VoxelsNative.isAvailable) {
                        InventoryOverlay(
                            inventoryJson = inventoryJson, recipesJson = recipesJson,
                            blessingsJson = blessingsJson, blessingCatalogJson = blessingCatalogJson,
                            onClose = { inventoryOpen = false }, startTab = invStartTab
                        )
                    }

                    if (tradeOpen && VoxelsNative.isAvailable) {
                        TradeOverlay(tradesJson = tradesJson, onClose = { tradeOpen = false })
                    }

                    if (furnaceOpen && VoxelsNative.isAvailable) {
                        FurnaceOverlay(
                            smeltingJson = smeltingJson, smeltJson = smeltJson,
                            isBlast = furnaceIsBlast, onClose = { furnaceOpen = false }
                        )
                    }

                    if (chestOpen && VoxelsNative.isAvailable) {
                        ChestOverlay(containerJson = containerJson, inventoryJson = inventoryJson, onClose = {
                            chestOpen = false
                            try { VoxelsNative.closeContainer() } catch (_: Exception) {}
                        })
                    }

                    if (stonecutterOpen && VoxelsNative.isAvailable) {
                        StonecutterOverlay(cutsJson = cutsJson, inventoryJson = inventoryJson,
                            onClose = { stonecutterOpen = false })
                    }

                    if (paused) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                                .pointerInput(Unit) { detectTapGestures { } }, // swallow taps behind the menu
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(stringResource(R.string.paused), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                                Button(onClick = { paused = false }, modifier = Modifier.width(240.dp)) { Text(stringResource(R.string.resume)) }
                                Button(onClick = {
                                    // onDestroy() saves the world and tears down the engine; finishing
                                    // returns to MenuActivity (still on the back stack).
                                    activity?.finish()
                                }, modifier = Modifier.width(240.dp)) { Text(stringResource(R.string.save_quit_to_menu)) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        com.vayunmathur.games.voxels.util.MusicFx.stop()
        if (VoxelsNative.isAvailable) { try { VoxelsNative.nativeOnDestroy() } catch (_: Exception) {} }
        super.onDestroy()
    }
}
