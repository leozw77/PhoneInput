package com.phoneinputenhanced.nativeclient

private fun checkEq(actual: Any?, expected: Any?, label: String) {
    check(actual == expected) { "$label: expected=$expected actual=$actual" }
}

fun main() {
    val insert = RealtimeDiffEngine.plan("电脑已有", "电脑已有文字")!!
    checkEq(insert.replaceStartUtf16, 4, "append start")
    checkEq(insert.replaceEndUtf16, 4, "append end")
    checkEq(insert.insertedText, "文字", "append text")

    val replace = RealtimeDiffEngine.plan("hello world", "hello 手机")!!
    checkEq(replace.replaceStartUtf16, 6, "replace start")
    checkEq(replace.replaceEndUtf16, 11, "replace end")
    checkEq(replace.insertedText, "手机", "replace text")

    val emoji = RealtimeDiffEngine.plan("A🙂B", "A🙂中文B")!!
    checkEq(emoji.replaceStartUtf16, 3, "emoji utf16 boundary")
    checkEq(emoji.insertedText, "中文", "emoji insert")

    val frames = mutableListOf<() -> Unit>()
    val moves = mutableListOf<Pair<Int, Int>>()
    val scrolls = mutableListOf<Pair<Int, Int>>()
    val batcher = MotionFrameBatcher(
        scheduleFrame = { frames += it },
        moveSink = { x, y -> moves += x to y },
        scrollSink = { x, y -> scrolls += x to y },
    )
    repeat(20) { batcher.addMove(3, -2) }
    checkEq(frames.size, 1, "one frame scheduled")
    checkEq(moves.size, 0, "not flushed early")
    frames.removeAt(0).invoke()
    checkEq(moves, listOf(60 to -40), "move samples coalesced")

    batcher.addMove(700, 0)
    frames.removeAt(0).invoke()
    checkEq(moves.takeLast(2), listOf(480 to 0, 220 to 0), "large move preserved without server clamp loss")

    repeat(10) { batcher.addScroll(0, 50) }
    checkEq(frames.size, 1, "scroll frame scheduled")
    frames.removeAt(0).invoke()
    checkEq(scrolls, listOf(0 to 500), "scroll coalesced")

    checkEq(TextIndex.caretSteps("A🙂B", 3), 2, "emoji caret steps")
    println("Native preview.6 smoke PASS")
}
