package com.phoneinputenhanced.nativeclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class OtpSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val action = intent.action.orEmpty()
        OtpDiagnosticLog.record(appContext, event = "source=sms_broadcast event=sms_receiver_enter action=${action.substringAfterLast('.')} appState=${OtpDiagnosticLog.foregroundState()}")
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            OtpDiagnosticLog.record(appContext, Log.WARN, "source=sms_broadcast event=sms_receiver_ignored reason=unexpected_action")
            return
        }

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .onFailure { OtpDiagnosticLog.record(appContext, Log.ERROR, "source=sms_broadcast event=sms_parse_failed stage=decode reason=${it.javaClass.simpleName}") }
            .getOrNull()
            .orEmpty()
        OtpDiagnosticLog.record(appContext, event = "source=sms_broadcast event=sms_parse_result parts=${messages.size}")
        if (messages.isEmpty()) {
            OtpDiagnosticLog.record(appContext, Log.WARN, "source=sms_broadcast event=sms_parse_no_message")
            return
        }

        val rawParts = JSONArray()
        messages.forEachIndexed { index, message ->
            rawParts.put(JSONObject()
                .put("part", index)
                .put("sender", message.displayOriginatingAddress.orEmpty())
                .put("timestampMillis", message.timestampMillis)
                .put("body", message.messageBody.orEmpty()))
        }
        OtpDiagnosticLog.recordPrivatePlaintext(
            appContext,
            "source=sms_broadcast event=raw_sms_parts appState=${OtpDiagnosticLog.foregroundState()}",
            JSONObject().put("parts", rawParts),
        )

        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val code = OtpForwarder.extractCode(body)
        OtpDiagnosticLog.record(appContext, event = "source=sms_broadcast event=sms_parse_result codeFound=${code != null}")
        OtpDiagnosticLog.recordPrivatePlaintext(
            appContext,
            "source=sms_broadcast event=selected_candidate",
            JSONObject().put("code", code ?: JSONObject.NULL),
        )
        if (code == null) return
        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty().ifBlank { "未知发件人" }
        val receivedAt = messages.firstOrNull()?.timestampMillis?.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val sourceId = "sms:$sender:$receivedAt:$code"
        val traceId = OtpForwarder.newTraceId()
        OtpDiagnosticLog.record(appContext, event = "source=sms_broadcast event=sms_code_detected trace=${traceId.take(12)}")

        val pending = goAsync()
        OtpForwarder.forward(appContext, code, sender, receivedAt, sourceId, "sms_broadcast", traceId) {
            pending.finish()
        }
    }
}
