## 1.4.0 - 2026-08-09

- Stable release promoted from the frozen `1.4.0-native-preview.10` baseline.

## 1.4.0-native-preview.10 - 2026-08-09

- 新增 Windows `PhoneInputImageTray.exe` 图片中转栏。
- 手机截图/图片上传成功后自动显示最近图片，最多 5 张。
- 缩略图通过标准 OLE `CF_HDROP` 作为真实文件拖拽，可用于 ChatGPT / Chrome 等文件拖放目标。
- 单击缩略图打开原图；× 隐藏，下一张图片到达时自动重新显示。
- 图片中转栏使用独立辅助进程，不阻塞 WebSocket 鼠标控制或文件上传。
- 保留 preview.9-buildfix1 语音“发送”修复；主触控板任务窗口按钮不变；CRX 回读继续延期。

## 1.4.0-native-preview.9 - 2026-08-09

- 触控板 `🎤 语音` 不再触发 Windows `Win+H`；改为弹出极简 Android IME 中转框。
- 可直接使用百度/搜狗/Gboard 等当前手机输入法自己的语音识别和标点能力。
- 新增 `VoiceRelayDiffEngine`：输入法修订句尾时使用 Unicode 安全的尾部退格 + 重写同步，避免重复整句。
- 输入法关闭后语音中转框自动关闭；PhoneInputEnhanced 本身仍不申请麦克风权限。
- `⌫ / 🎤 / ↵` 三键保留；主触控页 ChatGPT / Chrome / 微信任务窗口按钮明确保留。
- Windows `hotkey.voice` 保留兼容，但 preview.9 Android 不再调用。
- CRX/浏览器回读、本地 Whisper、云 ASR 继续延期。

## 1.4.0-native-preview.8 - 2026-08-09

- Replaced Android SpeechRecognizer voice input with Windows `Win+H` voice typing.
- Removed Android microphone permission and recognition-service query.
- Touchpad voice strip is now Backspace / Voice / Enter.
- Added hold-to-repeat Backspace.
- Added Protocol v2 + Host `hotkey.voice` capability.
- Added voice hotkey diagnostics (count / age / ACK).
- Kept preview.7 input-panel window switches and preview.6 file-transfer behavior.

## 1.4.0-native-preview.7 - 2026-08-09

- Added ChatGPT / Chrome / 微信 window-switch buttons inside the Android native input dialog.
- Added touchpad-overlay `语音` and adjacent `回车` controls.
- Voice input uses one-shot Android `SpeechRecognizer`; only final text is sent to the existing Windows `/api/text` path.
- Added runtime microphone permission handling and Android speech-recognition service query declaration.
- Realtime input window switching clears the stale target lock before reacquiring the new target.
- Existing Windows system screenshot, file transfer, mouse/gesture, browser fallback and diagnostics behavior remain intact.

## 1.4.0-native-preview.6 - 2026-08-09

- Android input popup slimmed to one primary action row; secondary realtime keys moved into a compact shortcut dialog.
- Restored Android `截图` button to trigger the Windows system screenshot shortcut; removed Android PC-screenshot preview workflow.
- Added Android Share Target and explicit image/file pickers for phone -> Windows LAN transfer.
- Added Windows `PhoneInputSendTo.exe` and Explorer `Send to -> PhoneInputEnhanced` for APK/small-file delivery to Android.
- File bytes use independent HTTP workers and do not enter the touchpad WebSocket writer queue.
- Removed unreliable automatic Android/Windows clipboard mirroring; added explicit send/pull text clipboard actions.
- Transfer diagnostics added to Android and Windows Host diagnostics.

# Changelog

## 1.4.0-native-preview.5 - 2026-08-09

