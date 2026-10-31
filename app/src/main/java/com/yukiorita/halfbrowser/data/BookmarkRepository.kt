package com.yukiorita.halfbrowser.data

import android.content.Context
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.UrlNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Bookmark storage. A single JSON blob keeps writes atomic; all mutations are
 * serialised on one lock, and StateFlow gives Compose a consistent snapshot.
 */
class BookmarkRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _items = MutableStateFlow(Codecs.decodeBookmarks(prefs.getString(KEY_ITEMS, null)))
    val items: StateFlow<List<Bookmark>> = _items.asStateFlow()

    fun snapshot(): List<Bookmark> = _items.value

    fun findByUrl(url: String): Bookmark? {
        val target = UrlNormalizer.canonicalForCompare(url) ?: return null
        return snapshot().firstOrNull {
            UrlNormalizer.canonicalForCompare(it.url) == target
        }
    }

    fun findById(id: String?): Bookmark? =
        if (id.isNullOrBlank()) null else snapshot().firstOrNull { it.id == id }

    fun add(title: String, url: String, glyph: String = ""): Bookmark {
        val normalizedUrl = UrlNormalizer.validateWebUrl(url)
            ?: throw IllegalArgumentException("Not a web URL")
        val now = System.currentTimeMillis()
        val item = Bookmark(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { UrlNormalizer.displayHost(normalizedUrl).orEmpty() },
            url = normalizedUrl,
            glyph = glyph.trim().take(2),
            createdAt = now,
            updatedAt = now,
        )
        mutate { it + item }
        return item
    }

    fun update(item: Bookmark): Boolean {
        val normalizedUrl = UrlNormalizer.validateWebUrl(item.url) ?: return false
        val cleaned = item.copy(
            url = normalizedUrl,
            title = item.title.trim().ifBlank { UrlNormalizer.displayHost(normalizedUrl).orEmpty() },
            glyph = item.glyph.trim().take(2),
            updatedAt = System.currentTimeMillis(),
        )
        var changed = false
        mutate { current ->
            if (current.none { it.id == cleaned.id }) return@mutate current
            changed = true
            current.map { if (it.id == cleaned.id) cleaned else it }
        }
        return changed
    }

    fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    fun move(fromIndex: Int, toIndex: Int) {
        mutate { current ->
            if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) {
                current
            } else {
                current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            }
        }
    }

    private fun mutate(transform: (List<Bookmark>) -> List<Bookmark>) {
        synchronized(lock) {
            val updated = transform(_items.value)
            if (updated == _items.value) return
            prefs.edit().putString(KEY_ITEMS, Codecs.encodeBookmarks(updated)).apply()
            _items.value = updated
        }
    }

    companion object {
        private const val PREFS_NAME = "halfbrowser_bookmarks"
        private const val KEY_ITEMS = "items_json"
    }
}
