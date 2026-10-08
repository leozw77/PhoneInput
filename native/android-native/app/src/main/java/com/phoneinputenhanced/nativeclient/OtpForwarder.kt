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
        source: String,
        traceId: String,
        onComplete: () -> Unit = {},
    ) {
        val sourceKey = stableId(sourceId)
        val trace = traceId.take(12)
        val now = System.currentTimeMillis()
        val codeKey = "${sender.trim().lowercase()}:$code"
        synchronized(seenSources) {
            if (seenSources.containsKey(sourceKey)) {
                OtpDiagnosticLog.record(context, event = "source=$source event=forward_decision trace=$trace result=skip reason=duplicate_source")
                onComplete()
                return
            }
            seenSources[sourceKey] = Unit
            while (seenSources.size > 256) seenSources.remove(seenSources.keys.first())

            seenCodes.entries.removeIf { now - it.value > DEDUPE_WINDOW_MS }
            val previous = seenCodes.put(codeKey, now)
            while (seenCodes.size > 256) seenCodes.remove(seenCodes.keys.first())
            if (previous != null && now - previous <= DEDUPE_WINDOW_MS) {
                OtpDiagnosticLog.record(context, event = "source=$source event=forward_decision trace=$trace result=skip reason=duplicate_code")
                onComplete()
                return
            }
        }

        io.execute {
            try {
                forwardOnce(context, code, sender, receivedAt, traceId, source, trace)
            } finally {
                onComplete()
            }
        }
    }

    private fun forwardOnce(context: Context, code: String, sender: String, receivedAt: Long, eventId: String, source: String, trace: String) {
        val host = context.getSharedPreferences("phoneinput_native", Context.MODE_PRIVATE)
            .getString("host", "")?.trim()?.substringBefore(":")
            ?.takeIf { it.matches(Regex("[0-9.]+")) }
        if (host.isNullOrBlank()) {
            OtpDiagnosticLog.record(context, Log.WARN, "source=$source event=forward_decision trace=$trace result=skip reason=no_saved_host")
            return
        }
        OtpDiagnosticLog.record(context, event = "source=$source event=forward_decision trace=$trace result=send hostConfigured=true")
        val body = JSONObject().put("code", code).put("sender", sender.take(120))
            .put("receivedAt", receivedAt).put("eventId", eventId).put("source", source).toString()
        for (attempt in 0..2) {
            val startedAt = System.nanoTime()
            var connection: HttpURLConnection? = null
            try {
                val activeConnection = LocalLanNetwork.openConnection(
                    context, host, URL("http://$host:51877/api/otp"),
                )
                connection = activeConnection
                activeConnection.requestMethod = "POST"
                activeConnection.connectTimeout = 2500
                activeConnection.readTimeout = 2500
                activeConnection.doOutput = true
                activeConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                activeConnection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = activeConnection.responseCode
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                OtpDiagnosticLog.record(context, if (status in 200..299) Log.INFO else Log.WARN,
                    "source=$source event=forward_attempt trace=$trace attempt=${attempt + 1} durationMs=$durationMs httpStatus=$status")
                if (status in 200..299) {
                    OtpDiagnosticLog.record(context, event = "source=$source event=forward_result trace=$trace result=success attempts=${attempt + 1}")
                    return
                }
                if (attempt == 2) OtpDiagnosticLog.record(context, Log.ERROR, "source=$source event=forward_result trace=$trace result=failed reason=http_status")
            } catch (error: Exception) {
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                OtpDiagnosticLog.record(context, Log.WARN,
                    "source=$source event=forward_attempt trace=$trace attempt=${attempt + 1} durationMs=$durationMs exception=${error.javaClass.simpleName}")
                if (attempt == 2) OtpDiagnosticLog.record(context, Log.ERROR,
                    "source=$source event=forward_result trace=$trace result=failed reason=${error.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
            if (attempt < 2) Thread.sleep(300L * (attempt + 1))
        }
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun newTraceId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    private const val DEDUPE_WINDOW_MS = 5 * 60 * 1000L
}
