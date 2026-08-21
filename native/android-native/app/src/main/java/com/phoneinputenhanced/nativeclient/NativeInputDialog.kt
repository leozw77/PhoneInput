package com.phoneinputenhanced.nativeclient

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Native input panel for preview.7. Slimmed to one action row; secondary keys live in a compact dialog.
 *
 * Batch mode keeps the preview.3 stable send path. Realtime mode mirrors the browser client's
 * targetId locking, committed-IME text injection, selection sync and input-state readback.
 */
class NativeInputDialog(
    private val activity: Activity,
    private val api: NativeCoreApi,
    private val hostProvider: () -> String,
    private val sendEnter: () -> Unit,
    private val switchWindow: (String) -> Unit,
) {
    private val prefs = activity.getSharedPreferences("phoneinput_native", Activity.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var dialog: Dialog? = null
    private var imeWasVisible = false
    private var busy = false
    private var realtime = prefs.getString(PREF_MODE, "batch") == "realtime"
    private var generation = 0L

    private var edit: RealtimeEditText? = null
    private var targetView: TextView? = null
    private var modeButton: Button? = null
    private var syncButton: Button? = null
    private var batchActions: View? = null
    private var realtimeActions: View? = null

    private var suppressTextCallbacks = false
    private var remoteUpdateDepth = 0
    private var composing = false
    private var suppressSelectionUntil = 0L
    private var selectionRunnable: Runnable? = null
    private var readbackInFlight = false
    private var manualCopyPendingTargetId = ""

    private var currentTargetId = ""
    private var currentTargetType = "other"
    private var currentTargetLabel = ""
    private var lockedTargetId = ""
    private var controlId = ""
    private var shadowText = ""
    private var projectedSelectionStart = 0
    private var projectedSelectionEnd = 0
    private var batchDraft = prefs.getString(PREF_BATCH_DRAFT, "").orEmpty()
    private var localRevision = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            val token = generation
            if (!isActive(token) || !realtime) return
            refreshTarget(token, readbackWhenChanged = true)
            main.postDelayed(this, 2000)
        }
    }

    fun show(source: String = "gesture") {
        val existing = dialog
        if (existing?.isShowing == true) {
            edit?.let(::focusAndShowIme)
            return
        }

        val token = ++generation
        val d = Dialog(activity)
        d.setCanceledOnTouchOutside(true)
        d.setContentView(buildContent(d, source, token))
        d.setOnDismissListener {
            if (realtime) saveRealtimeLocalDraft() else saveBatchDraft()
            main.removeCallbacks(pollRunnable)
            selectionRunnable?.let { main.removeCallbacks(it) }
            selectionRunnable = null
            busy = false
            imeWasVisible = false
            readbackInFlight = false
            remoteUpdateDepth = 0
            dialog = null
            edit = null
            targetView = null
            modeButton = null
            syncButton = null
            batchActions = null
            realtimeActions = null
            generation++
        }
        d.show()
        dialog = d

        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.38f }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            d.window?.decorView?.setOnApplyWindowInsetsListener { _, insets ->
                val visible = insets.isVisible(WindowInsets.Type.ime())
                if (visible) imeWasVisible = true
                else if (imeWasVisible && !busy && d.isShowing) d.dismiss()
                insets
            }
            d.window?.decorView?.requestApplyInsets()
        }

        updateModeUi(token, initial = true)
        edit?.postDelayed({ edit?.let(::focusAndShowIme) }, 120)
    }

    private fun buildContent(d: Dialog, source: String, token: Long): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = rounded(Color.rgb(24, 27, 33), Color.rgb(69, 74, 86), 18f)
        }

        root.addView(TextView(activity).apply {
            text = if (source == "two-finger-hold") "输入" else "PhoneInputEnhanced 输入"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }, fullWidth(wrap()).apply { bottomMargin = dp(4) })

        val windowRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        windowRow.addView(button("ChatGPT") { switchInputWindow("chatgpt", token) }, weighted())
        windowRow.addView(button("Chrome") { switchInputWindow("chrome", token) }, weighted())
        windowRow.addView(button("微信") { switchInputWindow("wechat", token) }, weighted(last = true))
        root.addView(windowRow, fullWidth(dp(36)).apply { bottomMargin = dp(5) })

        targetView = TextView(activity).apply {
            text = "正在读取电脑输入目标…"
            setTextColor(Color.rgb(180, 188, 205))
            textSize = 11f
            maxLines = 1
            setPadding(dp(2), 0, dp(2), dp(5))
        }
        root.addView(targetView, fullWidth(wrap()))

        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        modeButton = button("批量") {
            realtime = !realtime
            prefs.edit().putString(PREF_MODE, if (realtime) "realtime" else "batch").apply()
            updateModeUi(token, initial = false)
        }
        syncButton = button("从电脑同步") { manualSync(token) }
        modeRow.addView(modeButton, weighted())
        modeRow.addView(syncButton, weighted(last = true))
        root.addView(modeRow, fullWidth(dp(38)).apply { bottomMargin = dp(6) })

        val input = RealtimeEditText(activity).apply {
            id = INPUT_ID
            minLines = 4
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 120, 139))
            hint = "输入文字"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(34, 38, 46), Color.rgb(73, 80, 95), 12f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        edit = input
        input.onImeSubmit = { handleImeSubmit(d, input, token) }
        installRealtimeListeners(input, token)
        root.addView(input, fullWidth(dp(126)).apply { bottomMargin = dp(7) })

        batchActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("发送") { sendBatch(d, input, enterAfter = false) }, weighted())
            addView(button("发送并回车") { sendBatch(d, input, enterAfter = true) }, weighted())
            addView(button("关闭") { d.dismiss() }, weighted(last = true))
        }
        root.addView(batchActions, fullWidth(dp(43)))

        realtimeActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Enter") { sendRealtimeKey("enter", token) }, weighted())
            addView(button("快捷键") { showRealtimeShortcuts(token) }, weighted())
            addView(button("关闭") { d.dismiss() }, weighted(last = true))
        }
        root.addView(realtimeActions, fullWidth(dp(43)))
        return root
    }

    private fun switchInputWindow(target: String, token: Long) {
        if (!isActive(token)) return
        switchWindow(target)
        val label = humanTarget(target)
        if (!realtime) {
            targetView?.text = "批量输入 · 已切换到 $label"
            edit?.postDelayed({ edit?.let(::focusAndShowIme) }, 80)
            return
        }

        // Switching windows intentionally invalidates the old realtime target lock. Clear the local
        // projection first so text from the previous app can never be injected into the new one.
        lockedTargetId = ""
        controlId = ""
        currentTargetId = ""
        currentTargetType = target
        currentTargetLabel = label
        shadowText = ""
        projectedSelectionStart = 0
        projectedSelectionEnd = 0
        setEditProgrammatically("", 0, 0)
        setStatus("正在切换到 $label…", false)
        main.postDelayed({
            if (isActive(token) && realtime) refreshTarget(token, readbackWhenChanged = true, forceReadback = true)
        }, 320L)
        edit?.postDelayed({ edit?.let(::focusAndShowIme) }, 100)
    }

    private fun installRealtimeListeners(input: RealtimeEditText, token: Long) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(value: Editable?) {
                if (!isActive(token) || suppressTextCallbacks || remoteUpdateDepth > 0 || !realtime) return
                val editable = value ?: return
                val hasComposition = BaseInputConnection.getComposingSpanStart(editable) >= 0 ||
                    BaseInputConnection.getComposingSpanEnd(editable) >= 0
                if (hasComposition) {
                    composing = true
                    return
                }
                if (composing) composing = false
                suppressSelectionUntil = SystemClock.uptimeMillis() + 180
                localRevision++
                handleRealtimeMutation(editable.toString(), token)
            }
        })
        input.onSelectionChangedListener = selection@ { start, end ->
            if (!isActive(token) || !realtime || suppressTextCallbacks || remoteUpdateDepth > 0 || composing) return@selection
            if (SystemClock.uptimeMillis() < suppressSelectionUntil) return@selection
            scheduleSelectionSync(start, end, token)
        }
    }

    private fun updateModeUi(token: Long, initial: Boolean) {
        if (!isActive(token)) return
        modeButton?.text = if (realtime) "即时输入" else "批量输入"
        syncButton?.visibility = if (realtime) View.VISIBLE else View.GONE
        batchActions?.visibility = if (realtime) View.GONE else View.VISIBLE
        realtimeActions?.visibility = if (realtime) View.VISIBLE else View.GONE
        manualCopyPendingTargetId = ""
        lockedTargetId = ""
        controlId = ""
        selectionRunnable?.let { main.removeCallbacks(it) }
        selectionRunnable = null
        main.removeCallbacks(pollRunnable)

        if (realtime) {
            if (!initial) {
                batchDraft = edit?.text?.toString().orEmpty()
                saveBatchDraft()
            }
            setEditProgrammatically("", 0, 0)
            shadowText = ""
            projectedSelectionStart = 0
            projectedSelectionEnd = 0
            edit?.hint = "即时输入：IME 确认后的文字会实时发送到电脑"
            setStatus("正在识别电脑输入框并回读…", false)
            refreshTarget(token, readbackWhenChanged = true, forceReadback = true)
            main.postDelayed(pollRunnable, 2000)
        } else {
            if (!initial) saveRealtimeLocalDraft()
            currentTargetId = ""
            currentTargetType = "other"
            currentTargetLabel = ""
            setEditProgrammatically(batchDraft, batchDraft.length, batchDraft.length)
            edit?.hint = "输入完整文字，然后点击发送"
            targetView?.text = "批量输入 · 发送到电脑当前输入位置"
            setStatus("批量输入不会边打边发送。", false)
        }
        edit?.post { edit?.let(::focusAndShowIme) }
    }

    private fun refreshTarget(token: Long, readbackWhenChanged: Boolean, forceReadback: Boolean = false) {
        if (!isActive(token) || !realtime) return
        val host = hostProvider().trim()
        if (host.isEmpty()) {
            setStatus("请先连接电脑", true)
            return
        }
        api.getStatus(host) { result ->
            if (!isActive(token) || !realtime) return@getStatus
            if (!result.ok) {
                setStatus("读取目标失败：${result.message.ifBlank { "电脑端不可用" }}", true)
                return@getStatus
            }
            val previous = currentTargetId
            currentTargetId = result.targetId
            currentTargetType = result.targetType
            currentTargetLabel = result.target.ifBlank { humanTarget(result.targetType) }
            val changedWhileLocked = lockedTargetId.isNotBlank() && currentTargetId != lockedTargetId
            targetView?.text = when {
                currentTargetId.isBlank() -> "未识别当前电脑输入窗口"
                changedWhileLocked -> "窗口已变化 · ${currentTargetLabel.ifBlank { currentTargetType }}"
                else -> currentTargetLabel.ifBlank { "已识别电脑输入窗口" }
            }
            if (changedWhileLocked) {
                setStatus("电脑窗口已变化；为防止文字发错位置，即时发送已暂停。点“从电脑同步”。", true)
                return@getStatus
            }
            if (currentTargetId.isNotBlank() && (forceReadback || (readbackWhenChanged && previous != currentTargetId))) {
                syncDesktopState(token, currentTargetId, manual = false, copyBack = false, attempt = 0)
            }
        }
    }

    private fun manualSync(token: Long) {
        if (!isActive(token) || !realtime) return
        val target = currentTargetId
        if (target.isBlank()) {
            setStatus("未识别当前电脑输入窗口，正在重试…", true)
            refreshTarget(token, readbackWhenChanged = true, forceReadback = true)
            return
        }
        val copyBack = manualCopyPendingTargetId == target
        syncDesktopState(token, target, manual = true, copyBack = copyBack, attempt = 0)
    }

    private fun syncDesktopState(
        token: Long,
        targetId: String,
        manual: Boolean,
        copyBack: Boolean,
        attempt: Int,
    ) {
        if (!isActive(token) || !realtime || readbackInFlight) return
        readbackInFlight = true
        val host = hostProvider().trim()
        val revisionAtRequest = localRevision
        api.getInputState(host, targetId, if (manual) "manual" else "automatic", copyBack) { state ->
            readbackInFlight = false
            if (!isActive(token) || !realtime) return@getInputState
            if (!manual && localRevision != revisionAtRequest) {
                // Do not let a delayed automatic readback overwrite text the user typed meanwhile.
                return@getInputState
            }
            if (currentTargetId != targetId || (state.targetId.isNotBlank() && state.targetId != targetId) || state.reason == "target-mismatch") {
                if (manual) setStatus("电脑目标已变化，请重试同步。", true)
                return@getInputState
            }
            if (!state.ok || !state.supported) {
                if (manual && state.reason == "google-search-pattern-unavailable" && !copyBack) {
                    manualCopyPendingTargetId = targetId
                    setStatus("当前控件普通回读不可用；再点一次“从电脑同步”尝试复制回读。", true)
                    return@getInputState
                }
                if (!manual && attempt < 3) {
                    main.postDelayed({
                        if (isActive(token) && realtime) syncDesktopState(token, targetId, false, false, attempt + 1)
                    }, 80)
                    return@getInputState
                }
                manualCopyPendingTargetId = ""
                val detail = state.reason.ifBlank { state.message }.ifBlank { "当前输入框暂不支持回读" }
                setStatus(detail, true)
                return@getInputState
            }

            manualCopyPendingTargetId = ""
            lockedTargetId = ""
            controlId = state.controlId
            shadowText = state.text
            val start = state.selectionStart.coerceIn(0, state.text.length)
            val end = state.selectionEnd.coerceIn(start, state.text.length)
            projectedSelectionStart = start
            projectedSelectionEnd = end
            applyDesktopStateProgrammatically(state.text, start, end)
            setStatus(if (manual) "已从电脑同步。" else "已回读电脑当前输入内容。", false)
            edit?.let(::focusAndShowIme)
        }
    }

    private fun handleRealtimeMutation(newText: String, token: Long) {
        if (!isActive(token) || !realtime) return
        if (currentTargetId.isBlank()) {
            setStatus("未识别电脑输入目标；手机内容已保留，但尚未发送。", true)
            return
        }
        if (lockedTargetId.isBlank()) lockedTargetId = currentTargetId
        if (currentTargetId != lockedTargetId) {
            setStatus("电脑窗口已变化；即时发送已暂停，避免文字发错位置。", true)
            return
        }

        val oldShadow = shadowText
        val plan = RealtimeDiffEngine.plan(oldShadow, newText) ?: return
        val host = hostProvider().trim()
        if (host.isBlank()) return

        val replaceStart = plan.replaceStartUtf16
        val replaceEnd = plan.replaceEndUtf16
        if (projectedSelectionStart != replaceStart || projectedSelectionEnd != replaceEnd) {
            val startSteps = TextIndex.caretSteps(oldShadow, replaceStart)
            val endSteps = TextIndex.caretSteps(oldShadow, replaceEnd)
            api.setSelection(host, startSteps, endSteps, lockedTargetId, ::reportRealtimeResult)
        }
        if (plan.removesText) {
            api.sendCoreKey(host, "backspace", lockedTargetId, ::reportRealtimeResult)
        }
        if (plan.insertedText.isNotEmpty()) {
            api.sendRealtimeText(host, plan.insertedText, lockedTargetId, 6, ::reportRealtimeResult)
        }

        // Update the projected desktop state immediately; the HTTP write executor preserves order.
        shadowText = newText
        val newCaret = replaceStart + plan.insertedText.length
        projectedSelectionStart = newCaret
        projectedSelectionEnd = newCaret
        setStatus("即时输入中 · 已锁定 ${currentTargetLabel.ifBlank { humanTarget(currentTargetType) }}", false)
    }

    private fun scheduleSelectionSync(start: Int, end: Int, token: Long) {
        if (!realtime || lockedTargetId.isBlank() || currentTargetId != lockedTargetId || edit?.text.isNullOrEmpty()) return
        selectionRunnable?.let { main.removeCallbacks(it) }
        val action = Runnable {
            if (!isActive(token) || !realtime || composing) return@Runnable
            val input = edit ?: return@Runnable
            val safeStart = input.selectionStart.coerceAtLeast(0)
            val safeEnd = input.selectionEnd.coerceAtLeast(safeStart)
            if (projectedSelectionStart == safeStart && projectedSelectionEnd == safeEnd) return@Runnable
            projectedSelectionStart = safeStart
            projectedSelectionEnd = safeEnd
            api.setSelection(
                hostProvider().trim(),
                TextIndex.caretSteps(input.text.toString(), safeStart),
                TextIndex.caretSteps(input.text.toString(), safeEnd),
                lockedTargetId,
                ::reportRealtimeResult,
            )
        }
        selectionRunnable = action
        main.postDelayed(action, 120)
    }

    /**
     * Normalized Android IME enter/send action.
     * Batch mode sends the pending text and then one Windows Enter.
     * Realtime mode has already mirrored committed text, so only one Windows Enter is sent.
     */
    private fun handleImeSubmit(d: Dialog, input: RealtimeEditText, token: Long): Boolean {
        if (!isActive(token) || busy) return true
        if (realtime) {
            sendRealtimeKey("enter", token)
        } else {
            sendBatch(d, input, enterAfter = true)
        }
        return true
    }

    private fun showRealtimeShortcuts(token: Long) {
        val items = arrayOf("Backspace", "Tab", "Esc", "←", "↑", "↓", "→", "清空手机输入框")
        AlertDialog.Builder(activity)
            .setTitle("快捷键")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> realtimeBackspace(token)
                    1 -> sendRealtimeKey("tab", token)
                    2 -> sendRealtimeKey("escape", token)
                    3 -> sendRealtimeKey("left", token)
                    4 -> sendRealtimeKey("up", token)
                    5 -> sendRealtimeKey("down", token)
                    6 -> sendRealtimeKey("right", token)
                    7 -> clearRealtimeLocal()
                }
                edit?.let(::focusAndShowIme)
            }
            .show()
    }

    private fun realtimeBackspace(token: Long) {
        if (!isActive(token) || !realtime) return
        val input = edit ?: return
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        if (start != end) {
            input.text?.delete(start, end)
            return
        }
        if (start > 0) {
            val cp = Character.codePointBefore(input.text, start)
            val previous = (start - Character.charCount(cp)).coerceAtLeast(0)
            input.text?.delete(previous, start)
            return
        }
        sendRealtimeKey("backspace", token)
    }

    private fun sendRealtimeKey(key: String, token: Long) {
        if (!isActive(token) || !realtime) return
        val target = if (lockedTargetId.isNotBlank()) lockedTargetId else currentTargetId
        if (target.isBlank() || (lockedTargetId.isNotBlank() && currentTargetId != lockedTargetId)) {
            setStatus("当前输入目标不可用，请先从电脑同步。", true)
            return
        }
        api.sendCoreKey(hostProvider().trim(), key, target) { result ->
            reportRealtimeResult(result)
            if (!result.ok || !isActive(token)) return@sendCoreKey
            when (key) {
                "enter" -> {
                    lockedTargetId = ""
                    shadowText = ""
                    projectedSelectionStart = 0
                    projectedSelectionEnd = 0
                    setEditProgrammatically("", 0, 0)
                    setStatus("已发送 Enter。", false)
                    main.postDelayed({ refreshTarget(token, true, false) }, 220)
                }
                "tab", "escape" -> main.postDelayed({ refreshTarget(token, true, true) }, 120)
                "left", "right", "up", "down" -> main.postDelayed({
                    if (currentTargetId.isNotBlank()) syncDesktopState(token, currentTargetId, false, false, 0)
                }, 90)
            }
        }
    }

    private fun clearRealtimeLocal() {
        lockedTargetId = ""
        shadowText = ""
        projectedSelectionStart = 0
        projectedSelectionEnd = 0
        setEditProgrammatically("", 0, 0)
        setStatus("已清空手机输入框；电脑内容未删除。下一次输入会重新锁定当前目标。", false)
        edit?.let(::focusAndShowIme)
    }

    private fun reportRealtimeResult(result: NativeCoreApi.Result) {
        if (!result.ok) setStatus("即时输入失败：${result.message.ifBlank { "电脑端请求失败" }}", true)
    }

    private fun sendBatch(d: Dialog, input: RealtimeEditText, enterAfter: Boolean) {
        if (busy) return
        val value = input.text?.toString().orEmpty()
        if (value.isEmpty()) {
            Toast.makeText(activity, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }
        val host = hostProvider().trim()
        if (host.isEmpty()) {
            Toast.makeText(activity, "请先连接电脑", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        api.sendText(host, value) { result ->
            busy = false
            if (!result.ok) {
                Toast.makeText(activity, "发送失败：${result.message}", Toast.LENGTH_SHORT).show()
                return@sendText
            }
            if (enterAfter) sendEnter()
            batchDraft = ""
            prefs.edit().putString(PREF_BATCH_DRAFT, "").apply()
            setEditProgrammatically("", 0, 0)
            Toast.makeText(activity, if (enterAfter) "已发送并回车" else "文字已发送", Toast.LENGTH_SHORT).show()
            if (d.isShowing) d.dismiss()
        }
    }

    private fun applyDesktopStateProgrammatically(value: String, start: Int, end: Int) {
        remoteUpdateDepth++
        try {
            setEditProgrammatically(value, start, end)
        } finally {
            remoteUpdateDepth = (remoteUpdateDepth - 1).coerceAtLeast(0)
        }
    }

    private fun setEditProgrammatically(value: String, start: Int, end: Int) {
        val input = edit ?: return
        suppressTextCallbacks = true
        try {
            input.setText(value)
            val safeStart = start.coerceIn(0, value.length)
            val safeEnd = end.coerceIn(safeStart, value.length)
            input.setSelection(safeStart, safeEnd)
        } finally {
            suppressTextCallbacks = false
            suppressSelectionUntil = SystemClock.uptimeMillis() + 180
        }
    }

    private fun saveBatchDraft() {
        if (!realtime) batchDraft = edit?.text?.toString() ?: batchDraft
        prefs.edit().putString(PREF_BATCH_DRAFT, batchDraft).apply()
    }

    private fun saveRealtimeLocalDraft() {
        // Preview.4 deliberately does not restore a realtime draft into a different Windows target.
        // Keeping the projected state only for the active dialog is safer than cross-target injection.
    }

    private fun setStatus(message: String, error: Boolean) {
        val view = targetView ?: return
        view.text = message
        view.setTextColor(if (error) Color.rgb(238, 142, 142) else Color.rgb(154, 192, 164))
    }

    private fun humanTarget(type: String): String = when (type) {
        "chatgpt" -> "ChatGPT"
        "chrome" -> "Chrome"
        "wechat" -> "微信"
        else -> "电脑当前输入窗口"
    }

    private fun isActive(token: Long): Boolean = token == generation && dialog?.isShowing == true

    private fun focusAndShowIme(input: RealtimeEditText) {
        input.requestFocus()
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun button(text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        setPadding(dp(3), 0, dp(3), 0)
        background = rounded(Color.rgb(42, 47, 58), Color.rgb(75, 83, 101), 12f)
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun weighted(last: Boolean = false) = LinearLayout.LayoutParams(0, match(), 1f).apply {
        if (!last) marginEnd = dp(7)
    }

    private fun fullWidth(height: Int) = LinearLayout.LayoutParams(match(), height)
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val INPUT_ID = 0x50494531
        private const val PREF_MODE = "native_input_mode"
        private const val PREF_BATCH_DRAFT = "native_batch_draft"
    }
}