- Added text-only Android <-> Windows clipboard synchronization while Android is foregrounded.
- Added native Android screenshot preview using a Windows PNG capture endpoint.
- Added current foreground-window status and ChatGPT/Chrome/WeChat button highlighting.
- Added persisted pointer/scroll settings and optional light haptic feedback.
- Added a diagnostics center with WebSocket queue/ACK/heartbeat/reconnect/error metrics and Windows Host/Core health.
- Added LAN lifecycle monitoring and immediate reconnect after network return.
- Hardened Windows held-button state with per-control-session ownership so one disconnect cannot release another live session's hold.
- Reduced stale-session window with 15 s Android heartbeat and 45 s Host read deadline.
- Added ADB lifecycle stress tooling and a repeatable pressure-test matrix.
- Preserved preview.4 frame batching, realtime input/readback, and BuildFix1 IME Enter behavior.
- Automatic discovery/new auto-connect flow, offline-hotspot wizard, and CRX readback remain intentionally deferred.

## 1.4.0-native-preview.4 - 2026-08-09

- 修复 Android 原生触控时鼠标顿卡/追手问题：`MotionEvent` 历史采样改为按显示帧合并后发送，显著减少 WebSocket Writer 队列积压。
- 合并后的相对位移完整保留；超过 Protocol v2 单包限制时自动拆块，不以裁剪方式丢失移动距离。
- 拖动结束前强制先刷新最后一段鼠标位移，再发送 `LEFT_UP`，保证拖动顺序。
- 原生输入面板新增“批量输入 / 即时输入”模式并持久化。
- 即时输入复用现有 Windows `/core-api/status`、`input-state`、`text`、`selection`、`key` 链路，不重写 Windows 输入核心。
- 新增 targetId 锁定；电脑窗口变化时暂停即时注入，防止文字发送到错误窗口。
- 新增 Android IME composition 保护：拼音/中文候选组合阶段不发送，确认上屏后只发送最终提交内容。
- 新增中间插入、替换、删除的最小 diff 同步，并保持 selection 顺序。
- 新增自动输入回读、手动“从电脑同步”、selection 同步以及延迟回读防覆盖本地新输入。
- 新增即时输入 Backspace / Enter / Tab / Esc / 方向键。
- 新增 `RealtimeDiffEngine` / `MotionFrameBatcher` 纯 Kotlin 烟测。

## 1.4.0-native-preview.3 - 2026-08-09

- 新增双指轻触右键、双指横向/纵向滚动。
- 新增双指保持约 520ms 打开 Android 原生输入面板；右键/滚动/长按三种结果互斥。
- 原生输入面板先接入稳定批量文字发送，支持中文、emoji、多行与发送并回车。
- 保持 Windows Precision Touchpad 风格，不默认增加单指 620ms 右键。

## 1.4.0-native-preview.2 - 2026-08-08

- Android Native 触控板加入轻触左键与系统双击语义。
- 新增单指稳定按住约 220ms 后移动的左键拖动；准备阶段 7px 容差、12px 启动门槛。
- 新增双击第二次稳定按住约 150ms 后移动的拖动，保持与网页 preview.9 的防误拖参数一致。
- 新增拖动锁定按钮；锁定后滑动持续保持 Windows 左键，解锁/断线/切后台/多指接入均释放。
- `NativeGestureEngine` 扩展为 TapCandidate / SingleMove / PressDrag / DoubleTapDrag / DragLocked / Suppressed 状态机。
- Android 网络层补充 Protocol v2 `button {button,down}` 发送接口；Windows v2 协议无需破坏性修改。
- 新增纯 Kotlin 手势烟测，覆盖点击、移动、两类拖动、锁定与安全释放。
- preview.1 BuildFix1 的后台 Writer 线程修复继续保留。

## 1.4.0-native-preview.1 - 2026-08-08

- 新增独立 `/v2/ws` Native Protocol v2，旧 `/ws` 保持兼容。
- v2 增加 hello、protocol version、requestId、ACK、capabilities 和错误码。
- 新增 Android Kotlin 原生客户端工程，不使用 WebView。
- Native preview.1 实现单指移动、左右中键、截图、复制粘贴、ChatGPT/Chrome/微信切换。
- Android 网络层增加 heartbeat、自动重连和生命周期 release。
- 增加 Protocol v2 与 Native preview.1 回归测试。

## v1.3.0-preview.9 (2026-08-08)

