package com.phoneinputenhanced.nativeclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class OtpSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .onFailure { Log.w(TAG, "sms_broadcast_decode_failed") }
            .getOrNull()
            .orEmpty()
        if (messages.isEmpty()) return

        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val code = OtpForwarder.extractCode(body) ?: return
        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty().ifBlank { "未知发件人" }
        val receivedAt = messages.firstOrNull()?.timestampMillis?.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val sourceId = "sms:$sender:$receivedAt:$code"

        val pending = goAsync()
        OtpForwarder.forward(context.applicationContext, code, sender, receivedAt, sourceId) {
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "PhoneInputOTP"
    }
}
