package com.phoneinputenhanced.nativeclient

/** Computes one minimal replace operation between the projected Windows text and current EditText. */
object RealtimeDiffEngine {
    data class EditPlan(
        val replaceStartUtf16: Int,
        val replaceEndUtf16: Int,
        val insertedText: String,
    ) {
        val removesText: Boolean get() = replaceEndUtf16 > replaceStartUtf16
    }

    fun plan(oldText: String, newText: String): EditPlan? {
        if (oldText == newText) return null

        var prefix = 0
        while (prefix < oldText.length && prefix < newText.length) {
            val oldCp = oldText.codePointAt(prefix)
            val newCp = newText.codePointAt(prefix)
            if (oldCp != newCp) break
            prefix += Character.charCount(oldCp)
        }

        var oldSuffix = oldText.length
        var newSuffix = newText.length
        while (oldSuffix > prefix && newSuffix > prefix) {
            val oldCp = oldText.codePointBefore(oldSuffix)
            val newCp = newText.codePointBefore(newSuffix)
            if (oldCp != newCp) break
            oldSuffix -= Character.charCount(oldCp)
            newSuffix -= Character.charCount(newCp)
        }

        return EditPlan(
            replaceStartUtf16 = prefix,
            replaceEndUtf16 = oldSuffix,
            insertedText = newText.substring(prefix, newSuffix),
        )
    }
}
