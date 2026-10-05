package com.freezr.app.platform.apps

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downscaled icon bitmaps keyed by package. Loaded off the main thread and bounded by bytes,
 * so scrolling a list of 300+ apps never decodes on the UI thread or exhausts memory.
 */
@Singleton
class AppIconCache @Inject constructor(private val context: Context) {
    private val sizePx = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()?.also { cache.put(packageName, it) }
        }
    }

    fun evict(packageName: String) {
        cache.remove(packageName)
    }

    private companion object {
        const val MAX_BYTES = 12 * 1024 * 1024
    }
}
