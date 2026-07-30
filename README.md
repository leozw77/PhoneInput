# PhoneInput / 手机输入到电脑

![PhoneInput 演示](docs/demo.gif)

[中文](#中文介绍) · [English](#english)

## 中文介绍

使用手机输入法，直接向 Windows 当前获得焦点的输入框输入文字。无需安装手机 App，
不使用云端服务，只在同一 Wi-Fi 局域网内运行。

### 功能

- 支持整段发送和即时输入
- 支持中文输入法、Unicode 和 Emoji
- 同步手机光标和文字选区
- 支持插入、替换、删除、剪切和拖动删除
- 支持回车提交以及 `Shift+Enter` 换行
- 手机端提供 Windows 截图快捷键（`Win+Shift+S`）
- 本地二维码快速连接手机
- 可选 Windows 开机启动
- 支持添加到 Android/iPhone 主屏幕
- 仅驻留系统托盘，不弹出电脑虚拟键盘

### 使用要求

- Windows 10 或 Windows 11，x64
- 手机和电脑连接同一个 Wi-Fi
- Android 或 iPhone 上的现代浏览器

Android Chrome 经过了最充分的测试。iPhone Safari 可以完成基本输入，但输入法和选区
的细节可能略有不同。

### 快速开始

1. 从 [Releases](../../releases) 下载 `PhoneInputEnhanced-Windows-x64.zip`。
2. 解压并运行 `PhoneInputEnhanced.exe`。
3. Windows 防火墙询问时，只允许**专用网络**。
4. 右键单击托盘图标，选择**显示连接二维码**。
5. 手机和电脑连接同一个 Wi-Fi，然后用手机扫描二维码。
6. 在电脑上点入目标输入框，再用手机输入。

手机网页可以添加到 Android 或 iPhone 主屏幕。由于局域网页面使用 HTTP，部分浏览器
会创建浏览器快捷方式，而不是完整的离线 PWA；这不影响输入功能。

### 输入模式

- **整段发送**：先在手机上编辑完整文字，再一次发送。
- **即时输入**：中文选词确认、英文字符、删除和选区操作会立即发送。
- **提交并清空**：手机回车会在电脑端提交，并清空手机输入框。
- **换行，不提交**：手机回车发送 `Shift+Enter`；蓝色网页按钮仍会提交并清空。

### 已知限制

- 文字会发送给 Windows 当前获得键盘焦点的控件。
- 普通权限程序不能向管理员权限程序注入输入。
- Windows 登录界面、安全桌面、部分游戏和密码框会拒绝模拟输入。
- 富文本编辑器对 `Ctrl+Home`、选区和 Emoji 边界的处理可能不同。
- 只在电脑上移动光标或编辑文字，可能导致手机预期位置和电脑实际位置不一致。
- 电脑重新连接 Wi-Fi 后本地 IP 可能变化，此时需要重新扫描二维码。

### 编译

安装 .NET 8 SDK，然后运行：

```powershell
dotnet restore .\src\PhoneInput\PhoneInput.csproj --configfile .\NuGet.Config
dotnet build .\src\PhoneInput\PhoneInput.csproj -c Release --no-restore
dotnet publish .\src\PhoneInput\PhoneInput.csproj -c Release -r win-x64 `
  --self-contained true --no-restore -o .\dist
```

自包含版本包含 .NET 运行时，因此文件体积较大。

### 隐私

PhoneInput 不包含账号系统、数据分析或云端中转。文字只会通过局域网从手机浏览器发送到
Windows 程序。请勿为它的端口配置路由器公网转发。

## English

Use your phone's input method to type directly into the currently focused input
field on a Windows PC. No Android or iPhone app is required. PhoneInput runs
entirely on the local Wi-Fi network and does not use a cloud service.

### Features

- Batch input and confirmed-text immediate input
- Chinese input methods, Unicode, and Emoji
- Phone caret and text-selection synchronization
- Insert, replace, delete, cut, and drag-delete synchronization
- Enter-to-submit and `Shift+Enter` newline behavior
- Phone shortcut for the Windows screenshot tool (`Win+Shift+S`)
- Local QR code for quick phone connection
- Optional Windows startup
- Android/iPhone home-screen metadata
- Tray-only Windows application with no virtual keyboard

### Requirements

- Windows 10 or Windows 11, x64
- Android or iPhone on the same Wi-Fi as the PC
- A modern mobile browser

Android Chrome is the most thoroughly tested browser. Safari on iPhone should
support the basic workflow, but input-method and selection details can differ.

### Quick start

1. Download `PhoneInputEnhanced-Windows-x64.zip` from
   [Releases](../../releases).
2. Extract and run `PhoneInputEnhanced.exe`.
3. If Windows Firewall prompts, allow **Private networks** only.
4. Right-click the tray icon and choose **显示连接二维码**.
5. Scan the QR code with the phone while both devices use the same Wi-Fi.
6. Focus the intended input field on Windows, then type from the phone.

The mobile page can be added to the Android or iPhone home screen. Because the
local page uses HTTP, some browsers create a browser shortcut instead of a
fully installable offline PWA. Input functionality is unaffected.

### Input modes

- **整段发送 / Batch**: edit the full text on the phone, then send it.
- **即时输入 / Realtime**: confirmed Chinese text, English characters,
  deletion, and selection operations are sent immediately.
- **提交并清空 / Submit and clear**: phone Enter submits on Windows and clears
  the phone field.
- **换行，不提交 / Newline**: phone Enter sends `Shift+Enter`; the blue web
  button still submits and clears.

### Known limitations

- Input is sent to the Windows control that currently owns keyboard focus.
- A normal-privilege process cannot inject input into an elevated process.
- Windows sign-in, secure desktop, some games, and security fields reject
  simulated input.
- Rich-text editors can interpret `Ctrl+Home`, selection, and Emoji boundaries
  differently.
- Moving the caret or editing only on the PC can make the phone's expected
  position differ from the actual PC position.
- The local IP address can change when the PC reconnects to Wi-Fi; scan the QR
  code again when an old home-screen shortcut stops connecting.

### Build

Install the .NET 8 SDK, then run:

```powershell
dotnet restore .\src\PhoneInput\PhoneInput.csproj --configfile .\NuGet.Config
dotnet build .\src\PhoneInput\PhoneInput.csproj -c Release --no-restore
dotnet publish .\src\PhoneInput\PhoneInput.csproj -c Release -r win-x64 `
  --self-contained true --no-restore -o .\dist
```

The self-contained executable is intentionally large because it includes the
.NET runtime.

### Privacy

PhoneInput does not contain an account system, analytics, or cloud relay.
Text is sent directly from the phone browser to the Windows program over the
local network. Do not configure router port forwarding for its port.

## License

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
