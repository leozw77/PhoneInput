package com.phoneinputenhanced.nativeclient

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class DiagnosticsDialog(
    private val activity: Activity,
    private val api: NativeCoreApi,
    private val client: NativeWebSocket,
    private val hostProvider: () -> String,
    private val transferDiagnosticsProvider: () -> FileTransferManager.DiagnosticsSnapshot,
    private val voiceDiagnosticsProvider: () -> VoiceImeRelayDialog.DiagnosticsSnapshot,
) {
    private val main = Handler(Looper.getMainLooper())
    private var hostDiagnostics: NativeCoreApi.HostDiagnosticsResult? = null
    private var dialog: AlertDialog? = null
    private var textView: TextView? = null
    private var hostRefreshInFlight = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (dialog?.isShowing != true) return
            refresh()
            main.postDelayed(this, 1000L)
        }
    }

    fun show() {
        if (dialog?.isShowing == true) return
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
        }
        textView = TextView(activity).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(textView)
        val d = AlertDialog.Builder(activity)
            .setTitle("连接与性能诊断")
            .setView(root)
            .setNeutralButton("复制诊断", null)
            .setNegativeButton("刷新", null)
            .setPositiveButton("关闭", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val text = buildText()
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PhoneInputEnhanced diagnostics", text))
                Toast.makeText(activity, "诊断信息已复制", Toast.LENGTH_SHORT).show()
            }
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { refresh(forceHost = true) }
        }
        d.setOnDismissListener {
            main.removeCallbacks(refreshRunnable)
            dialog = null
            textView = null
        }
        d.show()
        dialog = d
        refresh(forceHost = true)
        main.postDelayed(refreshRunnable, 1000L)
    }

    private fun refresh(forceHost: Boolean = false) {
        textView?.text = buildText()
        val host = hostProvider().trim()
        if (host.isBlank() || hostRefreshInFlight) return
        hostRefreshInFlight = true
        api.getDiagnostics(host) { result ->
            hostRefreshInFlight = false
            if (dialog?.isShowing != true) return@getDiagnostics
            hostDiagnostics = result
            textView?.text = buildText()
        }
    }

    private fun buildText(): String {
        val s = client.diagnosticsSnapshot()
        val h = hostDiagnostics
        return buildString {
            appendLine("Android")
            appendLine("  app=${BuildConfig.VERSION_NAME}")
            appendLine("  device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("  sdk=${Build.VERSION.SDK_INT}")
            appendLine("  localIp=${localIPv4().ifBlank { "unknown" }}")
            appendLine("  host=${s.host.ifBlank { hostProvider().trim().ifBlank { "unset" } }}:${ProtocolV2.PORT}")
            appendLine("  state=${s.state} ${s.detail}")
            appendLine("  serverVersion=${s.serverVersion.ifBlank { "unknown" }}")
            appendLine("  reconnectCount=${s.reconnectCount}")
            appendLine("  writerQueue=${s.writerQueueDepth}")
            appendLine("  commands=${s.commandsWritten} move=${s.moveCommandsWritten} scroll=${s.scrollCommandsWritten}")
            appendLine("  ackAge=${formatAge(s.lastAckAgeMs)} heartbeatAge=${formatAge(s.lastHeartbeatAgeMs)}")
            appendLine("  connectedFor=${formatAge(s.connectedForMs)}")
            appendLine("  waitingForNetwork=${s.waitingForNetwork}")
            val t = transferDiagnosticsProvider()
            appendLine("  transfer uploading=${t.uploading} downloading=${t.downloading}")
            appendLine("  transfer sent=${t.uploadedFiles}/${formatBytes(t.uploadedBytes)} received=${t.downloadedFiles}/${formatBytes(t.downloadedBytes)}")
            appendLine("  transferLast=${t.lastTransfer.ifBlank { "none" }}")
            appendLine("  transferError=${t.lastError.ifBlank { "none" }}")
            appendLine("  lastError=${s.lastError.ifBlank { "none" }}")
            val v = voiceDiagnosticsProvider()
            appendLine("  voiceIme sessions=${v.sessions} edits=${v.successfulEdits} inserted=${v.insertedCodePoints} backspaces=${v.backspaces}")
            appendLine("  voiceIme lastSuccess=${formatAge(v.lastSuccessAgeMs)} error=${v.lastError.ifBlank { "none" }}")
            appendLine()
            appendLine("Windows Host")
            if (h == null) {
                appendLine("  loading…")
            } else if (!h.ok) {
                appendLine("  unavailable=${h.message}")
            } else {
                appendLine("  version=${h.version}")
                appendLine("  protocol=${h.protocol}")
                appendLine("  activeConnections=${h.activeConnections}")
                appendLine("  uptime=${h.uptimeSeconds}s goroutines=${h.goroutines}")
                appendLine("  coreAvailable=${h.coreAvailable} ${h.coreDetail}")
                appendLine("  foreground=${h.foregroundTarget}")
                appendLine("  title=${h.foregroundTitle.take(120)}")
                appendLine("  pendingFiles=${h.pendingFiles}")
                appendLine("  transfer uploads=${h.uploadCount}/${formatBytes(h.uploadBytes)} downloads=${h.downloadCount}/${formatBytes(h.downloadBytes)}")
                appendLine("  transferLast=${h.lastTransfer.ifBlank { "none" }}")
                appendLine("  transferError=${h.lastTransferError.ifBlank { "none" }}")
            }
            appendLine()
            appendLine("判断提示")
            appendLine("  writerQueue长期>3：手机发送队列可能积压")
            appendLine("  ackAge>60000ms：WebSocket/Host可能失活")
            appendLine("  coreAvailable=false：文字/窗口切换旧Core不可用")
            appendLine("  voiceIme error!=none：检查电脑当前输入焦点和局域网文字注入")
        }
    }

    private fun formatAge(ms: Long): String = when {
        ms < 0L -> "n/a"
        ms < 1000L -> "${ms}ms"
        else -> "${ms / 1000}s"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "%.1fKB".format(bytes / 1024.0)
        else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }

    private fun localIPv4(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress.orEmpty()
    }.getOrDefault("")

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
