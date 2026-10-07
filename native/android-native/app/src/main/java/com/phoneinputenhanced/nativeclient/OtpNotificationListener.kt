package com.phoneinputenhanced.nativeclient

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Reads OTPs surfaced by the default SMS app and forwards only the code metadata over the LAN. */
class OtpNotificationListener : NotificationListenerService() {
    private val io = Executors.newSingleThreadExecutor()
    private val seen = LinkedHashSet<String>()

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
        val code = OtpParser.extract(text) ?: return
        val eventId = "${sbn.key}:${sbn.postTime}:$code"
        synchronized(seen) {
            if (!seen.add(eventId)) return
            while (seen.size > 128) seen.remove(seen.first())
        }

        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifBlank { sbn.packageName }
        val receivedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        Log.i(TAG, "otp_notification_detected; codeFound=true")
        io.execute { forward(code, sender, receivedAt, eventId) }
    }

    private fun forward(code: String, sender: String, receivedAt: Long, eventId: String) {
        val host = getSharedPreferences("phoneinput_native", MODE_PRIVATE).getString("host", "")
            ?.trim()?.substringBefore(":")?.takeIf { it.matches(Regex("[0-9.]+")) }
        if (host.isNullOrBlank()) {
            Log.w(TAG, "otp_forward_skipped; reason=no_saved_host")
            return
        }
        val body = JSONObject().put("code", code).put("sender", sender.take(120))
            .put("receivedAt", receivedAt).put("eventId", eventId.take(256)).toString()
        for (attempt in 0..2) {
            val connection = (URL("http://$host:51877/api/otp").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status in 200..299) {
                    Log.i(TAG, "otp_forwarded; status=$status")
                    return
                }
                if (attempt == 2) Log.w(TAG, "otp_forward_failed; status=$status")
            } catch (error: Exception) {
                if (attempt == 2) Log.w(TAG, "otp_forward_failed; reason=${error.javaClass.simpleName}")
            } finally {
                connection.disconnect()
            }
            if (attempt < 2) Thread.sleep(300L * (attempt + 1))
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private object OtpParser {
        private val contextual = Regex(
            "(?:验证码|校验码|动态码|verification\\s*(?:code)?|one[- ]time\\s*(?:password|code)|\\botp\\b|\\bcode\\b)[^0-9]{0,20}([0-9]{4,8})(?![0-9])",
            RegexOption.IGNORE_CASE
        )
        private val anyCode = Regex("(?<![0-9])([0-9]{4,8})(?![0-9])")
        fun extract(text: String): String? = contextual.find(text)?.groupValues?.getOrNull(1)
            ?: anyCode.find(text)?.groupValues?.getOrNull(1)
    }

    companion object { private const val TAG = "PhoneInputOTP" }
}
