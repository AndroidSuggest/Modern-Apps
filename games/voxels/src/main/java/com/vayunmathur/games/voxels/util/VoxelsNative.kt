package com.vayunmathur.games.voxels.util

import android.util.Log
import android.view.Surface

object VoxelsNative {
    val isAvailable: Boolean = try {
        System.loadLibrary("voxels_engine")
        Log.i("VoxelsNative", "libvoxels_engine loaded")
        true
    } catch (t: Throwable) {
        Log.e("VoxelsNative", "System.loadLibrary(voxels_engine) failed", t)
        false
    }
    external fun nativeInit(filesDir: String, seed: Int): Boolean
    external fun surfaceCreated(surface: Surface)
    external fun surfaceChanged(width: Int, height: Int)
    external fun surfaceDestroyed()
    external fun nativeOnDestroy()
    external fun onMoveInput(moveX: Float, moveY: Float)
    external fun onLookInput(lookYawRate: Float, lookPitchRate: Float)
    external fun setJump(held: Boolean)
    external fun setFlyDown(held: Boolean)
    external fun setSneak(on: Boolean)
    external fun toggleFly()
    external fun breakBlockAt(x: Float, y: Float): Boolean
    // 0 = nothing, 1 = placed, 11 crafting table, 12 furnace, 13 jukebox, 14 blast furnace,
    // 20 villager trade, 30 chest opened, 41 portal lit.
    external fun placeBlockAt(x: Float, y: Float): Int
    external fun selectSlot(slot: Int)
    external fun moveItem(from: Int, to: Int)
    external fun giveBlock(id: Int)
    external fun craft(recipe: Int): Boolean
    external fun trade(index: Int): Boolean
    external fun getRecipesJson(): String
    external fun getTradesJson(): String
    external fun getInventoryJson(): String
    external fun getDebugJson(): String
    external fun getStatsJson(): String
    external fun getHealthJson(): String
    // Furnace: recipe catalog, live job state, and start/stop.
    external fun getSmeltingJson(): String
    external fun getSmeltJson(): String
    external fun startSmelt(recipe: Int, blast: Boolean): Boolean
    external fun stopSmelt()
    // Chest containers, keyed by the block the player last opened.
    external fun getContainerJson(): String
    external fun containerTake(idx: Int): Boolean
    external fun containerPut(idx: Int): Boolean
    external fun closeContainer()
}
