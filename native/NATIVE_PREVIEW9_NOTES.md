# PhoneInputEnhanced 1.4.0-native-preview.9

## 目标

preview.9 将触控板语音入口从 Windows `Win+H` 改为“手机当前输入法语音中转”。触控板主界面的 ChatGPT / Chrome / 微信任务窗口按钮继续保留；浏览器/CRX 回读继续延期，不在本版重构。

## 语音入口

触控板底部仍为：

```text
[ ⌫ 退格 ]   [ 🎤 语音 ]   [ ↵ 回车 ]
```

点击 `🎤 语音` 后，不调用 Android `SpeechRecognizer`，也不发送 `Win+H`。Android 弹出一个极简 `EditText` 并主动显示用户当前输入法。用户可以直接点百度输入法、搜狗、Gboard 等输入法自己的麦克风。

输入法提交到 EditText 的文字会通过现有 Windows 文字注入接口同步到电脑当前光标。输入法对句尾进行修订时，Android 使用公共前缀 + 尾部退格/重写的方式同步，避免整句重复。Emoji/代理对按 Unicode code point 计数。

输入法关闭后，语音中转面板自动关闭。Android 不申请 `RECORD_AUDIO`；麦克风权限和识别能力均由当前输入法自身管理。

## 保留

- 主触控页 ChatGPT / Chrome / 微信三个任务窗口按钮保留。
- 双指长按完整输入面板保留，其中三个窗口按钮也保留。
- `⌫` 短按删除、长按连续删除保留。
- `↵` 直接向 Windows 发送 Enter。
- Windows 系统截图、复制/粘贴、鼠标按键、拖动锁定、文件中转不变。
- Host 的 `hotkey.voice` / `Win+H` 能力暂留作向后兼容，但 preview.9 Android 不再调用。

## 暂缓

- CRX / 浏览器专用回读。
- 本地 Whisper。
- 云端 ASR Provider。

先实测手机输入法语音是否已经满足准确率、标点和操作效率，再决定是否需要云 ASR。
