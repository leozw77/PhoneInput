package com.phoneinputenhanced.nativeclient

import android.content.Context

data class AppSettings(
    val sensitivity: Float = 2.15f,
    val scrollSpeed: Float = 7f,
    val naturalScroll: Boolean = true,
    val hapticFeedback: Boolean = true,
) {
    fun toGestureConfig(base: GestureConfig = GestureConfig()): GestureConfig = base.copy(
        sensitivity = sensitivity.coerceIn(0.8f, 4.0f),
        scrollSpeed = scrollSpeed.coerceIn(2f, 14f),
        naturalScroll = naturalScroll,
    )

    companion object {
        private const val PREFS = "phoneinput_native"
        private const val K_SENSITIVITY = "setting_sensitivity"
        private const val K_SCROLL_SPEED = "setting_scroll_speed"
        private const val K_NATURAL_SCROLL = "setting_natural_scroll"
        private const val K_HAPTIC = "setting_haptic"

        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                sensitivity = p.getFloat(K_SENSITIVITY, 2.15f),
                scrollSpeed = p.getFloat(K_SCROLL_SPEED, 7f),
                naturalScroll = p.getBoolean(K_NATURAL_SCROLL, true),
                hapticFeedback = p.getBoolean(K_HAPTIC, true),
            )
        }

        fun save(context: Context, value: AppSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(K_SENSITIVITY, value.sensitivity)
                .putFloat(K_SCROLL_SPEED, value.scrollSpeed)
                .putBoolean(K_NATURAL_SCROLL, value.naturalScroll)
                .putBoolean(K_HAPTIC, value.hapticFeedback)
                .apply()
        }
    }
}
