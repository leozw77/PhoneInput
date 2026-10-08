package com.phoneinputenhanced.nativeclient

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Reads OTPs surfaced by the default SMS app and forwards only the code metadata over the LAN. */
class OtpNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        OtpDiagnosticLog.record(this, event = "source=notification event=listener_connected appState=${OtpDiagnosticLog.foregroundState()}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        OtpDiagnosticLog.record(this, Log.WARN, "source=notification event=listener_disconnected appState=${OtpDiagnosticLog.foregroundState()}")
        runCatching { requestRebind(ComponentName(this, OtpNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) {
            OtpDiagnosticLog.record(this, Log.WARN, "source=notification event=callback_ignored reason=null_notification")
            return
        }
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val isDefaultSms = !defaultSms.isNullOrBlank() && sbn.packageName == defaultSms
        OtpDiagnosticLog.record(
            this,
            if (isDefaultSms) Log.INFO else Log.DEBUG,
            "source=notification event=callback package=${sbn.packageName} isDefaultSms=$isDefaultSms postTime=${sbn.postTime} appState=${OtpDiagnosticLog.foregroundState()}",
        )
        if (sbn.packageName == packageName || !isDefaultSms) return

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
        val rawLines = JSONArray()
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.forEach { rawLines.put(it?.toString().orEmpty()) }
        val rawNotification = JSONObject()
            .put("package", sbn.packageName)
            .put("postTime", sbn.postTime)
            .put("notificationWhen", notification.`when`)
            .put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            .put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            .put("bigText", extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
            .put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
            .put("summaryText", extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty())
            .put("infoText", extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty())
            .put("textLines", rawLines)
            .put("ticker", notification.tickerText?.toString().orEmpty())
            .put("parserInput", text)
            .put("extraKeys", JSONArray(extras.keySet().sorted()))
        OtpDiagnosticLog.recordPrivatePlaintext(this, "source=notification event=notification_text", rawNotification)
        val code = OtpForwarder.extractCode(text)
        OtpDiagnosticLog.record(this, event = "source=notification event=notification_parse_result codeFound=${code != null}")
        OtpDiagnosticLog.recordPrivatePlaintext(
            this,
            "source=notification event=selected_candidate postTime=${sbn.postTime}",
            JSONObject().put("code", code ?: JSONObject.NULL),
        )
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
}
