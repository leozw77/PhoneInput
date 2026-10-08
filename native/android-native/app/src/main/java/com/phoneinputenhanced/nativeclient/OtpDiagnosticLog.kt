package com.phoneinputenhanced.nativeclient

import android.app.ActivityManager
import android.content.Context
import android.util.Log
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
        runCatching {
            synchronized(lock) {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists() && file.length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                    val tail = file.readBytes().takeLast(KEEP_BYTES.toInt())
                    file.writeBytes(tail)
                }
                file.appendText(line, Charsets.UTF_8)
            }
        }.onFailure { Log.w(TAG, "diagnostic_file_write_failed; reason=${it.javaClass.simpleName}") }
    }
}
