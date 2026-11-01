package com.yukiorita.halfbrowser.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yukiorita.halfbrowser.MainActivity
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.SitePermissionKind
import com.yukiorita.halfbrowser.core.SitePermissions
import com.yukiorita.halfbrowser.core.WebNotify
import com.yukiorita.halfbrowser.data.SitePermissionRepository
import org.json.JSONObject

/**
 * Carries web notifications from the page to Android.
 *
 * The page side is [WebNotify.JS], which installs a `Notification` object and
 * posts `hello` / `request` / `show` / `close` messages here. Only the main
 * frame is trusted, only http(s) origins are accepted, and a `show` is dropped
 * unless the user granted notifications for that exact origin — the page cannot
 * decide its own permission.
 *
 * Delivery is immediate while the page's process is alive (including while the
 * app is in the background when the keep-alive service is on). Unlike Chrome,
 * there is no Google Play Services push channel, so a page that is not running
 * cannot wake the app; that limitation is stated in the settings UI.
 */
class WebNotificationBridge(
    private val context: Context,
    private val permissions: SitePermissionRepository,
    private val currentUrl: () -> String,
    private val askUser: (origin: String, decide: (Boolean) -> Unit) -> Unit,
) : WebViewCompat.WebMessageListener {

    fun register(webView: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                WebNotify.BRIDGE_NAME,
                setOf("*"),
                this,
            )
            true
        }.getOrDefault(false)
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        // A cross-origin iframe must not be able to speak for the top-level site.
        if (!isMainFrame) return
        val origin = SitePermissions.originOf(sourceOrigin.toString()) ?: return
        val payload = message.data ?: return
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hello" -> replyPermission(origin, replyProxy)
            "request" -> handleRequest(origin, json, replyProxy)
            "show" -> {
                if (granted(origin)) show(origin, json)
            }
            "close" -> {
                val tag = json.optString("tag")
                runCatching {
                    NotificationManagerCompat.from(context).cancel(notificationId(origin, tag))
                }
            }
        }
    }

    private fun handleRequest(origin: String, json: JSONObject, replyProxy: JavaScriptReplyProxy) {
        val id = json.optString("id")
        val decision = permissions.decision(origin, SitePermissionKind.NOTIFICATIONS)
        if (decision != null) {
            reply(replyProxy, id, decision)
            return
        }
        askUser(origin) { allowed ->
            permissions.record(origin, SitePermissionKind.NOTIFICATIONS, allowed)
            reply(replyProxy, id, allowed)
        }
    }

    @SuppressLint("RequiresFeature")
    private fun replyPermission(origin: String, replyProxy: JavaScriptReplyProxy) {
        val decision = permissions.decision(origin, SitePermissionKind.NOTIFICATIONS)
        val value = when (decision) {
            true -> "granted"
            false -> "denied"
            null -> "default"
        }
        runCatching { replyProxy.postMessage(JSONObject().put("type", "permission").put("value", value).toString()) }
    }

    @SuppressLint("RequiresFeature")
    private fun reply(replyProxy: JavaScriptReplyProxy, id: String, allowed: Boolean) {
        val value = if (allowed) "granted" else "denied"
        runCatching {
            replyProxy.postMessage(
                JSONObject()
                    .put("type", "permission")
                    .put("id", id)
                    .put("value", value)
                    .toString(),
            )
        }
    }

    private fun granted(origin: String): Boolean =
        permissions.decision(origin, SitePermissionKind.NOTIFICATIONS) == true

    private fun notificationId(origin: String, tag: String): Int =
        (origin + "|" + tag).hashCode()

    @SuppressLint("MissingPermission")
    private fun show(origin: String, json: JSONObject) {
        if (!canPost()) return
        // Belt and braces: the runtime grant can be revoked between canPost()
        // and notify(), and lint cannot see through the helper.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // A notification posted to a channel that does not exist is dropped by
        // the system without an error, so make sure it exists first.
        ensureChannel(context)
        val title = json.optString("title").take(200).ifBlank { SitePermissions.hostOf(origin) }
        val body = json.optString("body").take(1000)
        val tag = json.optString("tag").take(100)
        val pageUrl = currentUrl().takeIf { SitePermissions.originOf(it) == origin }
            ?: (origin + "/")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(pageUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(origin, tag),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_halfbrowser)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup("web_notifications")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId(origin, tag), builder.build())
        }
    }

    private fun canPost(): Boolean {
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

    companion object {
        const val CHANNEL_ID = "web_notifications"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_web_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_web_channel_desc)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
