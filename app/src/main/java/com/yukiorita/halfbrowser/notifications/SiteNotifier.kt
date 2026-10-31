package com.yukiorita.halfbrowser.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yukiorita.halfbrowser.MainActivity
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.DeepLinks

/** Local notifications for background page-change detection. */
object SiteNotifier {

    const val CHANNEL_ID = "site_watch"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)
    }

    fun notify(
        context: Context,
        bookmark: Bookmark,
        pageTitle: String,
        snippet: String,
    ) {
        if (!canPost(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val bookmarkTitle = bookmark.title.ifBlank { bookmark.url }
        val text = context.getString(
            R.string.notification_site_updated,
            pageTitle.ifBlank { bookmark.url },
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(DeepLinks.bookmarkUri(bookmark.id))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            bookmark.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_halfbrowser)
            .setContentTitle(bookmarkTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$snippet"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup("site_watch")
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(bookmark.id.hashCode(), notification)
        }.onFailure { _: Throwable -> Unit }
    }
}
