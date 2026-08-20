# PhoneInputEnhanced 当前版本总结与冻结基线

**版本：** `1.4.0-native-preview.10`
**日期：** 2026-08-09
**状态：** 功能冻结候选基线（Stable Baseline Candidate）

> 本文档作为当前 Native 版本的权威交接摘要。后续开发默认以本版本为基线：优先修复实机 Bug、兼容性与发布打包问题，不再主动改动已经稳定的鼠标/手势核心，也不继续堆叠非必要功能。

---

## 1. 当前产品定位

PhoneInputEnhanced 是一套局域网手机控制 Windows 的轻量工具，当前由三部分组成：

1. **Android 原生客户端**：Kotlin 原生界面，不使用 WebView 作为主客户端。
2. **Windows Host / Core**：负责鼠标、键盘、窗口切换、文字输入、剪贴板、文件中转等系统能力。
3. **浏览器旧版兼容入口**：继续保留，作为回退方案，不再作为 Native 主线。

当前主目标已经从“继续增加功能”转为“日常稳定使用 + 发现问题再修”。

---

## 2. Android 主触控板已实现功能

### 2.1 鼠标与触控手势

Native 触控板目前已经实现：

- 单指移动鼠标。
- 单指轻触 = 左键单击。
- 双击 = Windows 双击。
- 单指稳定按住约 220ms 后移动 = 左键拖动。
- 双击第二次稳定按住约 150ms 后移动 = 双击拖动。
- 双指轻触 = 右键。
- 双指移动 = 横向/纵向滚动。
- 双指保持约 520ms = 打开原生文字输入面板。
- 拖动锁定：锁定后滑动持续保持 Windows 左键，解锁/断线/切后台等状态会安全释放。
- 独立左键 / 中键 / 右键按钮。

### 2.2 鼠标流畅度优化

已完成并冻结的关键优化：

- `MotionEvent` 历史采样按显示帧聚合。
- 高频鼠标移动减少 WebSocket Writer 队列堆积。
- 大位移自动拆包，避免裁剪导致距离丢失。
- 拖动结束前先发送最后一段 MOVE，再发送 LEFT_UP。
- 连接断开、生命周期变化时主动释放鼠标按键，避免卡住。
- Host 侧按控制会话追踪 held-button ownership，减少多连接互相误释放。

**当前策略：鼠标/手势核心冻结。除非出现明确 Bug，不再继续调整手感算法。**

---

## 3. 主界面快捷控制

主触控页保留并明确冻结以下任务窗口按钮：

- `ChatGPT`
- `Chrome`
- `微信`

这些按钮不会因为输入框中也存在窗口切换而删除。

触控板底部语音操作区：

- `⌫ 退格`
  - 短按删除一次。
  - 长按约 360ms 后连续退格，松手停止。
- `🎤 语音`
  - 打开手机当前输入法的极简语音中转框。
- `↵ 回车`
  - 向 Windows 当前目标发送 Enter。

主触控板下方快捷栏：

- `截图`：触发 **Windows 系统截图快捷能力**，不是手机截图预览。
- `复制`：Windows `Ctrl+C`。
- `粘贴`：Windows `Ctrl+V`。

---

## 4. 原生文字输入面板

双指长按打开 Android 原生输入面板。

### 4.1 两种输入模式

#### 批量输入

- 中文、英文、Emoji、多行文本。
- `发送`。
- `发送并回车`。
- 文字成功后再发送 Enter，避免文字与 Enter 顺序错乱。

#### 即时输入

- Android IME composition 保护：拼音/候选组合阶段不发送，确认上屏后再同步。
- 最小 diff 同步：支持追加、中间插入、替换、删除。
- Unicode / Emoji 安全处理。
- targetId 锁定，电脑目标窗口变化时暂停发送，防止文字发错窗口。
- selection 同步。
- 自动/手动从电脑回读当前输入内容（能力受目标应用限制）。
- 快捷键折叠到“快捷键”菜单：Backspace、Tab、Esc、方向键、清空手机输入框。

### 4.2 输入面板窗口切换

输入面板内继续保留：

- ChatGPT
- Chrome
- 微信

与主触控页的三个任务窗口按钮并存。

---

## 5. 当前语音输入方案