- 新增单指按住约 220ms 后再移动的真正左键拖动，解决微信图片详情、地图/滑块等只能靠按住左键拖动的场景；长按静止右键与双击拖动同时保留。
- 输入法曾弹出后，检测 viewport 恢复自动关闭内置输入弹层并返回触控板；草稿和即时输入/回读状态继续保留。
- 输入弹层软键盘未显示时在底部保留约 64px 空白点击关闭区。
- 触控板底部新增截图 / 复制 / 粘贴快捷栏；截图复用原核心，复制/粘贴由 Windows SendInput 注入 Ctrl+C / Ctrl+V。
- 保留 preview.8 顶部两行窗口按钮、冷启动 WebSocket 连接交接、浏览器标签防误拖和完整旧输入页。

## v1.3.0-preview.8 (2026-08-08)

- 顶部改为两行：第二行直接放 ChatGPT / Chrome / 微信，三个按钮从触控板区域移出。
- 修复冷启动/首次进入阶段 WebSocket 新旧页面连接互踢导致的反复断联；启动器增加 51877 就绪等待，服务端允许短暂连接交接。
- 多连接交接期间仅最后一条连接断开才全局释放鼠标状态。
- 双击拖动新增约 150ms 稳定按住门槛和 12px 明显移动门槛，避免点击浏览器标签页时自然抖动触发拖动。
- 保留完整旧输入页、内置键盘、即时输入/回读、双指长按和原窗口切换接口。

## v1.3.0-preview.7 (2026-08-08)

- 触控板区域移除“任务切换”中间入口，直接常驻显示 ChatGPT / Chrome / 微信三个窗口切换按钮。
- 三个按钮直接调用既有窗口切换接口，不打开任务切换弹层，不刷新页面，不重建触控板 WebSocket。
- 保留默认触控板首页、内置键盘、完整旧输入页切换、即时输入与回读能力。

# Changelog

## v1.3.0-preview.7 — 2026-08-08

- 默认手机主页面继续保持大面积触控板。
- 恢复完整原文字输入界面的可见切换入口：顶部新增“输入页”，进入 `/input/` 后保留原输入页自身的触控板切回能力。
- “键盘”仍只打开触控板当前页的内置输入 Bottom Sheet，两套入口职责明确，不互相替代。
- 任务/窗口切换入口明确放入触控板区域右上角，按钮提升对比度并显示“▣ 任务切换”，继续只保留 ChatGPT / Chrome / 微信。
- 触控板内快捷按钮阻止 pointer 事件冒泡，避免点击任务切换时被识别为鼠标手势。
- 保留 preview.5 的点击弹层外空白关闭、草稿保留、即时输入、回读、单 WebSocket 生命周期。

## v1.3.0-preview.5 — 2026-08-08

- 修正默认手机入口：旧核心首页加载后立即进入 51877 触控板主页；旧输入页仅保留为显式兼容入口。
- 文字输入弹层支持点击弹层外空白区域直接返回触控板，草稿、即时输入状态和回读状态继续保留。
- 窗口切换弹层同样支持点击弹层外空白区域关闭。
- 触控板区域右上角新增小型“▣ 窗口”入口，继续只提供 ChatGPT / Chrome / 微信。
- 保持单一触控板 WebSocket 生命周期，不因弹层开关或窗口切换重连。

## v1.3.0-preview.4 — 2026-08-08

- 手机默认主界面继续为大面积触控板；文字输入改为当前页面常驻 DOM Bottom Sheet，彻底移除 iframe 方案。
- 主输入手势改为双指长按约 520ms；双指轻触右键、双指滚动与长按三者互斥。
- 移除 preview.3/R2 三指输入主路径与 Touch Events 三指兼容复杂度。
- 将旧输入页关键逻辑迁入 `input_component.js`：批量输入、即时输入、IME composition、targetId、selection、input-state 回读与草稿。
- “发送并回车”改为文字成功后再发送 Enter；Enter 失败时可只重试 Enter，避免文字重复注入。
- 新增同页窗口切换弹层，只保留 ChatGPT、Chrome、微信；内置输入弹层内也保留三个快速按钮。
- 旧 `/input/` 页面继续作为独立兼容入口，但 CSP 禁止被 iframe 嵌入。
- 弹层开关不刷新页面，不重建触控板 WebSocket。

