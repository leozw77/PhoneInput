package com.phoneinputenhanced.nativeclient

import android.content.Context
import android.os.SystemClock
import android.text.Editable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * EditText with selection sync plus a robust IME submit bridge.
 *
 * Android IMEs do not all report the enter/send key the same way. Depending on the keyboard,
 * submit may arrive as performEditorAction(), a KEYCODE_ENTER event, or commitText("\\n").
 * We normalize all three forms into one callback and debounce duplicates.
 *
 * IMPORTANT: while an IME composing span is active (for example Chinese pinyin selection),
 * enter remains owned by the IME and is NOT treated as a Windows submit action.
 */
class RealtimeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {
    var onSelectionChangedListener: ((Int, Int) -> Unit)? = null
    var onImeSubmit: (() -> Boolean)? = null

    private var lastImeSubmitAt = 0L
    private var consumeEnterKeyUp = false

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedListener?.invoke(selStart, selEnd)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null

        // Multiline TextView normally adds IME_FLAG_NO_ENTER_ACTION. Remove it so keyboards are
        // allowed to expose their Send/Enter action instead of silently inserting a local newline.
        outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
        outAttrs.imeOptions = (outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.actionLabel = "发送"
        outAttrs.actionId = EditorInfo.IME_ACTION_SEND

        return object : InputConnectionWrapper(base, true) {
            override fun performEditorAction(editorAction: Int): Boolean {
                if (editorAction == EditorInfo.IME_ACTION_SEND ||
                    editorAction == EditorInfo.IME_ACTION_DONE ||
                    editorAction == EditorInfo.IME_ACTION_GO ||
                    editorAction == EditorInfo.IME_ACTION_UNSPECIFIED
                ) {
                    if (dispatchImeSubmit()) return true
                }
                return super.performEditorAction(editorAction)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        val handled = dispatchImeSubmit()
                        if (handled) {
                            consumeEnterKeyUp = true
                            return true
                        }
                    } else if (event.action == KeyEvent.ACTION_UP && consumeEnterKeyUp) {
                        consumeEnterKeyUp = false
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val submitted = text?.toString()
                if ((submitted == "\n" || submitted == "\r\n") && dispatchImeSubmit()) {
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && event.repeatCount == 0 && dispatchImeSubmit()) {
            consumeEnterKeyUp = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && consumeEnterKeyUp) {
            consumeEnterKeyUp = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun dispatchImeSubmit(): Boolean {
        if (hasActiveComposition()) return false
        val callback = onImeSubmit ?: return false

        // Some keyboards emit editorAction + keyEvent, or keyEvent + commitText("\\n") for one tap.
        // Consume duplicates so Windows receives exactly one Enter.
        val now = SystemClock.uptimeMillis()
        if (now - lastImeSubmitAt < IME_SUBMIT_DEBOUNCE_MS) return true

        val handled = callback()
        if (handled) lastImeSubmitAt = now
        return handled
    }

    private fun hasActiveComposition(): Boolean {
        val value: Editable = text ?: return false
        return BaseInputConnection.getComposingSpanStart(value) >= 0 ||
            BaseInputConnection.getComposingSpanEnd(value) >= 0
    }

    companion object {
        private const val IME_SUBMIT_DEBOUNCE_MS = 320L
    }
}
