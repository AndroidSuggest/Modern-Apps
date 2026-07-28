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
    external fun nativeInit(filesDir: String): Boolean
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
    external fun placeBlockAt(x: Float, y: Float): Boolean
    external fun selectSlot(slot: Int)
    external fun getInventoryJson(): String
    external fun getDebugJson(): String
    external fun getStatsJson(): String
}
