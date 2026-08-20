package com.phoneinputenhanced.nativeclient

private fun assertTrue(value: Boolean, message: String) {
    if (!value) error(message)
}

private class Probe {
    val outputs = mutableListOf<NativeGestureEngine.Output>()
    val engine = NativeGestureEngine(output = outputs::add)
}

fun main() {
    run {
        val p = Probe()
        p.engine.onSingleDown(100f, 100f, 0)
        p.engine.onSingleUp(100f, 100f, 80)
        assertTrue(p.outputs.contains(NativeGestureEngine.Output.LeftClick), "tap must emit left click")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.LeftDown }, "tap must not press left down")
    }

    run {
        val p = Probe()
        p.engine.onSingleDown(100f, 100f, 0)
        p.engine.onSingleMove(108f, 100f, 100)
        p.engine.onSingleUp(108f, 100f, 110)
        assertTrue(p.outputs.any { it is NativeGestureEngine.Output.MouseMove }, "early 8px motion must move cursor")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.LeftDown }, "early motion must not start drag")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.LeftClick }, "motion must not click")
    }

    run {
        val p = Probe()
        p.engine.onSingleDown(100f, 100f, 0)
        p.engine.onSingleMove(103f, 100f, 225)
        p.engine.onSingleMove(114f, 100f, 240)
        p.engine.onSingleUp(114f, 100f, 260)
        val down = p.outputs.indexOf(NativeGestureEngine.Output.LeftDown)
        val up = p.outputs.indexOf(NativeGestureEngine.Output.LeftUp)
        assertTrue(down >= 0, "press drag must emit left down")
        assertTrue(up > down, "press drag must release on up")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.LeftClick }, "press drag must not click")
    }

    run {
        val p = Probe()
        p.engine.onSingleDown(100f, 100f, 0)
        p.engine.onSingleUp(100f, 100f, 60)
        p.engine.onSingleDown(102f, 101f, 180)
        p.engine.onSingleUp(102f, 101f, 230)
        assertTrue(p.outputs.count { it == NativeGestureEngine.Output.LeftClick } == 2, "double tap must emit two Windows clicks")
        assertTrue(p.outputs.any { it is NativeGestureEngine.Output.Hint && it.text == "已双击" }, "double tap hint missing")
    }

    run {
        val p = Probe()
        p.engine.onSingleDown(100f, 100f, 0)
        p.engine.onSingleUp(100f, 100f, 50)
        p.engine.onSingleDown(101f, 101f, 160)
        p.engine.onSingleMove(104f, 101f, 315)
        p.engine.onSingleMove(114f, 101f, 330)
        p.engine.onSingleUp(114f, 101f, 350)
        assertTrue(p.outputs.count { it == NativeGestureEngine.Output.LeftClick } == 1, "double-tap drag must not emit second click")
        assertTrue(p.outputs.contains(NativeGestureEngine.Output.LeftDown), "double-tap drag must press left")
        assertTrue(p.outputs.contains(NativeGestureEngine.Output.LeftUp), "double-tap drag must release left")
    }

    run {
        val p = Probe()
        p.engine.onTwoFingerDown(100f, 100f, 160f, 100f, 0)
        p.engine.onTwoFingerUp(140)
        assertTrue(p.outputs.count { it == NativeGestureEngine.Output.RightClick } == 1, "two-finger tap must right-click")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.OpenTextInput }, "two-finger tap must not open input")
    }

    run {
        val p = Probe()
        p.engine.onTwoFingerDown(100f, 100f, 160f, 100f, 0)
        p.engine.onTwoFingerHoldTimeout(520)
        p.engine.onTwoFingerUp(700)
        assertTrue(p.outputs.count { it == NativeGestureEngine.Output.OpenTextInput } == 1, "two-finger hold must open input")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.RightClick }, "two-finger hold must not also right-click")
    }

    run {
        val p = Probe()
        p.engine.onTwoFingerDown(100f, 100f, 160f, 100f, 0)
        p.engine.onTwoFingerMove(100f, 122f, 160f, 122f, 100)
        p.engine.onTwoFingerMove(100f, 130f, 160f, 130f, 120)
        p.engine.onTwoFingerHoldTimeout(600)
        p.engine.onTwoFingerUp(650)
        assertTrue(p.outputs.any { it is NativeGestureEngine.Output.Scroll }, "two-finger movement must scroll")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.RightClick }, "scroll must not right-click")
        assertTrue(p.outputs.none { it == NativeGestureEngine.Output.OpenTextInput }, "scroll must not open input")
    }

    run {
        val p = Probe()
        p.engine.toggleDragLock()
        p.engine.onTwoFingerDown(100f, 100f, 160f, 100f, 0)
        assertTrue(!p.engine.isDragLocked, "two-finger gesture must clear drag lock")
        assertTrue(p.outputs.contains(NativeGestureEngine.Output.LeftUp), "two-finger gesture must release locked left")
    }

    run {
        val p = Probe()
        p.engine.suppressForTooManyPointers()
        assertTrue(p.engine.state == NativeGestureEngine.State.Suppressed, "3+ pointers must be suppressed")
    }

    println("NativeGestureEngine preview.6 smoke tests: PASS")
}
