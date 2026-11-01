package com.yukiorita.halfbrowser.data

import android.content.Context
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.HistoryEntry
import com.yukiorita.halfbrowser.core.HistoryList
import com.yukiorita.halfbrowser.core.HistoryRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browsing history, stored as one JSON blob like the bookmark list.
 *
 * Writes happen on the WebView callback thread; the list is small (500 rows) and
 * every mutation is synchronous under one lock, so a crash cannot leave a
 * half-written blob.
 */
class HistoryRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    fun snapshot(): List<HistoryEntry> = _items.value

    fun record(url: String, title: String) {
        val now = System.currentTimeMillis()
        mutate { current -> HistoryRules.record(current, url, title, now) }
    }

    fun delete(url: String) {
        val target = com.yukiorita.halfbrowser.core.UrlNormalizer.canonicalForCompare(url) ?: return
        mutate { current ->
            current.filterNot {
                com.yukiorita.halfbrowser.core.UrlNormalizer.canonicalForCompare(it.url) == target
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_ITEMS).apply()
            _items.value = emptyList()
        }
    }

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val decoded = runCatching {
            Codecs.json.decodeFromString(HistoryList.serializer(), raw)
        }.getOrNull() ?: return emptyList()
        return HistoryRules.sanitize(decoded.items)
    }

    private fun mutate(transform: (List<HistoryEntry>) -> List<HistoryEntry>) {
        synchronized(lock) {
            val updated = transform(_items.value)
            if (updated == _items.value) return
            val encoded = runCatching {
                Codecs.json.encodeToString(HistoryList.serializer(), HistoryList(items = updated))
            }.getOrNull() ?: return
            prefs.edit().putString(KEY_ITEMS, encoded).apply()
            _items.value = updated
        }
    }

    private companion object {
        const val PREFS_NAME = "halfbrowser_history"
        const val KEY_ITEMS = "history_json"
    }
}
