package com.phoneinputenhanced.nativeclient

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Low-frequency HTTP bridge to the existing Windows input core.
 *
 * Realtime writes use one serial executor so selection -> delete -> text/key ordering stays stable.
 * Status/readback uses a separate executor so a slow read cannot stall active typing.
 */
class NativeCoreApi {
    data class Result(val ok: Boolean, val message: String = "")

    data class StatusResult(
        val ok: Boolean,
        val targetId: String = "",
        val targetType: String = "other",
        val target: String = "",
        val message: String = "",
    )

    data class InputStateResult(
        val ok: Boolean,
        val supported: Boolean = false,
        val reason: String = "",
        val targetId: String = "",
        val targetType: String = "other",
        val text: String = "",
        val selectionStart: Int = 0,
        val selectionEnd: Int = 0,
        val controlId: String = "",
        val message: String = "",
    )

    data class ClipboardResult(
        val ok: Boolean,
        val hasText: Boolean = false,
        val text: String = "",
        val sequence: Long = 0L,
        val hash: String = "",
        val message: String = "",
    )

    data class ForegroundWindowResult(
        val ok: Boolean,
        val target: String = "other",
        val title: String = "",
        val process: String = "",
        val pid: Long = 0L,
        val message: String = "",
    )

    data class HostDiagnosticsResult(
        val ok: Boolean,
        val version: String = "",
        val protocol: Int = 0,
        val activeConnections: Int = 0,
        val uptimeSeconds: Long = 0L,
        val goroutines: Int = 0,
        val coreAvailable: Boolean = false,
        val coreDetail: String = "",
        val foregroundTarget: String = "other",
        val foregroundTitle: String = "",
        val pendingFiles: Int = 0,
        val uploadCount: Long = 0L,
        val downloadCount: Long = 0L,
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L,
        val lastTransfer: String = "",
        val lastTransferError: String = "",
        val message: String = "",
    )

    data class ScreenshotResult(
        val ok: Boolean,
        val png: ByteArray = ByteArray(0),
        val width: Int = 0,
        val height: Int = 0,
        val message: String = "",
    )

    private data class HttpResult(
        val ok: Boolean,
        val code: Int,
        val json: JSONObject?,
        val message: String,
    )

    private val inputWorker = Executors.newSingleThreadExecutor()
    // Input readback stays isolated from background clipboard/window polling.
    private val readWorker = Executors.newSingleThreadExecutor()
    private val auxWorker = Executors.newFixedThreadPool(2)
    private val screenshotWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Stable batch input path used by the preview.3 UI. */
    fun sendText(host: String, text: String, delayMs: Int = 6, callback: (Result) -> Unit) {
        if (text.isEmpty()) {
            callback(Result(false, "输入内容为空"))
            return
        }
        inputWorker.execute {
            val result = request(
                host = host,
                method = "POST",
                path = "/api/text",
                body = JSONObject()
                    .put("text", text)
                    .put("delayMs", delayMs.coerceIn(0, 15))
                    .put("enterAfter", false),
            )
            post(callback, Result(result.ok, result.message))
        }
    }

    /** Realtime text must carry targetId so Windows refuses stale-window injection. */
    fun sendRealtimeText(
        host: String,
        text: String,
        targetId: String,
        delayMs: Int = 6,
        callback: ((Result) -> Unit)? = null,
    ) {
        if (text.isEmpty()) return
        inputWorker.execute {
            val result = request(
                host = host,
                method = "POST",
                path = "/core-api/text",
                body = JSONObject()
                    .put("text", text)
                    .put("delayMs", delayMs.coerceIn(0, 15))
                    .put("targetId", targetId),
            )
            if (callback != null) post(callback, Result(result.ok, result.message))
        }
    }

    fun sendCoreKey(
        host: String,
        key: String,
        targetId: String = "",
        callback: ((Result) -> Unit)? = null,
    ) {
        inputWorker.execute {
            val query = if (targetId.isBlank()) "" else "?targetId=${encode(targetId)}"
            val result = request(host, "POST", "/core-api/key/${encodePath(key)}$query")
            if (callback != null) post(callback, Result(result.ok, result.message))
        }
    }

    /**
     * Mirrors one committed phone-IME revision into the currently focused Windows input.
     * Delete and insert are executed on the same serial worker so Baidu/Sogou voice tail
     * corrections cannot overtake each other on the LAN.
     */
    fun applyVoiceRelayEdit(
        host: String,
        deleteCodePoints: Int,
        insertText: String,
        callback: (Result) -> Unit,
    ) {
        val deletes = deleteCodePoints.coerceAtLeast(0)
        if (deletes == 0 && insertText.isEmpty()) {
            callback(Result(true))
            return
        }
        inputWorker.execute {
            var failure = ""
            for (i in 0 until deletes) {
                val r = request(host, "POST", "/core-api/key/backspace")
                if (!r.ok) {
                    failure = r.message.ifBlank { "Backspace failed" }
                    break
                }
            }
            if (failure.isBlank() && insertText.isNotEmpty()) {
                val r = request(
                    host = host,
                    method = "POST",
                    path = "/api/text",
                    body = JSONObject()
                        .put("text", insertText)
                        .put("delayMs", 4)
                        .put("enterAfter", false),
                )
                if (!r.ok) failure = r.message.ifBlank { "text injection failed" }
            }
            post(callback, Result(failure.isBlank(), failure))
        }
    }

