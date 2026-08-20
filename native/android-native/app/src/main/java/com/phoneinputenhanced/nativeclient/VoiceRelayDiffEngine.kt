package com.phoneinputenhanced.nativeclient

/**
 * Computes the minimal tail rewrite needed to mirror a phone IME edit into the
 * already-focused Windows input. The relay intentionally assumes the PC caret
 * remains at the end of the text inserted by this voice session.
 *
 * Using code points (rather than UTF-16 code units) keeps emoji/surrogate pairs
 * from turning into two Backspace operations.
 */
object VoiceRelayDiffEngine {
    data class Plan(
        val commonPrefix: String,
        val deleteCodePoints: Int,
        val insertText: String,
    ) {
        val isNoOp: Boolean get() = deleteCodePoints == 0 && insertText.isEmpty()
    }

    fun plan(oldText: String, newText: String): Plan {
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < oldText.length && newIndex < newText.length) {
            val oldCp = oldText.codePointAt(oldIndex)
            val newCp = newText.codePointAt(newIndex)
            if (oldCp != newCp) break
            oldIndex += Character.charCount(oldCp)
            newIndex += Character.charCount(newCp)
        }
        return Plan(
            commonPrefix = oldText.substring(0, oldIndex),
            deleteCodePoints = oldText.codePointCount(oldIndex, oldText.length),
            insertText = newText.substring(newIndex),
        )
    }
}
