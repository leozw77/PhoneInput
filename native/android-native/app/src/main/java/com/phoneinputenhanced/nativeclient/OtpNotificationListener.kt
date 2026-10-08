package com.phoneinputenhanced.nativeclient

import android.app.Notification
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.LinkedHashMap

/** Reads OTPs surfaced by the default SMS app and forwards only the code metadata over the LAN. */
class OtpNotificationListener : NotificationListenerService() {
    private val scanHandler = Handler(Looper.getMainLooper())
    private var listenerConnected = false
    private val observedPostTimes = LinkedHashMap<String, Long>()

    private val scanActiveNotifications = object : Runnable {
        override fun run() {
            if (!listenerConnected) return
            runCatching {
                val defaultSms = Telephony.Sms.getDefaultSmsPackage(this@OtpNotificationListener)
                if (!defaultSms.isNullOrBlank() && OtpForwarder.hasSavedHost(this@OtpNotificationListener)) {
                    activeNotifications.orEmpty()
                        .filter { it.packageName == defaultSms }
                        .forEach { processNotification(it, fromActiveScan = true) }
                }
            }
                .onFailure { OtpDiagnosticLog.record(this@OtpNotificationListener, Log.WARN, "source=notification event=active_scan_failed reason=${it.javaClass.simpleName}") }
            scanHandler.postDelayed(this, ACTIVE_SCAN_INTERVAL_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        OtpDiagnosticLog.record(this, event = "source=notification event=listener_connected")
        scanHandler.removeCallbacks(scanActiveNotifications)
        scanHandler.post(scanActiveNotifications)
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        scanHandler.removeCallbacks(scanActiveNotifications)
        OtpDiagnosticLog.record(this, Log.WARN, "source=notification event=listener_disconnected")
        super.onListenerDisconnected()
        runCatching { requestRebind(ComponentName(this, OtpNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) {
            OtpDiagnosticLog.record(this, Log.WARN, "source=notification event=notification_posted_ignored reason=null_notification")
            return
        }
        processNotification(sbn, fromActiveScan = false)
    }

    private fun processNotification(sbn: StatusBarNotification, fromActiveScan: Boolean) {
        if (sbn.packageName == packageName) return
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        if (defaultSms.isNullOrBlank() || sbn.packageName != defaultSms) {
            if (!fromActiveScan) {
                OtpDiagnosticLog.record(
                    this,
                    event = "source=notification event=notification_ignored reason=not_default_sms package=${sbn.packageName} defaultSms=${defaultSms ?: "unknown"}",
                )
            }
            return
        }
        val hasSavedHost = OtpForwarder.hasSavedHost(this)
        if (fromActiveScan && !hasSavedHost) return
        if (hasSavedHost && !rememberPostTime(sbn)) return

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
        val code = OtpForwarder.extractCode(text)
        OtpDiagnosticLog.record(this, event = "source=notification event=notification_parse_result codeFound=${code != null}")
        if (code == null) return

        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifBlank { sbn.packageName }
        val receivedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        // A notification's postTime can change when the messaging app updates the same alert.
        val sourceId = "notification:${sbn.key}:$code"
        val traceId = OtpForwarder.newTraceId()
        OtpDiagnosticLog.record(this, event = "source=notification event=notification_detected trace=${traceId.take(12)}")
        OtpForwarder.forward(this, code, sender, receivedAt, sourceId, "notification", traceId)
    }

    private fun rememberPostTime(sbn: StatusBarNotification): Boolean = synchronized(observedPostTimes) {
        val previous = observedPostTimes.put(sbn.key, sbn.postTime)
        while (observedPostTimes.size > MAX_TRACKED_NOTIFICATIONS) {
            observedPostTimes.remove(observedPostTimes.keys.first())
        }
        previous != sbn.postTime
    }

    override fun onDestroy() {
        listenerConnected = false
        scanHandler.removeCallbacks(scanActiveNotifications)
        super.onDestroy()
    }

    companion object {
        private const val ACTIVE_SCAN_INTERVAL_MS = 5_000L
        private const val MAX_TRACKED_NOTIFICATIONS = 256
    }
}
