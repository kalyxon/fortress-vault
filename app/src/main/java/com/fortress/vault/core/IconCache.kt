package com.fortress.vault.core

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

object IconCache {
    private val cache = LruCache<String, ImageBitmap>(350)
    private val failedPackages = Collections.synchronizedSet(HashSet<String>())

    fun getCached(packageName: String): ImageBitmap? {
        return cache.get(packageName)
    }

    fun getIcon(context: Context, packageName: String, sizePx: Int = 96): ImageBitmap? {
        if (failedPackages.contains(packageName)) return null
        val cached = cache.get(packageName)
        if (cached != null) return cached

        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap.asImageBitmap()
            } else {
                drawable.toBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.RGB_565).asImageBitmap()
            }
            cache.put(packageName, bitmap)
            bitmap
        }.onFailure {
            failedPackages.add(packageName)
        }.getOrNull()
    }

    fun preloadIcons(context: Context, packageNames: List<String>, sizePx: Int = 96) {
        packageNames.forEach { pkg ->
            if (!failedPackages.contains(pkg) && cache.get(pkg) == null) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(pkg)
                    val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
                        drawable.bitmap.asImageBitmap()
                    } else {
                        drawable.toBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.RGB_565).asImageBitmap()
                    }
                    cache.put(pkg, bitmap)
                }.onFailure {
                    failedPackages.add(pkg)
                }
            }
        }
    }

    fun clearCache() {
        cache.evictAll()
        failedPackages.clear()
    }

    suspend fun getIconAsync(context: Context, packageName: String, sizePx: Int = 96): ImageBitmap? =
        withContext(Dispatchers.IO) {
            getIcon(context, packageName, sizePx)
        }
}
