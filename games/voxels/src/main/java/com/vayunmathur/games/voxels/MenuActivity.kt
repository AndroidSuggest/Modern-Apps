package com.vayunmathur.games.voxels

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.voxels.ui.MenuScreen
import com.vayunmathur.games.voxels.ui.WorldCreatorScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.games.voxels.util.WorldInfo
import com.vayunmathur.games.voxels.util.WorldManager

// Launcher screen: lists saved worlds and hosts the world creator. Playing a world starts
// MainActivity (the game) with the world's save directory + seed passed as intent extras.
class MenuActivity : ComponentActivity() {
    // Bumped on each onResume so the world list reloads when returning from the game
    // (keeps last-played ordering fresh).
    private var resumeTick by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Standard app theme (Material You / system colors), matching the other apps — the menu
            // isn't part of the in-game world so it shouldn't use the green Voxels palette.
            DynamicTheme {
                var creating by remember { mutableStateOf(false) }
                var worlds by remember { mutableStateOf(WorldManager.listWorlds(this)) }
                LaunchedEffect(resumeTick) { worlds = WorldManager.listWorlds(this@MenuActivity) }

                Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    if (creating) {
                        WorldCreatorScreen(
                            onBack = { creating = false },
                            onCreate = { name, seedText ->
                                val seed = WorldManager.resolveSeed(seedText)
                                val world = WorldManager.createWorld(this@MenuActivity, name, seed, System.currentTimeMillis())
                                worlds = WorldManager.listWorlds(this@MenuActivity)
                                creating = false
                                play(world)
                            }
                        )
                    } else {
                        MenuScreen(
                            worlds = worlds,
                            onPlay = { play(it) },
                            onDelete = { world ->
                                WorldManager.deleteWorld(this@MenuActivity, world.id)
                                worlds = WorldManager.listWorlds(this@MenuActivity)
                            },
                            onCreate = { creating = true }
                        )
                    }
                }
                }
            }
        }
    }

    private fun play(world: WorldInfo) {
        WorldManager.touch(this, world.id, System.currentTimeMillis())
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("world_dir", world.dir)
            putExtra("world_seed", world.meta.seed)
            putExtra("world_name", world.meta.name)
        })
    }
}
