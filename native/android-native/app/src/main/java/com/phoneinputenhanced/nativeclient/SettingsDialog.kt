package com.phoneinputenhanced.nativeclient

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class SettingsDialog(
    private val activity: Activity,
    private val initial: AppSettings,
    private val onSave: (AppSettings) -> Unit,
) {
    fun show() {
        var sensitivity = initial.sensitivity
        var scrollSpeed = initial.scrollSpeed

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val sensitivityLabel = label("鼠标灵敏度：${"%.2f".format(sensitivity)}")
        root.addView(sensitivityLabel)
        root.addView(SeekBar(activity).apply {
            max = 320
            progress = ((sensitivity - 0.8f) * 100f).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(simpleSeek { progress ->
                sensitivity = 0.8f + progress / 100f
                sensitivityLabel.text = "鼠标灵敏度：${"%.2f".format(sensitivity)}"
            })
        })

        val scrollLabel = label("滚动速度：${"%.1f".format(scrollSpeed)}")
        root.addView(scrollLabel)
        root.addView(SeekBar(activity).apply {
            max = 120
            progress = ((scrollSpeed - 2f) * 10f).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(simpleSeek { progress ->
                scrollSpeed = 2f + progress / 10f
                scrollLabel.text = "滚动速度：${"%.1f".format(scrollSpeed)}"
            })
        })

        val natural = check("自然滚动", initial.naturalScroll)
        val haptic = check("触觉反馈", initial.hapticFeedback)
        root.addView(natural)
        root.addView(haptic)

        root.addView(label("说明：文件传输在“文件”菜单中手动触发；不再后台自动读取手机剪贴板。", 12f).apply {
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, 0)
        })

        AlertDialog.Builder(activity)
            .setTitle("PhoneInputEnhanced 设置")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                onSave(AppSettings(
                    sensitivity = sensitivity,
                    scrollSpeed = scrollSpeed,
                    naturalScroll = natural.isChecked,
                    hapticFeedback = haptic.isChecked,
                ))
            }
            .show()
    }

    private fun label(text: String, size: Float = 14f) = TextView(activity).apply {
        this.text = text
        textSize = size
        gravity = Gravity.START
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun check(text: String, checked: Boolean) = CheckBox(activity).apply {
        this.text = text
        isChecked = checked
        textSize = 14f
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
