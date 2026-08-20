# PhoneInputEnhanced v1.3.0-preview.9 开发说明

## 架构

本版继续使用已验证的 Windows 输入核心（本地 51876），触控板主机运行在 51877。默认主页始终是触控板；“键盘”打开当前页内置输入组件，“输入页”进入完整原输入页 `/input/`。

顶部采用两行布局：

1. 标题 + 键盘 + 输入页 + 设置；
2. ChatGPT + Chrome + 微信三个直接窗口切换按钮。

触控板本体不再放任何窗口按钮，避免侵占触摸区域或干扰手势。


## preview.9 单指按住拖动

在 preview.8 的浏览器标签防误拖基础上新增 `PressDrag`：普通第一击稳定约 220ms、位移 <=7px 时只进入拖动候选；随后位移 >12px 才发送 `left down`。如果在 220ms 前已经明显移动，则候选被取消并进入普通 `SingleMove`。若一直静止到约 620ms，则仍执行原单指长按右键。双击第二次拖动继续使用 150ms 的独立准备门槛。

## preview.9 输入法收起与底部关闭区

内置输入组件在打开时记录 visual viewport 基线。检测到高度下降超过 `max(110px, 16%)` 后标记软键盘曾打开；之后高度恢复到基线 80px 以内并持续约 240ms，则保存草稿并自动关闭弹层。未检测到软键盘时，弹层底部保留 64px 空白点击区作为兜底。

## preview.9 触控板底部快捷栏

触控板和鼠标三键之间新增“截图 / 复制 / 粘贴”。截图继续代理原核心 `screenshot` 键；复制/粘贴通过新 `/api/hotkey/*` 直接调用 Windows `SendInput` 发送 Ctrl+C / Ctrl+V，不依赖手机系统剪贴板权限。

## 启动阶段 WebSocket 稳定性

preview.7 服务端采用“新连接到来立即关闭旧连接”的单连接策略。手机浏览器首次进入、旧入口重定向、BFCache/页面恢复时可能短暂产生两条 WebSocket，新旧页面会互相触发重连，表现为刚进去反复断联、随后才稳定。

preview.8 改为：

- 启动器先启动 `PhoneInputTouchpadHost.exe`，轮询 `127.0.0.1:51877`，确认监听后才启动核心程序；
- 新连接不再主动踢掉旧连接，允许页面交接期间短暂重叠；
- 服务端记录活动连接数，仅当最后一条 WebSocket 断开时执行 `releaseAllButtons()`；
- 前端连接对象加入 generation 防护，旧 socket 的 onclose 不会覆盖新 socket 状态；
- 每 25 秒发送轻量 `ping`，页面回到前台或网络恢复时调用 `ensureConnected()`。

## 单指与双击拖动

普通单指仍为移动/轻触单击，单指长按仍为右键。

preview.7 的双击拖动在“第二击”只要移动超过 5px 就立刻发送左键按下，这对浏览器标签页过于敏感。preview.8 引入拖动意图门槛：

- 双击时间窗约 300ms，触点距离 <36px；
- 第二击先保持稳定约 150ms、移动 <=7px，才进入“可拖动”准备态；
- 准备完成后移动 >12px 才真正发送左键按下并进入 `DoubleTapDrag`；
- 若第二击在 150ms 之前就明显移动，则取消拖动候选，按普通鼠标移动处理；
- 抬手后仍立即释放左键。

这样保留双击第二次按住拖动，同时显著降低点击 Chrome/浏览器标签时的误拖。

## 双指状态机

`TwoFingerPending` 是双指入口：

- 320ms 内且移动 <12px：右键；
- 任一触点相对起点明显移动，最大移动 >18px：进入 `TwoFingerScroll`，并取消长按计时；
- 两指持续约 520ms 且最大移动 <=22px：进入 `TwoFingerHold`，打开输入弹层；
- 长按触发后双指候选不会再产生右键或滚动。

## 内置输入组件

继续直接复用：`/core-api/status`、`/core-api/input-state`、`/core-api/text`、`/core-api/selection`、`/core-api/key/*`、`/core-api/window-switch/*`。

批量普通发送通过严格 `/api/text` 包装；“发送并回车”只有文字成功后才发送 Enter，Enter 失败只重试 Enter。即时输入继续保留 targetId、IME composition、selection、input-state 回读与草稿。

## 旧页面兼容

`GET /input/` 继续代理完整核心旧输入页，并将 `/api/*` 改写到 `/core-api/*`。触控板顶部保留“输入页”入口，旧页内原触控板按钮可返回主页；触控板主界面不会 iframe 它。

## Native preview.5 architecture notes

Preview.5 keeps pointer traffic on Protocol v2 and adds low-frequency HTTP companion APIs for clipboard, screenshots, foreground-window status, and diagnostics. Large screenshot traffic is isolated from Android realtime input/readback executors so an image request cannot intentionally serialize the input path.

Windows held-button state is no longer global-only: each `/ws` or `/v2/ws` connection receives a `controlSession`, and physical button-up occurs only after the last owning session releases. Final-connection teardown still calls a complete safety release.

Android clipboard synchronization is foreground-only and text-only. It uses the Windows clipboard sequence number plus local suppression to prevent remote->local updates from echoing immediately back to Windows.

Network lifecycle handling watches LAN transports and pauses reconnect churn while no eligible LAN transport exists. It does not implement device discovery or hotspot creation.
