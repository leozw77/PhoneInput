# v1.3.0-preview.9 验收清单

## 自动化已通过

- [x] 默认主页为大面积触控板；顶部两行布局与 ChatGPT / Chrome / 微信直达按钮保留。
- [x] 触控板底部新增截图 / 复制 / 粘贴，位于触控板之外、不参与手势状态机。
- [x] 截图调用原 `/core-api/key/screenshot`；复制/粘贴调用本地 `/api/hotkey/copy|paste`。
- [x] 单指轻触仍为左键。
- [x] 快速第二击 + 9px 自然抖动不会发送左键按住，浏览器标签防误拖不回归。
- [x] 单指稳定按住约 220ms 后移动 16px 会发送左键按下，并在抬手时释放，可用于微信图片拖动。
- [x] 单指稳定长按约 620ms 且不移动仍为右键，不会进入左键拖动。
- [x] 双击第二次稳定按住约 150ms 后明显移动仍可正常拖动。
- [x] 双指快速轻触只发送一次右键；双指长按约 520ms 打开内置输入；双指明显移动继续滚动和惯性。
- [x] 输入弹层关闭再打开草稿仍存在。
- [x] 输入弹层下方空白关闭区可直接返回触控板并保留草稿。
- [x] 模拟软键盘 viewport 缩小再恢复后，输入弹层自动关闭。
- [x] 批量输入、发送并回车防重复、即时输入 composition、input-state 回读、selection 同步继续通过。
- [x] 输入弹层连续开关 20 次，页面 WebSocket 不重建。
- [x] Go 单元测试、go vet、JS 语法检查、Chromium 移动端回归通过。

## 建议实机复测

- [ ] 微信图片详情：单指按住约 0.2 秒后移动可拖动图片，松手立即释放。
- [ ] Chrome 标签页快速点击不再被误拖；真正按住后移动时仍能按预期拖动。
- [ ] Android 手机主动收起输入法后，内置输入弹层自动消失并直接恢复触控板。
- [ ] 若某浏览器未自动关闭，确认底部空白区可方便点击返回。
- [ ] 截图 / 复制 / 粘贴在 ChatGPT、Chrome、微信实际前台窗口均有效。
- [ ] Windows 冷启动后首次进入不再反复断联。

## Native preview.5 acceptance

- [ ] Android pointer still feels smooth after settings/diagnostic additions.
- [ ] Sensitivity, scroll speed, natural scroll, haptic and clipboard settings survive restart.
- [ ] Android text clipboard change reaches Windows while app is foregrounded.
- [ ] Windows text clipboard change reaches Android without an echo loop.
- [ ] Non-text Windows clipboard does not erase Android text clipboard.
- [ ] Screenshot opens native preview and repeated screenshots do not stall pointer/input traffic.
- [ ] Current ChatGPT / Chrome / WeChat button highlighting follows Windows foreground process.
- [ ] Diagnostics shows Android version, IP/host, state, server version, reconnect count, writer queue, command counters, ACK/heartbeat age, last error, Core availability and foreground window.
- [ ] Background/foreground and screen off/on recover without restart.
- [ ] Wi-Fi loss/recovery reconnects when LAN returns.
- [ ] Disconnect during drag/drag-lock never leaves Windows left button held.
- [ ] Browser and Android can coexist; disconnecting one does not release the other's held button.
- [ ] Existing browser smoke/regression still passes.

## Native preview.6 acceptance

- Android input popup has one permanent bottom action row; realtime secondary keys remain in `快捷键`.
- Main `截图` button triggers the existing Windows system screenshot shortcut.
- Android system Share -> PhoneInputEnhanced uploads screenshots/images to Windows `Downloads\PhoneInputEnhanced\Images`.
- Android `文件` menu can upload images/files and manually send/pull text clipboard.
- Windows runtime contains `PhoneInputSendTo.exe`; launcher installs `Send to -> PhoneInputEnhanced`.
- PC APK/small file staged through Send To is downloaded by connected Android to `Download/PhoneInputEnhanced`.
- APK receipt offers the Android installer flow.
- File bytes do not enter the control WebSocket writer queue.
- Diagnostics shows pending files, transfer counts/bytes, last transfer and last transfer error.
- Browser regression suite remains green.
