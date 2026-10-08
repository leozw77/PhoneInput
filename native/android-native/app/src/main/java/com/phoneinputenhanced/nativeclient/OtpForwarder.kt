package com.phoneinputenhanced.nativeclient

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/** Sends only OTP metadata to the configured LAN host; message text is never retained or logged. */
internal object OtpForwarder {
    private val io = Executors.newSingleThreadExecutor()
    private val seenSources = LinkedHashMap<String, Unit>()
    private val seenCodes = LinkedHashMap<String, Long>()

    private val contextual = Regex(
        "(?:验证码|校验码|动态码|verification\\s*(?:code)?|one[- ]time\\s*(?:password|code)|\\botp\\b|\\bcode\\b)[^0-9]{0,20}([0-9]{4,8})(?![0-9])",
        RegexOption.IGNORE_CASE
    )
    private val anyCode = Regex("(?<![0-9])([0-9]{4,8})(?![0-9])")

    fun extractCode(text: String): String? = contextual.find(text)?.groupValues?.getOrNull(1)
        ?: anyCode.find(text)?.groupValues?.getOrNull(1)

    fun forward(
        context: Context,
        code: String,
        sender: String,
        receivedAt: Long,
        sourceId: String,
        onComplete: () -> Unit = {},
    ) {
        val sourceKey = stableId(sourceId)
        val now = System.currentTimeMillis()
        val codeKey = "${sender.trim().lowercase()}:$code"
        synchronized(seenSources) {
            if (seenSources.containsKey(sourceKey)) {
                onComplete()
                return
            }
            seenSources[sourceKey] = Unit
            while (seenSources.size > 256) seenSources.remove(seenSources.keys.first())

            seenCodes.entries.removeIf { now - it.value > DEDUPE_WINDOW_MS }
            val previous = seenCodes.put(codeKey, now)
            while (seenCodes.size > 256) seenCodes.remove(seenCodes.keys.first())
            if (previous != null && now - previous <= DEDUPE_WINDOW_MS) {
                Log.i(TAG, "otp_duplicate_suppressed")
                onComplete()
                return
            }
        }

        io.execute {
            try {
                forwardOnce(context, code, sender, receivedAt, sourceKey)
            } finally {
                onComplete()
            }
        }
    }

    private fun forwardOnce(context: Context, code: String, sender: String, receivedAt: Long, eventId: String) {
        val host = context.getSharedPreferences("phoneinput_native", Context.MODE_PRIVATE)
            .getString("host", "")?.trim()?.substringBefore(":")
            ?.takeIf { it.matches(Regex("[0-9.]+")) }
        if (host.isNullOrBlank()) {
            Log.w(TAG, "otp_forward_skipped; reason=no_saved_host")
            return
        }
        val body = JSONObject().put("code", code).put("sender", sender.take(120))
            .put("receivedAt", receivedAt).put("eventId", eventId).toString()
        for (attempt in 0..2) {
            val connection = URL("http://$host:51877/api/otp").openConnection() as HttpURLConnection
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

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val DEDUPE_WINDOW_MS = 5 * 60 * 1000L
    private const val TAG = "PhoneInputOTP"
}
