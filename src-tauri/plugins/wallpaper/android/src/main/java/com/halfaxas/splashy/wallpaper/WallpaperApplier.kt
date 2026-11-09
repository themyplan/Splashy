package com.halfaxas.splashy.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File

/** Shared by WallpaperPlugin (manual set) and WallpaperWorker (auto rotation). */
object WallpaperApplier {
    // Max decoded pixels (~48 MB as ARGB_8888) to avoid OOM on big photos.
    private const val MAX_PIXELS = 12_000_000L

    fun targetSize(context: Context): Pair<Int, Int> {
        val wm = WallpaperManager.getInstance(context)
        val dm = context.resources.displayMetrics
        return Pair(
            maxOf(wm.desiredMinimumWidth, dm.widthPixels),
            maxOf(wm.desiredMinimumHeight, dm.heightPixels)
        )
    }

    fun decodeScaled(context: Context, file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val (targetW, targetH) = targetSize(context)
        val needed = maxOf(targetW.toFloat() / w, targetH.toFloat() / h)
        var sample = 1
        while (1f / (sample * 2) >= needed) sample *= 2
        while ((w.toLong() / sample) * (h.toLong() / sample) > MAX_PIXELS) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun apply(context: Context, bitmap: Bitmap) {
        val wm = WallpaperManager.getInstance(context)
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
