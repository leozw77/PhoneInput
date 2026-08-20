package com.phoneinputenhanced.nativeclient

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong

/**
 * A tiny IME relay used by the touchpad voice button.
 *
 * It does not record audio and does not use Android SpeechRecognizer. Instead it
 * gives the user's current keyboard (Baidu/Sogou/Gboard/etc.) a normal EditText.
 * The user can tap that keyboard's own microphone and the committed text is
 * mirrored into the currently focused Windows input.
 */
class VoiceImeRelayDialog(
    private val activity: Activity,
    private val api: NativeCoreApi,
    private val hostProvider: () -> String,
    private val onStatus: (String) -> Unit,
) {
    data class DiagnosticsSnapshot(
        val sessions: Long,
        val successfulEdits: Long,
        val insertedCodePoints: Long,
        val backspaces: Long,
        val lastSuccessAgeMs: Long,
        val lastError: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var edit: RealtimeEditText? = null
    private var relayRunnable: Runnable? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var imeWasVisible = false
    private var composing = false
    private var relayInFlight = false
    private var sendRequested = false
    private var lastRelayedText = ""
    private var desiredText = ""

    private val sessions = AtomicLong(0)
    private val successfulEdits = AtomicLong(0)
    private val insertedCodePoints = AtomicLong(0)
    private val backspaces = AtomicLong(0)
    @Volatile private var lastSuccessAt = 0L
    @Volatile private var lastError = ""

    fun show() {
        val host = hostProvider().trim()
        if (host.isBlank()) {
            onStatus("请先填写电脑 IP")
            return
        }
        val existing = dialog
        if (existing?.isShowing == true) {
            edit?.let(::focusAndShowIme)
            return
        }

        sessions.incrementAndGet()
        imeWasVisible = false
        composing = false
        relayInFlight = false
        sendRequested = false
        lastRelayedText = ""
        desiredText = ""

        val d = Dialog(activity)
        d.setCanceledOnTouchOutside(true)
        d.setContentView(buildContent(d))
        d.setOnDismissListener {
            relayRunnable?.let(main::removeCallbacks)
            relayRunnable = null
            sendRequested = false
            // Best effort: if the IME committed a final revision just before closing,
            // enqueue it before dropping references.
            desiredText = edit?.text?.toString().orEmpty()
            if (!composing) pumpRelay()
            removeLegacyImeWatcher(d)
            hideIme(edit)
            dialog = null
            edit = null
            imeWasVisible = false
        }
        d.show()
        dialog = d

        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.18f }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        installImeVisibilityWatcher(d)
        edit?.postDelayed({ edit?.let(::focusAndShowIme) }, 100L)
    }

    fun dismissForLifecycle() {
        dialog?.dismiss()
    }

    fun diagnosticsSnapshot(): DiagnosticsSnapshot {
        val now = System.currentTimeMillis()
        return DiagnosticsSnapshot(
            sessions = sessions.get(),
            successfulEdits = successfulEdits.get(),
            insertedCodePoints = insertedCodePoints.get(),
            backspaces = backspaces.get(),
            lastSuccessAgeMs = if (lastSuccessAt == 0L) -1L else (now - lastSuccessAt).coerceAtLeast(0L),
            lastError = lastError,
        )
    }

    private fun buildContent(d: Dialog): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = rounded(Color.rgb(24, 27, 33), Color.rgb(69, 74, 86), 18f)
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = "输入法语音"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, wrap(), 1f))
        titleRow.addView(button("发送") { requestSend(d) }, LinearLayout.LayoutParams(dp(64), dp(34)).apply {
            rightMargin = dp(6)
        })
        titleRow.addView(button("关闭") { d.dismiss() }, LinearLayout.LayoutParams(dp(64), dp(34)))
        root.addView(titleRow, fullWidth(dp(38)).apply { bottomMargin = dp(2) })

        root.addView(TextView(activity).apply {
            text = "点手机输入法自己的麦克风；识别文字会自动同步到电脑当前光标。"
            setTextColor(Color.rgb(173, 182, 201))
            textSize = 11.5f
            setPadding(dp(2), 0, dp(2), dp(6))
        }, fullWidth(wrap()))

        val input = RealtimeEditText(activity).apply {
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 120, 139))
            hint = "等待输入法语音…"
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(Color.rgb(34, 38, 46), Color.rgb(73, 80, 95), 12f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        edit = input
        input.onImeSubmit = {
            requestSend(d)
            true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                val editable = value ?: return
                val hasComposition = BaseInputConnection.getComposingSpanStart(editable) >= 0 ||
                    BaseInputConnection.getComposingSpanEnd(editable) >= 0
                if (hasComposition) {
                    composing = true
                    return
                }
                composing = false
                desiredText = editable.toString()
                scheduleRelay()
            }
        })
        root.addView(input, fullWidth(dp(88)))
        return root
    }

    private fun scheduleRelay() {
        relayRunnable?.let(main::removeCallbacks)
        val r = Runnable { pumpRelay() }
        relayRunnable = r
        // A small debounce absorbs the rapid tail revisions produced by voice IMEs
        // without making the PC feel delayed.
        main.postDelayed(r, 140L)
    }

    private fun pumpRelay() {
        relayRunnable?.let(main::removeCallbacks)
        relayRunnable = null
        if (relayInFlight || composing) return
        val target = desiredText
        val plan = VoiceRelayDiffEngine.plan(lastRelayedText, target)
        if (plan.isNoOp) return
        val host = hostProvider().trim()
        if (host.isBlank()) {
            lastError = "电脑 IP 为空"
            return
        }

        relayInFlight = true
        api.applyVoiceRelayEdit(host, plan.deleteCodePoints, plan.insertText) { result ->
            relayInFlight = false
            if (result.ok) {
                lastRelayedText = target
                successfulEdits.incrementAndGet()
                backspaces.addAndGet(plan.deleteCodePoints.toLong())
                insertedCodePoints.addAndGet(plan.insertText.codePointCount(0, plan.insertText.length).toLong())
                lastSuccessAt = System.currentTimeMillis()
                lastError = ""
            } else {
                lastError = result.message.ifBlank { "语音文字同步失败" }
                onStatus("语音文字同步失败：${lastError}")
            }
            // The IME may have revised the sentence while the previous LAN request was
            // in flight. A pending Send must win over another debounce so Enter is emitted
            // only after the final visible text has reached Windows.
            if (sendRequested) {
                performSend(d = dialog)
            } else if (desiredText != lastRelayedText && !composing) {
                scheduleRelay()
            }
        }
    }

    private fun requestSend(d: Dialog) {
        relayRunnable?.let(main::removeCallbacks)
        relayRunnable = null
        desiredText = edit?.text?.toString().orEmpty()
        // Tapping Send may happen immediately after an IME final commit. The visible text is
        // authoritative at this point; do not let an old composing flag block submission.
        composing = false
        sendRequested = true
        if (!relayInFlight) performSend(d)
    }

    private fun performSend(d: Dialog?) {
        val currentDialog = d ?: return
        if (!sendRequested || relayInFlight || !currentDialog.isShowing) return
        val host = hostProvider().trim()
        if (host.isBlank()) {
            sendRequested = false
            lastError = "电脑 IP 为空"
            onStatus("请先填写电脑 IP")
            return
        }

        desiredText = edit?.text?.toString().orEmpty()
        val target = desiredText
        val plan = VoiceRelayDiffEngine.plan(lastRelayedText, target)
        relayInFlight = true
        api.applyVoiceRelayEditAndEnter(host, plan.deleteCodePoints, plan.insertText) { result ->
            relayInFlight = false
            if (result.ok) {
                lastRelayedText = target
                successfulEdits.incrementAndGet()
                backspaces.addAndGet(plan.deleteCodePoints.toLong())
                insertedCodePoints.addAndGet(plan.insertText.codePointCount(0, plan.insertText.length).toLong())
                lastSuccessAt = System.currentTimeMillis()
                lastError = ""
                sendRequested = false
                onStatus("已发送")
                currentDialog.dismiss()
            } else {
                sendRequested = false
                lastError = result.message.ifBlank { "语音发送失败" }
                onStatus("语音发送失败：${lastError}")
            }
        }
    }

    private fun installImeVisibilityWatcher(d: Dialog) {
        val decor = d.window?.decorView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.setOnApplyWindowInsetsListener { _, insets ->
                val visible = insets.isVisible(WindowInsets.Type.ime())
                if (visible) imeWasVisible = true
                else if (imeWasVisible && d.isShowing) d.dismiss()
                insets
            }
            decor.requestApplyInsets()
            return
        }

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val frame = Rect()
            decor.getWindowVisibleDisplayFrame(frame)
            val hiddenHeight = decor.rootView.height - frame.bottom
            val visible = hiddenHeight > decor.rootView.height * 0.18f
            if (visible) imeWasVisible = true
            else if (imeWasVisible && d.isShowing) d.dismiss()
        }
        globalLayoutListener = listener
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun removeLegacyImeWatcher(d: Dialog) {
        val listener = globalLayoutListener ?: return
        val observer = d.window?.decorView?.viewTreeObserver
        if (observer?.isAlive == true) observer.removeOnGlobalLayoutListener(listener)
        globalLayoutListener = null
    }

    private fun focusAndShowIme(view: EditText) {
        view.requestFocus()
        view.setSelection(view.text?.length ?: 0)
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme(view: View?) {
        val target = view ?: return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(activity).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        isAllCaps = false
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(Color.rgb(42, 47, 58), Color.rgb(75, 83, 101), 11f)
        setOnClickListener { onClick() }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun fullWidth(height: Int) = LinearLayout.LayoutParams(match(), height)
}
