package com.yukiorita.halfbrowser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.FaviconRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads, normalises and caches bookmark icons.
 *
 * Icons live as small PNGs in app-private storage; the bookmark row only stores a
 * file name. Downloads respect the app's certificate pins (see [SiteFetcher]) and
 * are skipped for http-only pages and when the user disabled icon fetching.
 */
class FaviconRepository(
    private val context: Context,
    private val bookmarks: BookmarkRepository,
    private val fetcher: SiteFetcher,
    private val settings: SettingsRepository,
) {

    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    suspend fun refresh(bookmarkId: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!settings.current().fetchBookmarkIcons) return@withContext false
        val bookmark = bookmarks.findById(bookmarkId) ?: return@withContext false
        if (!force && bookmark.icon.isNotBlank() && loadBitmap(bookmark.icon) != null) {
            return@withContext false
        }
        val bytes = runCatching { fetcher.fetchIcon(bookmark.url) }.getOrNull()
            ?: return@withContext false
        val bitmap = decodeIcon(bytes) ?: return@withContext false
        val name = FaviconRules.iconFileName(bookmark.id)
        val written = write(name, bitmap)
        if (!written) return@withContext false
        bookmarks.update(bookmark.copy(icon = name))
        cache.put(name, bitmap)
        true
    }

    fun load(bookmark: Bookmark): Bitmap? {
        if (bookmark.icon.isBlank()) return null
        cache.get(bookmark.icon)?.let { return it }
        val bitmap = loadBitmap(bookmark.icon) ?: return null
        cache.put(bookmark.icon, bitmap)
        return bitmap
    }

    fun deleteFor(bookmark: Bookmark) {
        if (bookmark.icon.isBlank()) return
        cache.remove(bookmark.icon)
        runCatching { fileFor(bookmark.icon).delete() }
    }

    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        decode(bytes)?.let { return scale(it) }
        val png = FaviconRules.extractEmbeddedPng(bytes) ?: return null
        return decode(png)?.let { scale(it) }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= FaviconRules.TARGET_SIZE &&
            bounds.outHeight / (sample * 2) >= FaviconRules.TARGET_SIZE
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    private fun scale(source: Bitmap): Bitmap {
        if (source.width == FaviconRules.TARGET_SIZE && source.height == FaviconRules.TARGET_SIZE) {
            return source
        }
        val scaled = Bitmap.createScaledBitmap(
            source,
            FaviconRules.TARGET_SIZE,
            FaviconRules.TARGET_SIZE,
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun write(name: String, bitmap: Bitmap): Boolean = runCatching {
        val dir = directory()
        if (!dir.exists() && !dir.mkdirs()) return false
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) return false
            stream.flush()
        }
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }.getOrDefault(false)

    private fun loadBitmap(name: String): Bitmap? = runCatching {
        val file = fileFor(name)
        if (!file.isFile || file.length() <= 0L) return null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun directory(): File = File(context.filesDir, FaviconRules.DIRECTORY)

    private fun fileFor(name: String): File = File(directory(), name)
}
