# PhoneInput Native 1.4.0 发布说明

日期：2026-08-09
状态：Native 稳定基线；仍有明确的实机验收和功能缺口

## 版本组成

当前最新版不是单一的旧浏览器页面，而是两端组合：

1. Windows Native Host：Go 编写，负责局域网服务、鼠标/键盘、窗口切换、文件和图片中转。
2. Android Native Client：Kotlin 原生客户端，不以 WebView 作为主界面。
3. Legacy Core/browser：旧 C#/.NET 和浏览器入口继续保留，用于兼容和回滚，不作为 1.4.0 Native 主线。

## 已实现功能

### Windows Native

- Native Protocol v2：hello、版本、requestId、ACK、capabilities、错误码、心跳、重连和安全 release。
- `PhoneInputTouchpadHost.exe`：单指移动/点击/双击/左键拖动/双击拖动，双指滚动和右键。
- `PhoneInputEnhanced.exe`：局域网输入、批量文字、快捷键、窗口切换、截图、复制/粘贴、诊断和旧协议兼容。
- `PhoneInputSendTo.exe`：Windows“发送到 PhoneInputEnhanced”，把文件或 APK 发送到手机。
- `PhoneInputImageTray.exe`：接收手机图片后显示最多 5 张缩略图，支持打开和向支持文件拖放的程序拖入真实图片文件。
- 文件和图片使用独立 HTTP worker，不阻塞鼠标 WebSocket。
- 手机输入法语音中转：Windows 端不负责录音或云端识别。

### Android Native

- 原生 Kotlin 工程位于 `native/android-native/`。
- 单指移动、轻触左键、双击、左键拖动和拖动锁定。
- 双指轻触右键、双指横向/纵向滚动、双指长按打开文字输入面板。
- 批量输入和即时输入；支持中文 IME composition、Unicode、Emoji、最小 diff、插入/替换/删除和 selection 同步。
- `发送`、`发送并回车`、Backspace、Tab、Esc、方向键和手动同步。
- 手机输入法语音中转，保留最后一次提交修订后再发送 Enter。
- 图片/文件选择、Android `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 分享目标。
- 连接心跳、自动重连、生命周期 release、诊断中心和网络恢复监测。

## 未完成、限制和验证边界

### 回读功能

**回读功能仍然很不完善。** 当前只能针对部分可访问的 Windows 输入控件做 best effort 读取；目标程序、富文本控件、浏览器页面结构和输入法行为都会影响结果。Chrome/浏览器页面的完整自动回读、CRX 深度回读和复杂富文本回读仍然延期，不能视为已完成能力。

### 仍需实机验证

- Android 真机安装、升级、锁屏、切后台、断网恢复。
- 百度、搜狗、Gboard 等不同输入法的最终提交和语音中转行为。
- ImageTray 在不同 Windows DPI、多显示器和实际 ChatGPT/Chrome 窗口中的定位、拖放。
- Downloads 被 OneDrive 或其他方式重定向时的 Known Folder 兼容性。

### 构建和发布限制

- `PhoneInputEnhanced_1.4.0-release-unsigned.apk` 未使用正式 keystore 签名，不能直接作为正式生产 APK。
- 受限构建环境中，Windows 文件上传、系统热键和输入权限相关集成测试有失败；Go Windows 代码还有 `unsafe.Pointer` vet 告警。四个 Native EXE 的交叉编译成功，不等于所有 Windows/Android 场景已验收。
- 当前发布目录中的 Windows ZIP、Debug APK、未签名 Release APK和校验文件只代表本次 1.4.0 基线，不能替代用户设备上的端到端回归。

## 交付目录

- Windows 发布包：`Release/v1.4.0-native/PhoneInputEnhanced_1.4.0_Windows-x64_2026-08-09.zip`
- Android Debug APK：`Release/v1.4.0-native/PhoneInputEnhanced_1.4.0-debug.apk`
- Android 未签名 Release APK：`Release/v1.4.0-native/PhoneInputEnhanced_1.4.0-release-unsigned.apk`
- Android 源码：`native/android-native/`
- Windows Native 源码：`native/`
- 历史 C#/.NET 兼容线：`src/`
