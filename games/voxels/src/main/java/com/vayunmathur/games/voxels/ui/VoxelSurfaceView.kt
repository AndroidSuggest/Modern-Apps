package com.vayunmathur.games.voxels.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vayunmathur.games.voxels.util.VoxelsNative

class VoxelSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var ready = false
    init { holder.addCallback(this) }
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!VoxelsNative.isAvailable) return
        try {
            VoxelsNative.surfaceCreated(holder.surface)
            ready = true
        } catch (e: Exception) {
            android.util.Log.e("VoxelSurface", "surfaceCreated failed", e)
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!VoxelsNative.isAvailable || !ready) return
        try { VoxelsNative.surfaceChanged(width, height) } catch (e: Exception) {
            android.util.Log.e("VoxelSurface", "surfaceChanged failed", e)
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!VoxelsNative.isAvailable) return
        try {
            VoxelsNative.surfaceDestroyed()
            ready = false
        } catch (e: Exception) {
            android.util.Log.e("VoxelSurface", "surfaceDestroyed failed", e)
        }
    }
}
