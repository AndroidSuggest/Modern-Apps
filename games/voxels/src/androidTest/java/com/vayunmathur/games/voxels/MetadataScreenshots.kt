package com.vayunmathur.games.voxels

import android.app.Activity
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:games:voxels:metadata`.
 * Voxels uses native Rust + Vulkan; Compose captureToImage won't capture GL.
 * Uses UiAutomation.takeScreenshot() for full device capture.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snapDevice(index: Int) {
        Thread.sleep(900)
        val bmp: Bitmap? = try {
            instrumentation.uiAutomation.takeScreenshot()
        } catch (_: Exception) { null }
        bmp?.let { b ->
            File(outDir, "$index.png").outputStream().use { out ->
                b.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    @Test
    fun generateStoreScreenshots() {
        outDir
        // Try MenuActivity first if present, else MainActivity
        try {
            val menuCls = Class.forName("com.vayunmathur.games.voxels.MenuActivity") as Class<Activity>
            ActivityScenario.launch(menuCls).use {
                Thread.sleep(2500)
                snapDevice(1)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(4000)
                snapDevice(2)
            }
        } catch (_: Exception) {
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(4000)
                snapDevice(1)
                Thread.sleep(1500)
                snapDevice(2)
            }
        }
    }
}
