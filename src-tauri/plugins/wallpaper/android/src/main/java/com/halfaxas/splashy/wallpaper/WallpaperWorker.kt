package com.halfaxas.splashy.wallpaper

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Full cycle in Kotlin (no Rust, no Activity needed):
 * pick endpoint -> Unsplash API -> pick photo -> download (server-cropped to screen) -> set wallpaper.
 */
class WallpaperWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "SplashyWorker"
        private const val API_BASE = "https://api.unsplash.com"
        private const val FALLBACK = "/photos/random?count=30&orientation=portrait"
    }

    override fun doWork(): Result {
        return try {
            runCycle()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Network/IO problem: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cycle failed: ${e.message}", e)
            Result.success() // keep the periodic schedule alive
        }
    }

    private fun runCycle() {
        val ctx = applicationContext
        val cfg = AutoWallpaper.loadConfig(ctx)
        if (cfg == null) {
            Log.w(TAG, "No config file; skipping")
            return
        }
        val apiKey = readApiKey(cfg.settingsPath)
        if (apiKey == null) {
            Log.w(TAG, "No api_key in settings; skipping")
            return
        }

        val endpoint = chooseEndpoint(cfg.plan)
        val url = if (endpoint.startsWith("http")) endpoint else API_BASE + endpoint
        val body = String(httpGet(url, apiKey), Charsets.UTF_8)
        val photo = pickPhoto(body) ?: throw IOException("No photo in response for $endpoint")

        val (w, h) = WallpaperApplier.targetSize(ctx)
        val urls = photo.getJSONObject("urls")
        val raw = urls.optString("raw", "")
        val imageUrl = if (raw.isNotEmpty()) {
            val sep = if (raw.contains("?")) "&" else "?"
            // Let the server crop to screen size: small download, small bitmap.
            "$raw${sep}w=${w.coerceAtMost(3000)}&h=${h.coerceAtMost(3000)}&fit=crop&crop=entropy&q=85&fm=jpg"
        } else {
            urls.getString("regular")
        }

        val file = File(ctx.cacheDir, "auto_wallpaper.jpg")
        try {
            download(imageUrl, file)
            val bitmap = WallpaperApplier.decodeScaled(ctx, file)
                ?: throw IOException("Could not decode downloaded image")
            try {
                WallpaperApplier.apply(ctx, bitmap)
            } finally {
                bitmap.recycle()
            }
        } finally {
            file.delete()
        }

        // Unsplash API guideline: report the download once the photo is actually used.
        try {
            val dl = photo.optJSONObject("links")?.optString("download_location", "") ?: ""
            if (dl.isNotEmpty()) httpGet(dl, apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "download ping failed: ${e.message}")
        }
        Log.i(TAG, "Wallpaper changed")
    }

    /**
     * Mirrors the app: only enabled targets (already filtered by Rust), restricted to the
     * time group of the current local hour (start inclusive, end exclusive, wraps midnight),
     * then a weighted random pick.
     */
    private fun chooseEndpoint(plan: JSONObject): String {
        val fallback = plan.optString("fallback", FALLBACK).ifBlank { FALLBACK }
        val targets = plan.optJSONArray("targets") ?: JSONArray()
        if (targets.length() == 0) return fallback
        val all = (0 until targets.length()).map { targets.getJSONObject(it) }

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        var allowed: Set<String>? = null
        val groups = plan.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val s = g.optInt("start_hour")
            val e = g.optInt("end_hour")
            val inside = when {
                s < e -> hour >= s && hour < e
                s > e -> hour >= s || hour < e
                else -> false
            }
            if (inside) {
                val ids = g.optJSONArray("target_ids") ?: JSONArray()
                if (ids.length() > 0) allowed = (0 until ids.length()).map { ids.getString(it) }.toSet()
                break
            }
        }
        val allowedIds = allowed
        // No matching group / empty group / group with no enabled targets -> all enabled targets.
        val candidates = all.filter { allowedIds == null || allowedIds.contains(it.optString("id")) }
            .ifEmpty { all }

        val weights = candidates.map { maxOf(0, it.optInt("weight", 1)) }
        val total = weights.sum()
        if (total <= 0) return candidates.random().getString("endpoint")
        var r = Random.nextInt(total)
        for ((i, c) in candidates.withIndex()) {
            r -= weights[i]
            if (r < 0) return c.getString("endpoint")
        }
        return candidates.last().getString("endpoint")
    }

    private fun readApiKey(path: String): String? {
        val f = File(path)
        if (!f.isFile) return null
        val key = JSONObject(f.readText()).optString("api_key", "")
        return key.ifBlank { null }
    }

    /** Handles: single photo object, array of photos, or {"results":[...]} (search). */
    private fun pickPhoto(body: String): JSONObject? {
        val t = body.trim()
        if (t.startsWith("[")) return pickFrom(JSONArray(t))
        val o = JSONObject(t)
        return when {
            o.has("results") -> pickFrom(o.getJSONArray("results"))
            o.has("urls") -> o
            else -> null
        }
    }

    private fun pickFrom(arr: JSONArray): JSONObject? =
        if (arr.length() == 0) null else arr.getJSONObject(Random.nextInt(arr.length()))

    private fun httpGet(url: String, apiKey: String?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (apiKey != null) {
                conn.setRequestProperty("Authorization", "Client-ID $apiKey")
                conn.setRequestProperty("Accept-Version", "v1")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code while downloading image")
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        } finally {
            conn.disconnect()
        }
    }
}
