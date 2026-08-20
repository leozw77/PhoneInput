package com.phoneinputenhanced.nativeclient

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), NativeWebSocket.Listener {
    private lateinit var client: NativeWebSocket
    private lateinit var coreApi: NativeCoreApi
    private lateinit var inputDialog: NativeInputDialog
    private lateinit var voiceImeRelay: VoiceImeRelayDialog
    private lateinit var transferManager: FileTransferManager
    private lateinit var networkMonitor: NetworkLifecycleMonitor
    private lateinit var statusView: TextView
    private lateinit var foregroundView: TextView
    private lateinit var hostInput: EditText
    private lateinit var connectButton: Button
    private lateinit var touchpad: TouchpadView
    private lateinit var dragLockButton: Button
    private lateinit var chatgptButton: Button
    private lateinit var chromeButton: Button
    private lateinit var wechatButton: Button
    private lateinit var screenshotButton: Button

    private val main = Handler(Looper.getMainLooper())
    private var connectionState = NativeWebSocket.State.Disconnected
    private var resumed = false
    private var settings = AppSettings()
    private var activeWindowTarget = "other"

    private val prefs by lazy { getSharedPreferences("phoneinput_native", MODE_PRIVATE) }

    private val windowPoll = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (connectionState == NativeWebSocket.State.Connected) refreshForegroundWindow()
            main.postDelayed(this, 1200L)
        }
    }

    private val transferPoll = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (connectionState == NativeWebSocket.State.Connected) transferManager.pollPending()
            main.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = Color.rgb(17, 19, 24)
        window.navigationBarColor = Color.rgb(17, 19, 24)

        settings = AppSettings.load(this)
        client = NativeWebSocket(this)
        coreApi = NativeCoreApi()
        inputDialog = NativeInputDialog(
            activity = this,
            api = coreApi,
            hostProvider = { hostInput.text?.toString().orEmpty() },
            sendEnter = { client.key("enter") },
            switchWindow = { target -> switchWindow(target) },
        )
        voiceImeRelay = VoiceImeRelayDialog(
            activity = this,
            api = coreApi,
            hostProvider = { hostInput.text?.toString().orEmpty() },
            onStatus = { message -> showBrief(message) },
        )
        transferManager = FileTransferManager(
            context = this,
            hostProvider = { hostInput.text?.toString().orEmpty() },
            onStatus = { message -> showBrief(message) },
            onReceived = { name, mime, uri -> onFileReceived(name, mime, uri) },
        )
        networkMonitor = NetworkLifecycleMonitor(
            context = this,
            onAvailable = { client.onNetworkAvailable() },
            onLost = { client.onNetworkLost() },
        )

        val root = buildUi()
        setContentView(root)
        applySystemBarInsets(root)
        touchpad.updateSettings(settings)

        val saved = prefs.getString("host", "") ?: ""
        hostInput.setText(saved)
        networkMonitor.start()
        if (saved.isNotBlank()) {
            client.connect(saved)
            if (!networkMonitor.isLanAvailable()) client.onNetworkLost()
        }
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        client.onAppForeground()
        main.removeCallbacks(windowPoll)
        main.post(windowPoll)
        main.removeCallbacks(transferPoll)
        main.post(transferPoll)
    }

    override fun onPause() {
        // Release held mouse state before Android can freeze/kill this Activity.
        touchpad.resetForLifecycle()
        voiceImeRelay.dismissForLifecycle()
        client.releaseAll()
        resumed = false
        main.removeCallbacks(windowPoll)
        main.removeCallbacks(transferPoll)
        super.onPause()
    }

    override fun onDestroy() {
        networkMonitor.stop()
        transferManager.shutdown()
        main.removeCallbacksAndMessages(null)
        client.shutdown()
        coreApi.shutdown()
        super.onDestroy()
    }

    override fun onStateChanged(state: NativeWebSocket.State, detail: String) {
        connectionState = state
        val label = when (state) {
            NativeWebSocket.State.Disconnected -> "未连接"
            NativeWebSocket.State.Connecting -> "连接中"
            NativeWebSocket.State.Connected -> "已连接"
            NativeWebSocket.State.Reconnecting -> "重连中"
        }
        statusView.text = if (detail.isBlank()) label else "$label · $detail"
        connectButton.text = if (state == NativeWebSocket.State.Connected) "断开" else "连接"
        val active = state == NativeWebSocket.State.Connected
        touchpad.alpha = if (active) 1f else 0.64f
        if (!active) {
            touchpad.resetForLifecycle()
            updateForegroundWindow("other", "")
        } else {
            if (resumed) {
                refreshForegroundWindow()
                transferManager.pollPending()
            }
        }
    }

    override fun onProtocolMessage(message: String) = showBrief(message)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(Color.rgb(17, 19, 24))
        }

        val titleRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "PhoneInputEnhanced · Native 1.4.0"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, wrap(), 1f))
        titleRow.addView(compactButton("文件") { openFileTransferMenu() }, LinearLayout.LayoutParams(dp(52), dp(34)).apply { marginEnd = dp(5) })
        titleRow.addView(compactButton("设置") { openSettings() }, LinearLayout.LayoutParams(dp(52), dp(34)).apply { marginEnd = dp(5) })
        titleRow.addView(compactButton("诊断") { openDiagnostics() }, LinearLayout.LayoutParams(dp(52), dp(34)))
        root.addView(titleRow, fullWidth(dp(40)).apply { bottomMargin = dp(2) })

        statusView = TextView(this).apply {
            text = "未连接"
            setTextColor(Color.rgb(174, 183, 204))
            textSize = 12f
            isSingleLine = true
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        root.addView(statusView, fullWidth(wrap()))

        val connectionRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        hostInput = EditText(this).apply {
            hint = "电脑 IP，例如 192.168.1.20"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(116, 124, 143))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.rgb(30, 33, 40), Color.rgb(67, 72, 84), 12f)
        }
        connectButton = actionButton("连接") {
            val host = hostInput.text.toString().trim()
            if (connectionState == NativeWebSocket.State.Connected) {
                client.disconnect()
            } else if (host.isBlank()) {
                showBrief("请输入电脑 IP")
            } else {
                prefs.edit().putString("host", host).apply()
                client.connect(host)
                if (!networkMonitor.isLanAvailable()) client.onNetworkLost()
            }
        }
        connectionRow.addView(hostInput, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        connectionRow.addView(connectButton, LinearLayout.LayoutParams(dp(72), dp(44)))
        root.addView(connectionRow, fullWidth(wrap()).apply { bottomMargin = dp(8) })

        val windowRow = row()
        chatgptButton = actionButton("ChatGPT") { switchWindow("chatgpt") }
        chromeButton = actionButton("Chrome") { switchWindow("chrome") }
        wechatButton = actionButton("微信") { switchWindow("wechat") }
        windowRow.addView(chatgptButton, weightedButton())
        windowRow.addView(chromeButton, weightedButton())
        windowRow.addView(wechatButton, weightedButton(last = true))
        root.addView(windowRow, fullWidth(dp(44)))

        foregroundView = TextView(this).apply {
            text = "当前窗口：未识别"
            setTextColor(Color.rgb(139, 149, 171))
            textSize = 11f
            isSingleLine = true
            setPadding(dp(4), dp(3), dp(4), dp(7))
        }
        root.addView(foregroundView, fullWidth(wrap()))

        val touchpadFrame = FrameLayout(this)
        touchpad = TouchpadView(this, object : TouchpadView.Listener {
            override fun onMove(dx: Int, dy: Int) = client.sendMove(dx, dy)
            override fun onScroll(x: Int, y: Int) = client.sendScroll(x, y)
            override fun onLeftClick() = client.click("left")
            override fun onRightClick() = client.click("right")
            override fun onLeftButton(down: Boolean) = client.button("left", down)
            override fun onOpenTextInput() {
                if (connectionState != NativeWebSocket.State.Connected) {
                    showBrief("请先连接电脑")
                    return
                }
                inputDialog.show("two-finger-hold")
            }
            override fun onDragLockChanged(enabled: Boolean) = updateDragLockButton(enabled)
        })
        touchpadFrame.addView(touchpad, FrameLayout.LayoutParams(match(), match()))

        val voiceRow = row()
        val backspaceButton = actionButton("⌫ 退格") { }.apply { textSize = 12.5f }
        installRepeatingBackspace(backspaceButton)
        voiceRow.addView(backspaceButton, LinearLayout.LayoutParams(0, match(), 1f).apply { marginEnd = dp(6) })
        voiceRow.addView(actionButton("🎤 语音") { openImeVoiceRelay() }.apply { textSize = 12.5f }, LinearLayout.LayoutParams(0, match(), 1.25f).apply { marginEnd = dp(6) })
        voiceRow.addView(actionButton("↵ 回车") { sendTouchpadKey("enter") }.apply { textSize = 12.5f }, LinearLayout.LayoutParams(0, match(), 1f))
        touchpadFrame.addView(voiceRow, FrameLayout.LayoutParams(dp(270), dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(12)
        })
        root.addView(touchpadFrame, LinearLayout.LayoutParams(match(), 0, 1f).apply { bottomMargin = dp(8) })

        val shortcutRow = row()
        screenshotButton = actionButton("截图") { captureScreenshot() }
        shortcutRow.addView(screenshotButton, weightedButton())
        shortcutRow.addView(actionButton("复制") { client.hotkey("copy") }, weightedButton())
        shortcutRow.addView(actionButton("粘贴") { client.hotkey("paste") }, weightedButton(last = true))
        root.addView(shortcutRow, fullWidth(dp(45)).apply { bottomMargin = dp(7) })

        val mouseRow = row()
        mouseRow.addView(actionButton("左键") { client.click("left") }, weightedButton())
        mouseRow.addView(actionButton("中键") { client.click("middle") }, weightedButton())
        mouseRow.addView(actionButton("右键") { client.click("right") }, weightedButton())
        dragLockButton = actionButton("拖动锁定") {
            if (connectionState != NativeWebSocket.State.Connected) showBrief("请先连接电脑") else touchpad.toggleDragLock()
        }
        mouseRow.addView(dragLockButton, weightedButton(last = true))
        root.addView(mouseRow, fullWidth(dp(49)))
        updateDragLockButton(false)
        return root
    }

    private fun switchWindow(target: String) {
        if (settings.hapticFeedback) touchpad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        client.switchWindow(target)
        main.postDelayed({ refreshForegroundWindow() }, 220L)
    }

    private fun openImeVoiceRelay() {
        if (connectionState != NativeWebSocket.State.Connected) {
            showBrief("请先连接电脑")
            return
        }
        if (settings.hapticFeedback) touchpad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        voiceImeRelay.show()
    }

    private fun sendTouchpadKey(key: String, haptic: Boolean = true) {
        if (connectionState != NativeWebSocket.State.Connected) {
            showBrief("请先连接电脑")
            return
        }
        if (haptic && settings.hapticFeedback) touchpad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        client.key(key)
    }

    private fun installRepeatingBackspace(button: Button) {
        var pressed = false
        val repeat = object : Runnable {
            override fun run() {
                if (!pressed || connectionState != NativeWebSocket.State.Connected) return
                sendTouchpadKey("backspace", haptic = false)
                main.postDelayed(this, 72L)
            }
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (connectionState != NativeWebSocket.State.Connected) {
                        showBrief("请先连接电脑")
                        return@setOnTouchListener true
                    }
                    pressed = true
                    sendTouchpadKey("backspace")
                    main.removeCallbacks(repeat)
                    main.postDelayed(repeat, 360L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    main.removeCallbacks(repeat)
                    true
                }
                else -> true
            }
        }
    }

    private fun captureScreenshot() {
        if (connectionState != NativeWebSocket.State.Connected) {
            showBrief("请先连接电脑")
            return
        }
        client.key("screenshot")
        showBrief("已触发 Windows 系统截图")
    }

    private fun openFileTransferMenu() {
        val items = arrayOf("发图片到电脑", "发文件到电脑", "发手机剪贴板", "取电脑剪贴板")
        AlertDialog.Builder(this)
            .setTitle("文件与剪贴板")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseFile("image/*", REQUEST_IMAGE)
                    1 -> chooseFile("*/*", REQUEST_FILE)
                    2 -> sendPhoneClipboardToPc()
                    3 -> pullPcClipboardToPhone()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun chooseFile(type: String, requestCode: Int) {
        if (hostInput.text.toString().trim().isBlank()) {
            showBrief("请先填写电脑 IP")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Activity result API kept intentionally dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.data?.let(uris::add)
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) return
        transferManager.uploadUris(uris.distinct(), if (requestCode == REQUEST_IMAGE) "image" else "file")
    }

    private fun sendPhoneClipboardToPc() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            showBrief("手机剪贴板没有文字")
            return
        }
        coreApi.setClipboard(hostInput.text.toString(), text) { result ->
            showBrief(if (result.ok) "已发到电脑剪贴板" else "发送失败：${result.message}")
        }
    }

    private fun pullPcClipboardToPhone() {
        coreApi.getClipboard(hostInput.text.toString()) { result ->
            if (!result.ok || !result.hasText) {
                showBrief(if (result.ok) "电脑剪贴板没有文字" else "读取失败：${result.message}")
                return@getClipboard
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PhoneInputEnhanced", result.text))
            showBrief("已取到手机剪贴板")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShare(sharedIntent: Intent?) {
        val action = sharedIntent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND) {
            sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
        } else {
            sharedIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        sharedIntent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isNotEmpty()) {
            val category = if (sharedIntent.type?.startsWith("image/") == true) "image" else "file"
            transferManager.uploadUris(uris.distinct(), category)
        } else {
            val text = sharedIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            if (text.isNotBlank()) {
                coreApi.setClipboard(hostInput.text.toString(), text) { result ->
                    showBrief(if (result.ok) "分享文字已发到电脑剪贴板" else "发送失败：${result.message}")
                }
            }
        }
        setIntent(Intent(Intent.ACTION_MAIN))
    }

    private fun onFileReceived(name: String, mime: String, uri: Uri?) {
        if (!name.endsWith(".apk", ignoreCase = true) || uri == null) return
        AlertDialog.Builder(this)
            .setTitle("已收到 APK")
            .setMessage("$name\n\n是否打开系统安装界面？")
            .setNegativeButton("稍后", null)
            .setPositiveButton("安装") { _, _ -> openReceivedFile(name, mime, uri) }
            .show()
    }

    private fun openReceivedFile(name: String, mime: String, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            showBrief("请允许 PhoneInputEnhanced 安装未知应用，然后再次从下载目录打开 $name")
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (name.endsWith(".apk", true)) "application/vnd.android.package-archive" else mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { showBrief("无法打开 $name：${it.message}") }
    }

    private fun refreshForegroundWindow() {
        val host = hostInput.text.toString().trim()
        if (host.isBlank()) return
        coreApi.getForegroundWindow(host) { result ->
            if (!resumed || connectionState != NativeWebSocket.State.Connected) return@getForegroundWindow
            if (result.ok) updateForegroundWindow(result.target, result.title)
        }
    }

    private fun updateForegroundWindow(target: String, title: String) {
        activeWindowTarget = target
        val label = when (target) {
            "chatgpt" -> "ChatGPT"
            "chrome" -> "Chrome"
            "wechat" -> "微信"
            else -> "其他"
        }
        foregroundView.text = if (title.isBlank()) "当前窗口：$label" else "当前窗口：$label · ${title.take(54)}"
        setWindowButtonState(chatgptButton, target == "chatgpt")
        setWindowButtonState(chromeButton, target == "chrome")
        setWindowButtonState(wechatButton, target == "wechat")
    }

    private fun setWindowButtonState(button: Button, active: Boolean) {
        button.background = if (active) {
            rounded(Color.rgb(46, 70, 60), Color.rgb(86, 148, 111), 12f)
        } else {
            rounded(Color.rgb(42, 47, 58), Color.rgb(75, 83, 101), 12f)
        }
    }

    private fun openSettings() {
        SettingsDialog(this, settings) { next ->
            settings = next
            AppSettings.save(this, next)
            touchpad.updateSettings(next)
            showBrief("设置已保存")
        }.show()
    }

    private fun openDiagnostics() {
        DiagnosticsDialog(
            activity = this,
            api = coreApi,
            client = client,
            hostProvider = { hostInput.text?.toString().orEmpty() },
            transferDiagnosticsProvider = { transferManager.diagnosticsSnapshot() },
            voiceDiagnosticsProvider = { voiceImeRelay.diagnosticsSnapshot() },
        ).show()
    }

    private fun updateDragLockButton(enabled: Boolean) {
        if (!::dragLockButton.isInitialized) return
        dragLockButton.text = if (enabled) "拖动锁定 ✓" else "拖动锁定"
        dragLockButton.background = if (enabled) {
            rounded(Color.rgb(53, 72, 62), Color.rgb(95, 137, 111), 12f)
        } else {
            rounded(Color.rgb(42, 47, 58), Color.rgb(75, 83, 101), 12f)
        }
    }

    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val baseLeft = root.paddingLeft
            val baseTop = root.paddingTop
            val baseRight = root.paddingRight
            val baseBottom = root.paddingBottom
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
                insets
            }
            root.requestApplyInsets()
        } else {
            root.fitsSystemWindows = true
        }
    }

    private fun showBrief(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setPadding(dp(4), 0, dp(4), 0)
        background = rounded(Color.rgb(42, 47, 58), Color.rgb(75, 83, 101), 12f)
        setOnClickListener { action() }
    }

    private fun compactButton(text: String, action: () -> Unit) = actionButton(text, action).apply { textSize = 12f }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun weightedButton(last: Boolean = false) = LinearLayout.LayoutParams(0, match(), 1f).apply {
        if (!last) marginEnd = dp(7)
    }

    private fun fullWidth(height: Int) = LinearLayout.LayoutParams(match(), height)
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_IMAGE = 4101
        private const val REQUEST_FILE = 4102
    }
}
