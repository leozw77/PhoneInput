package com.phoneinputenhanced.nativeclient

private fun checkPlan(old: String, new: String, prefix: String, deletes: Int, insert: String) {
    val p = VoiceRelayDiffEngine.plan(old, new)
    check(p.commonPrefix == prefix) { "prefix old=$old new=$new got=${p.commonPrefix}" }
    check(p.deleteCodePoints == deletes) { "deletes old=$old new=$new got=${p.deleteCodePoints}" }
    check(p.insertText == insert) { "insert old=$old new=$new got=${p.insertText}" }
}

fun main() {
    checkPlan("", "测试", "", 0, "测试")
    checkPlan("测试", "测试语音", "测试", 0, "语音")
    checkPlan("测试语音", "测试声音", "测试", 2, "声音")
    checkPlan("abc🙂", "abc🙂好", "abc🙂", 0, "好")
    checkPlan("abc🙂好", "abc🙂", "abc🙂", 1, "")
    checkPlan("完全不同", "新的内容", "", 4, "新的内容")
    check(VoiceRelayDiffEngine.plan("一样", "一样").isNoOp)
    println("VoiceRelayDiffEngine smoke: PASS")
}
