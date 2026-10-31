package com.yukiorita.halfbrowser.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yukiorita.halfbrowser.MainActivity
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.BookmarkIcons
import com.yukiorita.halfbrowser.core.DeepLinks
import kotlin.math.roundToInt

/**
 * Home-screen shortcuts for bookmarks.
 *
 * Dynamic shortcuts mirror the bookmark list (so a long-press on the app icon
 * shows recent bookmarks); [requestPin] asks the launcher to pin one specific
 * bookmark. Pinning always requires a user confirmation in the launcher.
 */
object BookmarkShortcuts {

    enum class PinResult { REQUESTED, UNSUPPORTED, FAILED }

    private const val MAX_DYNAMIC = 5

    fun syncDynamic(context: Context, bookmarks: List<Bookmark>) {
        runCatching {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
                ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty()
            ) {
                // Still attempt dynamic registration: some launchers support dynamic
                // shortcuts without supporting pinning.
            }
            val limit = runCatching {
                ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            }.getOrDefault(MAX_DYNAMIC).coerceAtLeast(0)
            val sorted = bookmarks.sortedByDescending { it.createdAt }.take(minOf(limit, MAX_DYNAMIC))
            val items = sorted.map { buildShortcut(context, it) }
            ShortcutManagerCompat.setDynamicShortcuts(context, items)

            // Pinned shortcuts do not follow bookmark edits automatically. Refresh
            // the label/icon of any pinned shortcut whose bookmark still exists.
            val pinnedIds = runCatching {
                ShortcutManagerCompat.getShortcuts(
                    context,
                    ShortcutManagerCompat.FLAG_MATCH_PINNED,
                ).map { it.id }.toSet()
            }.getOrDefault(emptySet())
            if (pinnedIds.isNotEmpty()) {
                val updates = bookmarks
                    .filter { BookmarkIcons.shortcutId(it.id) in pinnedIds }
                    .map { buildShortcut(context, it) }
                if (updates.isNotEmpty()) {
                    runCatching { ShortcutManagerCompat.updateShortcuts(context, updates) }
                }
            }
        }
    }

    fun requestPin(context: Context, bookmark: Bookmark): PinResult {
        return runCatching {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                return PinResult.UNSUPPORTED
            }
            // Registering the dynamic shortcut first lets the launcher upgrade it
            // to a pinned one instead of creating a duplicate.
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, buildShortcut(context, bookmark)) }
            val requested = ShortcutManagerCompat.requestPinShortcut(context, buildShortcut(context, bookmark), null)
            if (requested) PinResult.REQUESTED else PinResult.FAILED
        }.getOrDefault(PinResult.FAILED)
    }

    fun removeDynamic(context: Context, bookmarkId: String) {
        runCatching {
            val remaining = ShortcutManagerCompat.getDynamicShortcuts(context)
                .filterNot { it.id == BookmarkIcons.shortcutId(bookmarkId) }
            ShortcutManagerCompat.setDynamicShortcuts(context, remaining)
        }
    }

    private fun buildShortcut(context: Context, bookmark: Bookmark): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(DeepLinks.bookmarkUri(bookmark.id))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ShortcutInfoCompat.Builder(context, BookmarkIcons.shortcutId(bookmark.id))
            .setShortLabel(BookmarkIcons.shortcutLabel(bookmark.title, bookmark.url))
            .setLongLabel(BookmarkIcons.shortcutLabel(bookmark.title, bookmark.url))
            .setIcon(IconCompat.createWithBitmap(monogramBitmap(context, bookmark)))
            .setIntent(intent)
            .build()
    }

    /**
     * Draws the same monogram the home grid uses, so a shortcut is recognisable
     * at a glance. No network access and no favicon dependency.
     */
    fun monogramBitmap(context: Context, bookmark: Bookmark): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (108f * density).roundToInt().coerceIn(108, 432)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val color = BookmarkIcons.colorFor(bookmark.url, bookmark.colorSeed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toInt()
        }
        val radius = size * 0.24f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)

        val glyph = BookmarkIcons.glyphFor(bookmark.title, bookmark.url, bookmark.glyph)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.46f
        }
        val metrics = textPaint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(glyph, size / 2f, baseline, textPaint)
        return bitmap
    }
}
