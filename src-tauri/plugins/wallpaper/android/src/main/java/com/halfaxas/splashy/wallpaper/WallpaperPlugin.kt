package com.halfaxas.splashy.wallpaper

import android.app.Activity
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONObject
import java.io.File

@InvokeArg
class SetWallpaperArgs {
    lateinit var path: String
}

@InvokeArg
class ScheduleArgs {
    var intervalMinutes: Int = 15
    lateinit var settingsPath: String
    lateinit var planJson: String
    var replace: Boolean = false
}

@TauriPlugin
class WallpaperPlugin(private val activity: Activity) : Plugin(activity) {

    @Command
    fun setWallpaper(invoke: Invoke) {
        val args = try {
            invoke.parseArgs(SetWallpaperArgs::class.java)
        } catch (e: Exception) {
            invoke.reject("Invalid arguments: ${e.message}")
            return
        }

        Thread {
            try {
                val file = File(args.path)
                if (!file.isFile) {
                    invoke.reject("File not found: ${args.path}")
                    return@Thread
                }
                val bitmap = WallpaperApplier.decodeScaled(activity, file)
                if (bitmap == null) {
                    invoke.reject("Could not decode image: ${args.path}")
                    return@Thread
                }
                try {
                    WallpaperApplier.apply(activity, bitmap)
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

    @Command
    fun scheduleAutoWallpaper(invoke: Invoke) {
        val args = try {
            invoke.parseArgs(ScheduleArgs::class.java)
        } catch (e: Exception) {
            invoke.reject("Invalid arguments: ${e.message}")
            return
        }
        try {
            val minutes = maxOf(AutoWallpaper.MIN_INTERVAL_MINUTES, args.intervalMinutes)
            AutoWallpaper.saveConfig(activity, AutoConfig(args.settingsPath, JSONObject(args.planJson)))
            AutoWallpaper.schedule(activity, minutes.toLong(), args.replace)
            invoke.resolve(JSObject().put("intervalMinutes", minutes))
        } catch (e: Exception) {
            invoke.reject("Failed to schedule auto wallpaper: ${e.message}")
        }
    }

    @Command
    fun cancelAutoWallpaper(invoke: Invoke) {
        try {
            AutoWallpaper.cancel(activity)
            invoke.resolve()
        } catch (e: Exception) {
            invoke.reject("Failed to cancel auto wallpaper: ${e.message}")
        }
    }
}