### 5.1 已废弃方案

以下方案不再作为当前主线：

- Android `SpeechRecognizer`。
- Android `RECORD_AUDIO` 权限。
- Windows `Win+H` 作为默认语音入口。
- 本地 Whisper。
- 第三方云 ASR API。

Windows Host 仍可保留旧 `hotkey.voice` 能力用于兼容旧客户端，但 preview.10 Android 不再调用。

### 5.2 当前正式使用方式：手机输入法语音中转

流程：

```text
手机点“🎤 语音”
→ 打开极简 RealtimeEditText 中转框
→ 弹出手机当前输入法
→ 使用百度 / 搜狗 / Gboard 等输入法自己的语音
→ 输入法提交文字
→ PhoneInputEnhanced 做尾部 diff
→ 实时同步到 Windows 当前光标
```

PhoneInputEnhanced 本身不录音、不识别语音，因此：

- 不申请麦克风权限。
- 识别准确率和标点能力由当前手机输入法决定。
- 避免 Windows `Win+H` 的悬浮小窗口。
- 不额外占用 Windows Whisper 模型内存。

### 5.3 preview.9-buildfix1 发送修复

语音中转框已经增加：

- `发送`
- `关闭`

“发送”动作会：

1. 等待当前语音文字同步完成。
2. 补齐输入法最后一次提交/修订。
3. 再向 Windows 发送一次 Enter。
4. 关闭语音中转框。

输入法自己的 `IME_ACTION_SEND / Enter / commitText("\\n")` 也尽量统一走发送逻辑，避免普通多行 EditText 只换行、不发送的问题。

---

## 6. 手动剪贴板

自动双向剪贴板同步已经正式放弃，不再作为当前功能。

当前保留明确的手动操作：

- 手机剪贴板 → Windows 剪贴板。
- Windows 剪贴板 → 手机剪贴板。
- 主触控页 `复制 / 粘贴` 仍直接操作 Windows 当前前台应用。

这样可以避免 Android 后台剪贴板限制和自动同步回环问题。

---

## 7. 手机 → Windows 文件/截图

Android 支持：

- App 内“发图片到电脑”。
- App 内“发文件到电脑”。
- Android 系统 `ACTION_SEND / ACTION_SEND_MULTIPLE` 分享目标。
- 手机正常系统截图后，可从系统分享面板直接选择 PhoneInputEnhanced。

传输特点：

- 文件字节走独立 HTTP worker。
- 不进入鼠标 WebSocket Writer 队列。
- 图片默认保存到 Windows：`Downloads\\PhoneInputEnhanced\\Images`。
- 普通文件默认保存到：`Downloads\\PhoneInputEnhanced\\Files`。

当前仍有一个已知兼容性项：Windows 下载目录目前按当前实现计算，尚未改为 `FOLDERID_Downloads` Known Folder，因此对被重定向/OneDrive 接管的 Downloads 需要后续修复。

---

## 8. Windows → 手机文件

Windows 包含：

- `PhoneInputSendTo.exe`

Launcher 可注册资源管理器：

```text
发送到 → PhoneInputEnhanced
```

流程：

```text
Windows 文件
→ 右键“发送到 PhoneInputEnhanced”
→ Host 暂存并生成 ID + Token
→ Android 前台连接时轮询待收文件
→ HTTP 下载
→ 保存到 Download/PhoneInputEnhanced
```

APK 文件收到后可弹出安装入口；首次安装未知来源 APK 仍由 Android 系统要求用户授权。

当前定位是“小文件 / APK 轻量中转”，不做 LocalSend 式重型文件管理器。

---

## 9. preview.10：Windows 图片中转栏

preview.10 最后一个新增主功能是：

**手机截图/图片 → Windows 底部图片中转栏 → 直接拖入 ChatGPT / Chrome 等目标。**

新增程序：

- `PhoneInputImageTray.exe`

### 9.1 当前行为

- 图片上传成功后 Host 异步启动或通知 ImageTray。
- 单实例运行。
- 位于 Windows 屏幕底部中央、任务栏上方。
- 新图片到达时显示，但尽量不抢当前焦点。
- 最新图片排最前。
- 最多保留最近 5 张缩略图。
- 单击缩略图：使用 Windows 默认程序打开真实文件。
- `×`：仅隐藏中转栏，不删除图片；下一张图片到达时重新显示。

