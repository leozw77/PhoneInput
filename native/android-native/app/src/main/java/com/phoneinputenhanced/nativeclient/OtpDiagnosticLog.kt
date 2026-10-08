package com.phoneinputenhanced.nativeclient

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Privacy-safe OTP flow diagnostics mirrored to logcat and a bounded app-private file. */
internal object OtpDiagnosticLog {
    private const val TAG = "PhoneInputOTP"
    private const val FILE_NAME = "otp-diagnostics.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val KEEP_BYTES = 128 * 1024L
    private val lock = Any()

    fun foregroundState(): String = runCatching {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        if (state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "foreground" else "background"
    }.getOrDefault("unknown")

    fun record(context: Context, level: Int = Log.INFO, event: String) {
        val line = "${System.currentTimeMillis()} $event\n"
        when (level) {
            Log.ERROR -> Log.e(TAG, event)
            Log.WARN -> Log.w(TAG, event)
            else -> Log.i(TAG, event)
        }
        append(context, line)
    }

    /** Writes user-authorized message plaintext only to the app-private file, never to logcat or the PC host. */
    fun recordPrivatePlaintext(context: Context, event: String, data: JSONObject) {
        val payload = data.toString().take(MAX_PRIVATE_PAYLOAD_CHARS)
        append(context, "${System.currentTimeMillis()} PRIVATE_PLAINTEXT $event payload=$payload\n")
    }

    private fun append(context: Context, line: String) {
        runCatching {
            synchronized(lock) {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists() && file.length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                    val tail = file.readBytes().takeLast(KEEP_BYTES.toInt()).toByteArray()
                    file.writeBytes(tail)
                }
                file.appendText(line, Charsets.UTF_8)
            }
        }.onFailure { Log.w(TAG, "diagnostic_file_write_failed; reason=${it.javaClass.simpleName}") }
    }

    private const val MAX_PRIVATE_PAYLOAD_CHARS = 12_000
}
