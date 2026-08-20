package com.phoneinputenhanced.nativeclient

data class GestureConfig(
    val sensitivity: Float = 2.15f,
    val maxDeltaPerSample: Int = 480,
    val tapMoveTolerancePx: Float = 7f,
    val dragStartDistancePx: Float = 12f,
    val pressDragArmMs: Long = 220L,
    val doubleTapDragArmMs: Long = 150L,
    val doubleTapMaxIntervalMs: Long = 300L,
    val doubleTapMaxDistancePx: Float = 36f,
    val twoFingerTapMaxMs: Long = 320L,
    val twoFingerTapMoveTolerancePx: Float = 12f,
    val twoFingerScrollStartDistancePx: Float = 18f,
    val twoFingerHoldMs: Long = 520L,
    val twoFingerHoldMoveTolerancePx: Float = 22f,
    val scrollSpeed: Float = 7f,
    val naturalScroll: Boolean = true,
)