    /**
     * Flushes the final voice-IME tail revision and then presses Windows Enter on the same
     * serialized input worker. This prevents a pending text update from arriving after Enter.
     */
    fun applyVoiceRelayEditAndEnter(
        host: String,
        deleteCodePoints: Int,
        insertText: String,
        callback: (Result) -> Unit,
    ) {
        val deletes = deleteCodePoints.coerceAtLeast(0)
        inputWorker.execute {
            var failure = ""
            for (i in 0 until deletes) {
                val r = request(host, "POST", "/core-api/key/backspace")
                if (!r.ok) {
                    failure = r.message.ifBlank { "Backspace failed" }
                    break
                }
            }
            if (failure.isBlank() && insertText.isNotEmpty()) {
                val r = request(
                    host = host,
                    method = "POST",
                    path = "/api/text",
                    body = JSONObject()
                        .put("text", insertText)
                        .put("delayMs", 4)
                        .put("enterAfter", false),
                )
                if (!r.ok) failure = r.message.ifBlank { "text injection failed" }
            }
            if (failure.isBlank()) {
                val r = request(host, "POST", "/core-api/key/enter")
                if (!r.ok) failure = r.message.ifBlank { "Enter failed" }
            }
            post(callback, Result(failure.isBlank(), failure))
        }
    }

    fun setSelection(
        host: String,
        start: Int,
        end: Int,
        targetId: String,
        callback: ((Result) -> Unit)? = null,
    ) {
        inputWorker.execute {
            val result = request(
                host = host,
                method = "POST",
                path = "/core-api/selection",
                body = JSONObject()
                    .put("start", start.coerceAtLeast(0))
                    .put("end", end.coerceAtLeast(start))
                    .put("targetId", targetId),
            )
            if (callback != null) post(callback, Result(result.ok, result.message))
        }
    }

    fun getStatus(host: String, callback: (StatusResult) -> Unit) {
        readWorker.execute {
            val result = request(host, "GET", "/core-api/status")
            val x = result.json
            post(
                callback,
                StatusResult(
                    ok = result.ok,
                    targetId = x?.optString("targetId").orEmpty(),
                    targetType = x?.optString("targetType", "other") ?: "other",
                    target = x?.optString("target").orEmpty(),
                    message = result.message,
                ),
            )
        }
    }

    fun getInputState(
        host: String,
        targetId: String,
        source: String = "automatic",
        copyBack: Boolean = false,
        callback: (InputStateResult) -> Unit,
    ) {
        readWorker.execute {
            val path = buildString {
                append("/core-api/input-state?targetId=")
                append(encode(targetId))
                append("&source=")
                append(encode(source))
                if (copyBack) append("&copyBack=true")
            }
            val result = request(host, "GET", path, acceptNon2xxJson = true)
            val x = result.json
            val reason = x?.optString("reason").orEmpty()
            val targetMismatch = reason == "target-mismatch"
            post(
                callback,
                InputStateResult(
                    ok = result.ok || targetMismatch,
                    supported = x?.optBoolean("supported", false) ?: false,
                    reason = reason,
                    targetId = x?.optString("targetId").orEmpty(),
                    targetType = x?.optString("targetType", "other") ?: "other",
                    text = x?.optString("text").orEmpty(),
                    selectionStart = x?.optInt("selectionStart", 0) ?: 0,
                    selectionEnd = x?.optInt("selectionEnd", 0) ?: 0,
                    controlId = x?.optString("controlId").orEmpty(),
                    message = result.message,
                ),
            )
        }
    }


    fun getClipboard(host: String, callback: (ClipboardResult) -> Unit) {
        auxWorker.execute {
            val result = request(host, "GET", "/api/clipboard")
            val x = result.json
            post(callback, ClipboardResult(
                ok = result.ok,
                hasText = x?.optBoolean("hasText", false) ?: false,
                text = x?.optString("text").orEmpty(),
                sequence = x?.optLong("sequence", 0L) ?: 0L,
                hash = x?.optString("hash").orEmpty(),
                message = result.message,
            ))
        }
    }

    fun setClipboard(host: String, text: String, callback: ((Result) -> Unit)? = null) {
        auxWorker.execute {
            val result = request(host, "POST", "/api/clipboard", JSONObject().put("text", text))
            if (callback != null) post(callback, Result(result.ok, result.message))
        }
    }

