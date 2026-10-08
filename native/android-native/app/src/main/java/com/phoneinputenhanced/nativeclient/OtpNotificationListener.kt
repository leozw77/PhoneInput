package com.phoneinputenhanced.nativeclient

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Telephony
import android.util.Log

/** Reads OTPs surfaced by the default SMS app and forwards only the code metadata over the LAN. */
class OtpNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification_listener_connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "notification_listener_disconnected")
        runCatching { requestRebind(ComponentName(this, OtpNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        if (defaultSms.isNullOrBlank() || sbn.packageName != defaultSms) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val text = buildList {
            listOf(
                Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT
            ).forEach { key -> extras.getCharSequence(key)?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(::add) }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }?.let(::addAll)
            notification.tickerText?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }.distinct().joinToString("\n").take(4000)
        val code = OtpForwarder.extractCode(text) ?: return

        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifBlank { sbn.packageName }
        val receivedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        Log.i(TAG, "otp_notification_detected; codeFound=true")
        // A notification's postTime can change when the messaging app updates the same alert.
        val sourceId = "notification:${sbn.key}:$code"
        OtpForwarder.forward(this, code, sender, receivedAt, sourceId)
    }

    companion object { private const val TAG = "PhoneInputOTP" }
}
