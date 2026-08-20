package com.phoneinputenhanced.nativeclient

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Small, deliberately boring LAN file bridge for preview.6.
 * Control traffic stays on WebSocket; file bytes always use independent HTTP workers.
 */
class FileTransferManager(
    private val context: Context,
    private val hostProvider: () -> String,
    private val onStatus: (String) -> Unit,
    private val onReceived: (String, String, Uri?) -> Unit,
) {
    data class DiagnosticsSnapshot(
        val uploading: Boolean,
        val downloading: Boolean,
        val uploadedFiles: Long,
        val downloadedFiles: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val lastTransfer: String,
        val lastError: String,
    )

    private data class PendingFile(
        val id: String,
        val token: String,
        val name: String,
        val size: Long,
        val mime: String,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val pollWorker = Executors.newSingleThreadExecutor()
    private val uploading = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)
    private val pollInFlight = AtomicBoolean(false)
    private val uploadedFiles = AtomicLong(0)
    private val downloadedFiles = AtomicLong(0)
    private val uploadedBytes = AtomicLong(0)
    private val downloadedBytes = AtomicLong(0)
    @Volatile private var lastTransfer = ""
    @Volatile private var lastError = ""

    fun uploadUris(uris: List<Uri>, category: String = "file") {
        if (uris.isEmpty()) return
        val host = normalizedHost()
        if (host.isBlank()) {
            status("请先填写电脑 IP")
            return
        }
        worker.execute {
            uploading.set(true)
            try {
                var success = 0
                for (uri in uris) {
                    val meta = queryMeta(uri)
                    val result = uploadOne(host, uri, meta.first, meta.second, category)
                    if (result) success++
                }
                if (success > 0) status("已发送到电脑：$success 个文件")
            } finally {
                uploading.set(false)
            }
        }
    }

    fun pollPending() {
        val host = normalizedHost()
        if (host.isBlank() || downloading.get() || !pollInFlight.compareAndSet(false, true)) return
        pollWorker.execute {
            try {
                val files = readPending(host)
                if (files.isNotEmpty()) downloadPending(host, files)
            } finally {
                pollInFlight.set(false)
            }
        }
    }

    fun diagnosticsSnapshot() = DiagnosticsSnapshot(
        uploading = uploading.get(),
        downloading = downloading.get(),
        uploadedFiles = uploadedFiles.get(),
        downloadedFiles = downloadedFiles.get(),
        uploadedBytes = uploadedBytes.get(),
        downloadedBytes = downloadedBytes.get(),
        lastTransfer = lastTransfer,
        lastError = lastError,
    )

    fun shutdown() {
        worker.shutdownNow()
        pollWorker.shutdownNow()
    }

    private fun uploadOne(host: String, uri: Uri, name: String, mime: String, category: String): Boolean {
        return runCatching {
            val connection = (URL("http://$host:${ProtocolV2.PORT}/api/files/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 60_000
                doOutput = true
                useCaches = false
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Content-Type", mime.ifBlank { "application/octet-stream" })
                setRequestProperty("X-PhoneInput-File-Name", URLEncoder.encode(name, Charsets.UTF_8.name()))
                setRequestProperty("X-PhoneInput-Category", category)
            }
            var bytes = 0L
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            bytes += n
                        }
                    }
                } ?: throw IllegalStateException("无法读取 $name")
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    throw IllegalStateException(detail.ifBlank { "HTTP $code" })
                }
                uploadedFiles.incrementAndGet()
                uploadedBytes.addAndGet(bytes)
                lastTransfer = "手机→电脑 $name (${formatBytes(bytes)})"
                lastError = ""
                true
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            lastError = "上传 $name：${error.message ?: error.javaClass.simpleName}"
            status("发送失败：${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    private fun readPending(host: String): List<PendingFile> = runCatching {
        val connection = (URL("http://$host:${ProtocolV2.PORT}/api/files/pending").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 4000
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching emptyList()
            val text = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            val array = JSONObject(text).optJSONArray("files") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val x = array.optJSONObject(i) ?: continue
                    val id = x.optString("id")
                    val token = x.optString("token")
                    if (id.isBlank() || token.isBlank()) continue
                    add(PendingFile(
                        id = id,
                        token = token,
                        name = x.optString("name", "received.bin"),
                        size = x.optLong("size", 0L),
                        mime = x.optString("mime", "application/octet-stream"),
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse {
        lastError = "检查电脑待发送文件：${it.message ?: it.javaClass.simpleName}"
        emptyList()
    }

    private fun downloadPending(host: String, files: List<PendingFile>) {
        downloading.set(true)
        try {
            for (file in files) {
                if (downloadOne(host, file)) {
                    markComplete(host, file)
                }
            }
        } finally {
            downloading.set(false)
        }
    }

    private fun downloadOne(host: String, file: PendingFile): Boolean = runCatching {
        val encodedToken = URLEncoder.encode(file.token, Charsets.UTF_8.name())
        val connection = (URL("http://$host:${ProtocolV2.PORT}/api/files/download/${file.id}?token=$encodedToken").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 120_000
            useCaches = false
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            var bytes = 0L
            val saved = createDownloadTarget(file.name, file.mime)
            try {
                saved.open().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            bytes += n
                        }
                    }
                }
                saved.finish(true)
            } catch (e: Exception) {
                saved.finish(false)
                throw e
            }
            downloadedFiles.incrementAndGet()
            downloadedBytes.addAndGet(bytes)
            lastTransfer = "电脑→手机 ${file.name} (${formatBytes(bytes)})"
            lastError = ""
            status("已收到 ${file.name}")
            android.os.Handler(context.mainLooper).post { onReceived(file.name, file.mime, saved.uri) }
            true
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        lastError = "下载 ${file.name}：${error.message ?: error.javaClass.simpleName}"
        status("接收失败：${file.name}")
        false
    }

    private fun markComplete(host: String, file: PendingFile) {
        runCatching {
            val token = URLEncoder.encode(file.token, Charsets.UTF_8.name())
            val c = (URL("http://$host:${ProtocolV2.PORT}/api/files/complete/${file.id}?token=$token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 2000
                doOutput = true
            }
            try { c.outputStream.use { }; c.responseCode } finally { c.disconnect() }
        }
    }

    private data class SaveTarget(
        val uri: Uri?,
        val open: () -> java.io.OutputStream,
        val finish: (Boolean) -> Unit,
    )

    private fun createDownloadTarget(name: String, mime: String): SaveTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneInputEnhanced")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            return SaveTarget(
                uri = uri,
                open = { context.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("无法打开下载文件") },
                finish = { success ->
                    if (success) {
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        context.contentResolver.update(uri, done, null, null)
                    } else {
                        context.contentResolver.delete(uri, null, null)
                    }
                },
            )
        }
        val base = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "PhoneInputEnhanced")
        base.mkdirs()
        val dest = uniqueFile(base, name)
        return SaveTarget(
            uri = null,
            open = { FileOutputStream(dest) },
            finish = { success -> if (!success) dest.delete() },
        )
    }

    private fun queryMeta(uri: Uri): Pair<String, String> {
        var name = "shared_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = cursor.getString(i) ?: name
            }
        }
        val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        return name to mime
    }

    private fun normalizedHost(): String = hostProvider().trim()
        .removePrefix("http://").removePrefix("https://")
        .removePrefix("ws://").removePrefix("wss://")
        .substringBefore('/').substringBefore(':').trim()

    private fun status(message: String) = android.os.Handler(context.mainLooper).post { onStatus(message) }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var file = File(dir, safe)
        if (!file.exists()) return file
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var i = 1
        while (file.exists()) { file = File(dir, "${stem}_$i$ext"); i++ }
        return file
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
