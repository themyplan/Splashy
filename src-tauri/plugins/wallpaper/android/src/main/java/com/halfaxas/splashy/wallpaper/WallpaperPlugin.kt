package com.halfaxas.splashy.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import java.io.File

@InvokeArg
class SetWallpaperArgs {
    lateinit var path: String
}

@TauriPlugin
class WallpaperPlugin(private val activity: Activity) : Plugin(activity) {

    companion object {
        // Max decoded pixels (~48 MB as ARGB_8888) to avoid OOM on big photos.
        private const val MAX_PIXELS = 12_000_000L
    }

    @Command
    fun setWallpaper(invoke: Invoke) {
        val args = try {
            invoke.parseArgs(SetWallpaperArgs::class.java)
        } catch (e: Exception) {
            invoke.reject("Invalid arguments: ${e.message}")
            return
        }

        // Decoding + setting a bitmap is heavy: keep it off the main thread.
        Thread {
            try {
                val file = File(args.path)
                if (!file.isFile) {
                    invoke.reject("File not found: ${args.path}")
                    return@Thread
                }
                val bitmap = decodeScaled(file)
                if (bitmap == null) {
                    invoke.reject("Could not decode image: ${args.path}")
                    return@Thread
                }
                try {
                    applyBitmap(bitmap)
                } finally {
                    bitmap.recycle()
                }
                invoke.resolve(JSObject().put("ok", true))
            } catch (e: OutOfMemoryError) {
                invoke.reject("Out of memory while loading the image")
            } catch (e: Exception) {
                invoke.reject("Failed to set wallpaper: ${e.message}")
            }
        }.start()
    }

    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val wm = WallpaperManager.getInstance(activity)
        val dm = activity.resources.displayMetrics
        val targetW = maxOf(wm.desiredMinimumWidth, dm.widthPixels)
        val targetH = maxOf(wm.desiredMinimumHeight, dm.heightPixels)

        // Scale needed to cover the screen; downsample by powers of two while still >= that.
        val needed = maxOf(targetW.toFloat() / w, targetH.toFloat() / h)
        var sample = 1
        while (1f / (sample * 2) >= needed) sample *= 2
        // Hard memory cap, even if that means slightly below screen resolution.
        while ((w.toLong() / sample) * (h.toLong() / sample) > MAX_PIXELS) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun applyBitmap(bitmap: Bitmap) {
        val wm = WallpaperManager.getInstance(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            wm.setBitmap(
                bitmap,
                null,
                true,
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
        } else {
            wm.setBitmap(bitmap)
        }
    }
}
