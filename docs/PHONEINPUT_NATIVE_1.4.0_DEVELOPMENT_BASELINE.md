# PhoneInput Native 1.4.0 开发基线

更新时间：2026-08-09
稳定版本：`1.4.0`
来源：`1.4.0-native-preview.10` 功能冻结候选基线

本文档是后续 Native 开发的工作入口。它记录 1.4.0 相比旧版的定位、已经完成的能力、不可随意破坏的约束、构建入口和下一阶段优先级。

## 1. 版本定位

PhoneInput 当前有两条需要明确隔离的产品线：

- **旧稳定线 v1.2.5**：C#/.NET Windows 客户端，保留在 `src/` 和历史 `Release/` 目录中，作为回滚基线。
- **Native 稳定线 1.4.0**：Kotlin Android 原生客户端 + Go Windows Host，位于 `native/`，发布资产位于 `Release/v1.4.0-native/`。

1.4.0 不是覆盖 v1.2.5 的普通补丁，而是新的 Native 主线；旧版 Core、旧 `/ws` 和浏览器入口仍作为兼容回退保留。

权威功能摘要：

- `native/CURRENT_VERSION_SUMMARY.md`
- `native/PhoneInputEnhanced_1.4.0-native-preview.10_CURRENT_VERSION_SUMMARY_2026-08-09.md`（用户提供的原始实现说明，原样保留）

## 2. 相比 v1.2.5 的主要提升

### 2.1 客户端架构

- 手机端从浏览器页面升级为 Kotlin 原生 Android 客户端，不以 WebView 作为主界面。
- Windows 端拆分为 `PhoneInputTouchpadHost.exe`、`PhoneInputEnhanced.exe`、`PhoneInputSendTo.exe`、`PhoneInputImageTray.exe` 和兼容 Core。
- 新增 Native Protocol v2，支持 requestId、ACK、capabilities、稳定错误码、心跳、重连和安全 release；旧 `/ws` 继续兼容。

### 2.2 触控板与鼠标

- 单指点击、双击、移动、左键拖动和双击拖动。
- 双指右键、横向/纵向滚动、双指长按打开原生输入面板。
- 拖动锁定、断线/切后台/多指接入时安全释放按键。
- MotionEvent 历史采样按显示帧合并，大位移拆包，减少 Writer 队列积压和移动距离丢失。

### 2.3 原生文字输入

- 批量输入和即时输入两种模式。
- IME composition 保护，避免拼音候选阶段提前发送。
- 中文、Emoji、Unicode 安全处理。
- 中间插入、替换、删除的最小 diff 同步。
- targetId 锁定、selection 同步、回读防覆盖和 ChatGPT/Chrome/微信窗口切换。
- Backspace、Tab、Esc、方向键等快捷键。

### 2.4 语音

最终稳定方案使用手机当前输入法的语音能力：

```text
手机点击语音
→ 弹出手机输入法
→ 输入法完成语音识别
→ PhoneInputEnhanced 同步尾部文本修订
→ 发送时补齐最后修订并发送 Enter
```

当前 Native 主线不再默认使用：

- Android `SpeechRecognizer`。
- `RECORD_AUDIO` 麦克风权限。
- Windows `Win+H`。
- 本地 Whisper 或云端 ASR。

### 2.5 文件、APK 和图片

- 手机 App 内发送图片/文件。
- Android 系统 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 分享目标。
- 图片和普通文件走独立 HTTP worker，不阻塞鼠标 WebSocket。
- Windows 右键“发送到 PhoneInputEnhanced”，向手机发送 APK 和小文件。
- `PhoneInputImageTray.exe` 显示最近最多 5 张图片，并通过 OLE `CF_HDROP` 把真实文件拖入 ChatGPT/Chrome。

### 2.6 剪贴板与诊断

- 自动双向剪贴板同步已移除，改为明确的手动复制/拉取。
- Android 诊断中心覆盖连接、ACK、心跳、重连、文件和语音中转状态。
- Windows 提供 `/api/diagnostics`。
- 控制会话按 held-button ownership 管理，避免一个连接断开误释放另一个连接的按键。

## 3. 已冻结的稳定性规则

后续开发默认遵守以下规则：