## v1.3.0-preview.3 R2 — 2026-08-06

- 修复真实手机上三指轻触完全不触发的问题：增加 Touch Events 兜底并放宽同步/移动容差。
- 双指轻微移动增加短暂判定缓冲，避免第三指加入前被滚动状态抢占。
- 删除触控板页重复实现的输入表单，改为同页嵌入电脑端原来自带的完整输入页面。
- 增加受限 `/input/` 页面代理和 `/core-api/*` 白名单接口代理，保留原版批量/实时输入、同步、按键和窗口切换能力。
- 禁用嵌入页 Service Worker，防止跨页面缓存干扰。
- 原版普通发送成功后自动返回触控板；失败不关闭，保留原版输入内容。
- 构建产物增加 `_R2` 后缀，程序内部版本仍为 `v1.3.0-preview.3`。

## v1.3.0-preview.3 — 2026-08-06

基线：`v1.3.0-preview.2`；稳定文字输入核心仍源自 `v1.2.5`。

### 三指文字输入

- 新增三指轻触识别：三指按下间隔不超过 120ms、单点移动不超过 16px、总时长不超过 350ms、抬起间隔不超过 140ms。
- 三指候选期间禁止单击、双击、右键、滚动、长按、拖动和鼠标移动。
- 新增当前页面内的底部文字输入浮层，不跳页、不刷新、不重建 WebSocket。
- 新增顶部小型 `⌨ 键盘` 备用按钮。
- 支持取消、发送、发送并回车、手机系统返回键优先关闭。

### 发送可靠性

- 复用稳定核心 `/api/text` 和 `/api/key/enter`。
- `发送并回车` 强制执行：文字接口成功后才调用 Enter。
- 文字失败时保留输入和浮层。
- 文字成功但 Enter 失败时锁定文本并仅重试 Enter，避免重复发送文字。
- 触控板主机限制文本为 20,000 个 Unicode 字符，并拒绝跨来源调用。

### 触控体验

- 新增双指惯性滚动，默认开启、中等强度；低/中/高可调，可完全关闭。
- 新触摸、切后台、页面失焦、WebSocket 断线、输入浮层打开时立即停止惯性。
- 三指方向手势状态已预留，但本版默认不执行，优先保证三指轻触稳定。
- 保留 preview.2 的 2.30× 默认灵敏度、移动加速、480 单帧位移上限和大触控区。

### 状态与日志

- 重整为明确状态：Idle、TapCandidate、SingleMove、LongPress、DoubleTapDrag、TwoFingerPending、TwoFingerScroll、ThreeFingerPending、ThreeFingerSwipe、Suppressed、TextInputOpen、DragLocked。
- pointercancel、lostpointercapture、visibilitychange、pagehide、失焦和断线统一释放与重置。
- 新增三指检测、浮层开关、发送长度/成功/失败、发送并回车完成、状态重置日志；不记录完整文字。

### 版本

- 产品版本：`1.3.0-preview.3`
- 核心程序集/文件版本：`1.3.0.3`
- 页面缓存修订：`phone-input-v1.3.3`

## 1.4.0-native-preview.3 — 2026-08-09

- Android: two-finger tap now performs right click (Windows touchpad convention).
- Android: two-finger movement performs horizontal/vertical scrolling.
- Android: two-finger hold ~520 ms opens a native Android input dialog.
- Android: tap / scroll / hold two-finger branches are mutually exclusive.
- Android: no single-finger long-press right-click is added.
- Android: native batch input supports Chinese IME, emoji, multiline text, Send, Send+Enter.
- Android: BuildConfig generation explicitly enabled to avoid the preview.2 first-build failure.

## 1.4.0-native-preview.9-buildfix1
- Fixed voice relay Enter becoming a local newline by reusing `RealtimeEditText` IME submit normalization.
- Added explicit `发送` beside `关闭`.
- Added serialized final voice flush + Windows Enter to prevent the last text revision from arriving after submit.
- Android versionCode 12; Windows preview.9 host remains compatible.