### 9.2 拖入 ChatGPT

拖动缩略图时：

- 使用标准 Windows OLE `IDataObject / IDropSource`。
- 暴露 `CF_HDROP` / `TYMED_HGLOBAL`。
- 交给目标程序的是磁盘上的真实 PNG/JPEG 文件路径，而不是把缩略图字节复制到拖拽载荷。

设计目标等同于：

> 从 Windows 资源管理器把真实图片文件拖进 ChatGPT。

图片中转栏是独立辅助进程，不与鼠标控制 WebSocket 共用高频线程。

---

## 10. Windows 组件组成

当前 Windows x64 包主要包含：

- `PhoneInputEnhanced.exe` — 启动器。
- `PhoneInputTouchpadHost.exe` — LAN Host / Native Protocol / HTTP 功能入口。
- `Core/PhoneInputEnhanced.exe` — 既有 Windows 输入核心。
- `PhoneInputSendTo.exe` — Windows → 手机文件“发送到”辅助程序。
- `PhoneInputImageTray.exe` — 图片中转栏。

Host 默认 Native WebSocket：

```text
ws://<PC>:51877/v2/ws
```

旧浏览器 `/ws` 继续兼容。

---

## 11. Protocol v2 与连接安全释放

Protocol v2 已包含：

- hello / protocol version。
- requestId。
- ACK。
- capabilities。
- 稳定错误码。
- move / scroll / button / click / release / ping。
- copy / paste hotkey。
- key。
- ChatGPT / Chrome / 微信 window_switch。

Android 连接侧具有：

- 后台 Writer 线程，避免 NetworkOnMainThreadException。
- heartbeat。
- 重连。
- 网络生命周期监测。
- App 切后台/断连时安全 release。

Host v2 当前使用约 45 秒 read deadline，Android 心跳约 15 秒。

---

## 12. 当前诊断能力

Android 诊断中心目前覆盖：

- Android/客户端版本。
- Host IP / 连接状态。
- Server version / protocol。
- 重连次数。
- Writer queue / ACK / heartbeat 等网络状态。
- Core availability。
- 当前前台窗口。
- 文件上传/下载状态、数量、字节数、最后传输、最后错误。
- 语音中转 session / edit / backspace / 最近错误等信息。

Windows Host 也提供 `/api/diagnostics`。

---

## 13. 浏览器兼容与回读状态

### 继续保留

- 旧浏览器触控板入口。
- 旧 `/ws`。
- 旧输入页兼容。
- `/core-api/*` 白名单桥接。

### 明确延期

浏览器/CRX 深度回读继续延期。

原因：

- 浏览器复杂网页的当前输入框内容无法仅靠通用 UIA 保证稳定回读。
- 当前没有找到足够干净、维护成本可接受的方案。
- 不为了回读重写已经稳定的 Native 输入/鼠标架构。

普通 Native 即时输入中的回读仍作为 best effort 使用，不能视为所有网页/控件的绝对权威能力。

---

## 14. 当前明确不继续开发的内容

除非后续真实使用出现强需求，以下功能冻结/延期：

- CRX 深度回读。
- 自动双向剪贴板同步。
- Android SpeechRecognizer。
- Windows `Win+H` 默认语音方案。
- 本地 Whisper。
- 腾讯/讯飞/OpenAI 等云 ASR。
- 新的自动发现电脑逻辑。
- 离线热点复杂模式。
- 重型文件管理器。
- 断点续传/同步目录/传输历史数据库。
- 新增复杂多指手势。

---

## 15. 当前已知问题 / 尚需实机验收

### P0：preview.10 图片中转栏真实 Windows 验收

当前构建环境不是 Windows 桌面环境，因此以下能力虽然已完成代码和 Windows 交叉编译，但仍必须实机确认：

- Windows 真实 DPI / 多显示器下的底部中转栏位置。
- PNG/JPEG 缩略图真实渲染。
- 手机截图分享后是否立即自动出现。
- 用手机触控板按住缩略图拖入实际 ChatGPT/Chrome 页面是否成功。

### P1：文件下载目录 Known Folder