1. 不主动重写已经稳定的鼠标移动、拖动和双指滚动算法。
2. 不删除主触控页的 ChatGPT、Chrome、微信任务窗口按钮。
3. 不恢复自动双向剪贴板同步。
4. 不把 `Win+H`、Android `SpeechRecognizer`、本地 Whisper 或云 ASR重新放回默认语音链路。
5. 不把 CRX/浏览器深度回读塞回 Native 主线；普通回读只能视为 best effort。
6. 文件传输继续与鼠标 WebSocket 隔离。
7. 新功能必须先证明能改善日常使用，否则优先做 Bug 修复和实机兼容。
8. 不覆盖 `Release/v1.2.5*` 和旧 `src/`；新 Native 发布使用新的版本目录。

## 4. 构建入口

### Android

项目：`native/android-native/`
版本：`versionCode 13`、`versionName 1.4.0`
要求：Android SDK 36、JDK 17、Gradle 9.3 缓存或 Android Studio。

构建 Debug 和未签名 Release：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
gradle.bat -p native/android-native assembleDebug assembleRelease --no-daemon
```

正式发布前必须使用正式 keystore 重新签名；`app-release-unsigned.apk` 不能直接当作生产签名包。

### Windows

项目：`native/`
构建脚本：`native/tools/build_release.py`
目标：Windows amd64。

发布脚本会生成四个 Native EXE、兼容 Core、Windows ZIP、Android APK 和校验文件；源码直接由仓库中的 `native/` 提供：

```powershell
python native/tools/build_release.py --output-dir native/artifacts
```

当前记录使用 Go 1.26.5 完成 Windows 交叉编译。Go cache、GOPATH、Gradle user home 和 Android user home 必须指向当前用户可写目录，不能依赖不可写的 `C:\.gradle` 或 `C:\.android`。

`--skip-tests` 只用于当前受限桌面环境无法完成 Windows 输入/热键集成测试的情况；正常稳定发布不能默认跳过测试。

## 5. 当前验证和限制

已验证：

- Windows 四个 Native EXE 交叉编译成功。
- Android Debug APK 构建成功，`versionName=1.4.0`，Debug v2 签名有效。
- Android 未签名 Release APK 构建成功。
- JavaScript 语法检查成功。
- Windows ZIP 含 Host、Launcher、SendTo、ImageTray 和 Core。
- 发布目录 SHA-256 校验清单无错误。
- 用户提供的实现说明已保存在 `native/` 和本仓库的版本说明文档中。

当前不能视为完全实机验收：

- ImageTray 的真实 DPI、多显示器定位和拖入实际 ChatGPT/Chrome 页面。
- Android 真机安装、升级、后台/锁屏/断网恢复。
- 不同百度、搜狗、Gboard 等输入法的最终提交行为。
- Windows Downloads 重定向/OneDrive 场景；当前尚未切换到 `FOLDERID_Downloads`。
- 生产 Release APK 签名。

当前 Windows 受限会话中，`go test ./...` 有 5 个涉及文件上传、系统热键和 Windows 输入权限的集成测试失败；Go 1.26.5 `go vet` 对 Windows OLE/ImageTray 和输入代码报告 `unsafe.Pointer` 告警。两者都不能被误写成“全部测试通过”。

## 6. 后续开发优先级

按以下顺序处理：

1. ImageTray 真实 Windows 验收和拖放问题。
2. Downloads Known Folder 兼容性。
3. Android 真机安装/升级、锁屏、切后台、断网恢复。
4. 常用 Android 输入法兼容性。
5. 正式 Release APK keystore、版本匹配提示和发布说明。
6. 只有出现明确用户需求后，才评估新功能；不继续堆叠 preview 功能。

## 7. 交接前检查清单

- [ ] 确认当前修改位于 `native/`，没有误改 `src/` 或旧 Release。
- [ ] 确认版本号仍为 `1.4.0`，没有把 preview 后缀写回运行时版本。
- [ ] 运行 Go 测试、vet，并记录真实失败原因。
- [ ] 构建 Debug/Release APK，检查版本、签名和大小。
- [ ] 构建 Windows 四个 EXE，检查 ZIP 条目。
- [ ] 检查用户实现说明 MD 是否仍在源码和发布包中。
- [ ] 重新生成并核对 SHA-256。
- [ ] 未完成真实 Windows/Android 验收前，不把版本描述为“所有场景已验证”。