    fun getForegroundWindow(host: String, callback: (ForegroundWindowResult) -> Unit) {
        auxWorker.execute {
            val result = request(host, "GET", "/api/foreground-window")
            val w = result.json?.optJSONObject("window")
            post(callback, ForegroundWindowResult(
                ok = result.ok,
                target = w?.optString("target", "other") ?: "other",
                title = w?.optString("title").orEmpty(),
                process = w?.optString("process").orEmpty(),
                pid = w?.optLong("pid", 0L) ?: 0L,
                message = result.message,
            ))
        }
    }

    fun getDiagnostics(host: String, callback: (HostDiagnosticsResult) -> Unit) {
        auxWorker.execute {
            val result = request(host, "GET", "/api/diagnostics")
            val x = result.json
            val w = x?.optJSONObject("foregroundWindow")
            post(callback, HostDiagnosticsResult(
                ok = result.ok,
                version = x?.optString("version").orEmpty(),
                protocol = x?.optInt("protocol", 0) ?: 0,
                activeConnections = x?.optInt("activeConnections", 0) ?: 0,
                uptimeSeconds = x?.optLong("uptimeSeconds", 0L) ?: 0L,
                goroutines = x?.optInt("goroutines", 0) ?: 0,
                coreAvailable = x?.optBoolean("coreAvailable", false) ?: false,
                coreDetail = x?.optString("coreDetail").orEmpty(),
                foregroundTarget = w?.optString("target", "other") ?: "other",
                foregroundTitle = w?.optString("title").orEmpty(),
                pendingFiles = x?.optInt("pendingFiles", 0) ?: 0,
                uploadCount = x?.optLong("uploadCount", 0L) ?: 0L,
                downloadCount = x?.optLong("downloadCount", 0L) ?: 0L,
                uploadBytes = x?.optLong("uploadBytes", 0L) ?: 0L,
                downloadBytes = x?.optLong("downloadBytes", 0L) ?: 0L,
                lastTransfer = x?.optString("lastTransfer").orEmpty(),
                lastTransferError = x?.optString("lastTransferError").orEmpty(),
                message = result.message,
            ))
        }
    }

    fun getScreenshot(host: String, callback: (ScreenshotResult) -> Unit) {
        screenshotWorker.execute {
            val normalized = normalizeHost(host)
            if (normalized.isBlank()) {
                post(callback, ScreenshotResult(false, message = "电脑地址为空"))
                return@execute
            }
            val result = runCatching {
                val connection = (URL("http://$normalized:${ProtocolV2.PORT}/api/screenshot").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 12000
                    useCaches = false
                    setRequestProperty("Accept", "image/png")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        ScreenshotResult(false, message = detail.ifBlank { "电脑端返回 HTTP $code" })
                    } else {
                        val maxBytes = 32 * 1024 * 1024
                        val bytes = connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(32 * 1024)
                            var total = 0
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) throw IllegalStateException("截图数据过大")
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                        ScreenshotResult(
                            ok = bytes.isNotEmpty(),
                            png = bytes,
                            width = connection.getHeaderField("X-PhoneInput-Width")?.toIntOrNull() ?: 0,
                            height = connection.getHeaderField("X-PhoneInput-Height")?.toIntOrNull() ?: 0,
                            message = if (bytes.isEmpty()) "截图为空" else "",
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { e -> ScreenshotResult(false, message = e.message?.ifBlank { e.javaClass.simpleName } ?: e.javaClass.simpleName) }
            post(callback, result)
        }
    }

    fun shutdown() {
        inputWorker.shutdownNow()
        readWorker.shutdownNow()
        auxWorker.shutdownNow()
        screenshotWorker.shutdownNow()
    }

    private fun request(
        host: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        acceptNon2xxJson: Boolean = false,
    ): HttpResult {
        val normalized = normalizeHost(host)
        if (normalized.isBlank()) return HttpResult(false, 0, null, "电脑地址为空")
        return runCatching {
            val url = URL("http://$normalized:${ProtocolV2.PORT}$path")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3000
                readTimeout = 8000
                doOutput = body != null
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                if (body != null) {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = if (stream != null) {
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                } else ""
                val json = response.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                val ok = code in 200..299
                val detail = json?.optString("error").orEmpty()
                    .ifBlank { json?.optString("reason").orEmpty() }
                    .ifBlank { if (ok) "" else "电脑端返回 HTTP $code" }
                HttpResult(ok || (acceptNon2xxJson && json != null), code, json, detail)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            val detail = error.message?.trim().orEmpty()
            HttpResult(false, 0, null, if (detail.isBlank()) error.javaClass.simpleName else detail)
        }
    }

    private fun <T> post(callback: (T) -> Unit, value: T) {
        main.post { callback(value) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun encodePath(value: String): String = encode(value).replace("%2F", "/")

    private fun normalizeHost(raw: String): String {
        var value = raw.trim()
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://")
        value = value.substringBefore('/').substringBefore(':')
        return value.trim()
    }
}