当前 Windows 手机→PC 保存目录尚未切换到系统真正的 Downloads Known Folder；如果用户将“下载”重定向到其他盘或 OneDrive，路径可能不符合系统实际下载目录。

### P1：不同 Android 输入法差异

百度、搜狗、Gboard 等 IME 对 composition、Enter、最终文本修订的行为并不完全一致。当前语音方案已经针对尾部修订和发送顺序处理，但仍需以实际常用输入法为准。

### P2：Android APK 构建验证

当前 Linux 构建环境没有 Android SDK/android.jar，因此：

- Android 纯 Kotlin smoke 可以运行。
- **不能声明这里已经真实 assemble 出 APK。**
- APK 仍需在完整 Android SDK 环境完成 assemble/install/upgrade 实机验证。

---

## 16. 当前自动化验证状态

截至 preview.10，当前包记录的验证状态：

- `go test ./...` — PASS。
- `go vet ./...` — PASS。
- Windows amd64 交叉编译 — PASS：
  - `PhoneInputTouchpadHost.exe`
  - `PhoneInputEnhanced.exe`
  - `PhoneInputSendTo.exe`
  - `PhoneInputImageTray.exe`
- 浏览器 JavaScript syntax / smoke — PASS。
- `VoiceRelayDiffEngineSmoke.kt` — PASS。
- `NativeGestureEngineSmoke.kt` — PASS（仅保留已有 unused-parameter compiler warning）。

**注意：自动化通过不等于 Windows ImageTray 实机拖放已经验收。**

---

## 17. 稳定基线规则

从 `1.4.0-native-preview.10` 开始，默认执行以下开发纪律：

1. 不主动改鼠标移动/拖动/双指滚动算法。
2. 不删除主触控页 ChatGPT / Chrome / 微信任务窗口按钮。
3. 不重新启用自动剪贴板同步。
4. 不重新切回 Windows `Win+H` 或 Android SpeechRecognizer 作为默认语音方案。
5. 不把 CRX/浏览器深度回读重新塞回主线，除非先有独立验证方案。
6. 文件传输继续与鼠标 WebSocket 隔离。
7. 新功能必须证明能明显改善日常使用，否则优先拒绝增加。
8. 后续版本优先：Bug 修复 → 实机兼容 → Release 签名/打包 → 正式版本。

---

## 18. 建议下一阶段

如果 preview.10 图片中转栏实机验收通过，建议不再继续做 `preview.11` 功能版，而转入：

```text
1.4.0-rc.1
```

RC 阶段仅处理：

- ImageTray 实机问题。
- Wi-Fi/锁屏/后台/Host 重启等异常恢复。
- Windows Downloads Known Folder。
- Android release APK / 签名 / 升级安装。
- Windows / Android 版本匹配提示。
- 正式图标、README、清理历史文档。

不再主动增加新的产品功能。

---

## 19. 当前典型日常工作流

### 触控 + 文字

```text
手机 Native 触控板
→ 控制 Windows 鼠标
→ 双指长按打开输入面板
→ 手机输入法输入
→ 即时/批量发送到电脑
```

### 手机语音输入电脑

```text
手机点 🎤
→ 当前手机输入法
→ 百度/其他输入法语音
→ PhoneInputEnhanced 尾部 diff 中转
→ Windows 当前光标
→ 点“发送”完成最后同步 + Enter
```

### 手机截图用于 ChatGPT

```text
手机系统截图
→ 分享到 PhoneInputEnhanced
→ Windows Images 文件夹
→ PhoneInputImageTray 底部缩略图
→ 拖进 ChatGPT
```

### Windows APK 发到手机

```text
资源管理器右键 APK
→ 发送到 PhoneInputEnhanced
→ Android Download/PhoneInputEnhanced
→ 打开安装界面
```

---

# 结论

`1.4.0-native-preview.10` 已经覆盖当前项目最核心的日常需求：

**触控板、鼠标手势、窗口切换、文字输入、手机输入法语音、截图/复制/粘贴、手动剪贴板、双向小文件传输，以及手机截图到 Windows 图片中转栏再拖入 ChatGPT 的工作流。**

因此本版本正式作为当前 Native 主线的**功能冻结候选基线**。后续默认以稳定性和正式发布为目标，而不是继续扩展功能范围。
