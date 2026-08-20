package com.phoneinputenhanced.nativeclient

import java.text.BreakIterator
import java.util.Locale

/** Converts Android UTF-16 caret offsets to character/grapheme steps expected by the Windows selection API. */
object TextIndex {
    fun caretSteps(value: String, utf16Offset: Int): Int {
        val safe = utf16Offset.coerceIn(0, value.length)
        if (safe == 0) return 0
        val prefix = value.substring(0, safe)
        return try {
            val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
            iterator.setText(prefix)
            var count = 0
            var boundary = iterator.first()
            while (boundary != BreakIterator.DONE) {
                val next = iterator.next()
                if (next == BreakIterator.DONE) break
                count++
                boundary = next
            }
            count
        } catch (_: Exception) {
            prefix.codePointCount(0, prefix.length)
        }
    }
}
