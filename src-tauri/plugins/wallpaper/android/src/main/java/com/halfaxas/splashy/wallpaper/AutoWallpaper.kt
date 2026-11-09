package com.halfaxas.splashy.wallpaper

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AutoConfig(val settingsPath: String, val plan: JSONObject)

object AutoWallpaper {
    const val WORK_NAME = "splashy_auto_wallpaper" // unique name => only ONE periodic job can exist
    const val MIN_INTERVAL_MINUTES = 15            // WorkManager hard minimum
    private const val CONFIG_FILE = "splashy_auto_wallpaper.json"

    /** The worker runs with no app/Rust alive, so everything it needs lives in this file. */
    fun saveConfig(context: Context, config: AutoConfig) {
        val json = JSONObject()
            .put("settings_path", config.settingsPath)
            .put("plan", config.plan)
        val tmp = File(context.filesDir, "$CONFIG_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(context.filesDir, CONFIG_FILE))) {
            throw java.io.IOException("Could not save auto wallpaper config")
        }
    }

    fun loadConfig(context: Context): AutoConfig? {
        val f = File(context.filesDir, CONFIG_FILE)
        if (!f.isFile) return null
        val json = JSONObject(f.readText())
        return AutoConfig(json.getString("settings_path"), json.getJSONObject("plan"))
    }

    /** replace=false -> KEEP (don't reset the timer on every app start); true -> UPDATE (apply new interval). */
    fun schedule(context: Context, minutes: Long, replace: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<WallpaperWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
