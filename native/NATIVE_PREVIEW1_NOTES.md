# PhoneInputEnhanced 1.4.0-native-preview.1

## 定位

这是从 `1.3.0-preview.9` 网页触控板基线分出的第一版 Android Native 开发版本。

**没有 WebView。** 浏览器旧客户端仍使用 `/ws`；Android 原生客户端使用 `/v2/ws`。

## Windows 端新增

- 新增 `PhoneInputEnhanced Protocol v2`；
- 新增 `/v2/ws`，不修改旧 `/ws`；
- v2 应用层 `hello`；
- 每条命令包含 `protocol`、`requestId`；
- 每条命令返回 ACK；
- 明确错误码；
- v2 支持鼠标移动、滚动、按键按下/释放、点击、release、heartbeat；
- v2 复用原 51876 Core 的截图和窗口切换；
- v2 复制/粘贴直接复用 TouchpadHost 当前热键注入；
- 浏览器与 Android 共享活动连接计数；最后一个控制连接消失时才释放鼠标按钮。

## Android Native preview.1

- Kotlin；
- 原生 `Activity`；
- 自定义 `TouchpadView`；
- 直接处理 `MotionEvent`；
- 独立 `NativeGestureEngine` 状态机骨架；
- 单指移动鼠标；
- 左 / 中 / 右键；
- ChatGPT / Chrome / 微信直接切换；
- 截图 / 复制 / 粘贴；
- 手动 IP；
- 记住最后连接电脑；
- v2 WebSocket 自动重连；
- 25 秒 heartbeat；
- Activity 进入后台时 release。

## 刻意延后

按照交接计划，以下不塞进 preview.1：点击/双击、按住拖动、双指、惯性、原生 IME 输入、自动发现、剪贴板双向同步、截图回传预览。
