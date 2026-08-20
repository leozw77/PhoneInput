# Native preview.1 BuildFix1

修复两个首轮实机问题：

1. **首个控制动作后立即“重连中 · 发送失败：网络错误”**
   - 根因：连接握手在后台线程，但连接完成后的 `move/click/hotkey/key/window_switch/ping` 由 UI 回调直接执行 `BufferedOutputStream.write/flush`。
   - Android 会对主线程 TCP 写入抛出 `NetworkOnMainThreadException`。该异常通常没有 message，因此旧 UI 只显示模糊的“网络错误”。
   - 修复：增加独立单线程 writer executor。所有握手后的 WebSocket 控制帧都排队到 writer 线程发送；reader 仍保持独立阻塞读取。
   - 同时保留发送顺序，并避免 reader executor 被阻塞导致无法发送。

2. **Android 16 顶部标题/系统状态栏重叠**
   - 修复：按 `WindowInsets.Type.systemBars()` 给根布局应用系统栏 inset。
   - 连接状态改为独立一行，不再挤在 IP 输入框左侧的窄栏中。

另外：
- 网络错误现在会显示异常类型，后续若仍有局域网/防火墙问题更容易定位。
- Windows 端 Protocol v2 无需修改，本 BuildFix 只替换 Android 客户端。
