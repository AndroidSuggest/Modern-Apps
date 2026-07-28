package com.vayunmathur.games.voxels

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        if (VoxelsNative.isAvailable) {
            try { VoxelsNative.nativeInit(filesDir.absolutePath) } catch (e: Exception) {
                android.util.Log.e("VoxelsMain", "nativeInit failed", e)
            }
        }
        setContent {
            VoxelsTheme {
                var inventoryJson by remember { mutableStateOf("""{"selected":0,"slots":[{"id":3,"count":64},{"id":2,"count":64},{"id":1,"count":64},{"id":4,"count":16},{"id":10,"count":32},{"id":6,"count":32},{"id":7,"count":16},{"id":8,"count":16},{"id":9,"count":16}]}""") }
                var debugJson by remember { mutableStateOf("Voxels Engine\nInitializing Vulkan...\nMatcha Atlas 64x64") }
                var showDebug by remember { mutableStateOf(true) }
                var achievementsManager by remember { mutableStateOf<VoxelsAchievementsManager?>(null) }
                val newAchievement by (achievementsManager?.newAchievement?.collectAsState() ?: remember { mutableStateOf(null) })

                LaunchedEffect(Unit) {
                    try {
                        val json = assets.open("achievements.json").bufferedReader().readText()
                        achievementsManager = VoxelsAchievementsManager(this@MainActivity, json)
                    } catch (_: Exception) {}
                }

                LaunchedEffect(Unit) {
                    while (isActive) {
                        if (VoxelsNative.isAvailable) {
                            try {
                                inventoryJson = VoxelsNative.getInventoryJson()
                                debugJson = VoxelsNative.getDebugJson()
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
                            VoxelSurfaceView(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                        }, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFF0E1A0F)), contentAlignment = Alignment.Center) {
                            Text("Rust lib missing — Vulkan unavailable", color = Color.White)
                        }
                    }

                    Crosshair(modifier = Modifier.align(Alignment.Center))

                    Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 96.dp)) {
                        Joystick(modifier = Modifier, isLook = false,
                            onMove = { x, y -> if (VoxelsNative.isAvailable) try { VoxelsNative.onJoystickInput(x, y, 0f, 0f) } catch (_: Exception) {} },
                            onLookDelta = { _, _ -> })
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 96.dp)) {
                        Joystick(modifier = Modifier, isLook = true,
                            onMove = { _, _ -> },
                            onLookDelta = { dyaw, dpitch -> if (VoxelsNative.isAvailable) try { VoxelsNative.onJoystickInput(0f, 0f, dyaw, dpitch) } catch (_: Exception) {} })
                    }

                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                        Hotbar(inventoryJson = inventoryJson,
                            onSelect = { slot -> if (VoxelsNative.isAvailable) try { VoxelsNative.selectSlot(slot) } catch (_: Exception) {} })
                    }

                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 36.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = { if (VoxelsNative.isAvailable) try { VoxelsNative.breakBlock() } catch (_: Exception) {} }) { Text("Break") }
                                Button(onClick = { if (VoxelsNative.isAvailable) try { VoxelsNative.placeBlock() } catch (_: Exception) {} }) { Text("Place") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = { if (VoxelsNative.isAvailable) try { VoxelsNative.onAction(true, false, false) } catch (_: Exception) {} }) { Text("Jump") }
                                Button(onClick = { if (VoxelsNative.isAvailable) try { VoxelsNative.onAction(false, false, true) } catch (_: Exception) {} }) { Text("Fly") }
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
                }
            }
        }
    }

    override fun onDestroy() {
        if (VoxelsNative.isAvailable) { try { VoxelsNative.nativeOnDestroy() } catch (_: Exception) {} }
        super.onDestroy()
    }
}
