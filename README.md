# PhoneInput / 手机输入到电脑

Use a phone's input method to type directly into the currently focused input
field on a Windows PC. No Android or iPhone app is required. PhoneInput runs
entirely on the local Wi-Fi network and does not use a cloud service.

使用手机输入法，直接向 Windows 当前获得焦点的输入框输入文字。无需安装手机 App，
不使用云端服务，只在同一 Wi-Fi 局域网内运行。

## Features / 功能

- Batch input and confirmed-text immediate input
- Chinese input methods, Unicode and Emoji
- Phone caret and text-selection synchronization
- Insert, replace, delete, cut and drag-delete synchronization
- Enter-to-submit or `Shift+Enter` newline behavior
- Local QR code for quick phone connection
- Optional Windows startup
- Android/iPhone home-screen metadata
- Tray-only Windows application with no virtual keyboard

## Requirements / 要求

- Windows 10 or Windows 11, x64
- Android or iPhone on the same Wi-Fi as the PC
- A modern mobile browser

Android Chrome is the most thoroughly tested browser. Safari on iPhone should
support the basic workflow, but input-method and selection details can differ.

## Quick start / 快速开始

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

## Input modes / 输入模式

- **整段发送**: edit the full text on the phone, then send it.
- **即时输入**: confirmed Chinese text, English characters, deletion and
  selection operations are sent immediately.
- **提交并清空**: phone Enter submits on Windows and clears the phone field.
- **换行，不提交**: phone Enter sends `Shift+Enter`; the blue web button still
  submits and clears.

## Build / 编译

Install the .NET 8 SDK, then run:

```powershell
dotnet restore .\src\PhoneInput\PhoneInput.csproj --configfile .\NuGet.Config
dotnet build .\src\PhoneInput\PhoneInput.csproj -c Release --no-restore
dotnet publish .\src\PhoneInput\PhoneInput.csproj -c Release -r win-x64 `
  --self-contained true --no-restore -o .\dist
```

The self-contained executable is intentionally large because it includes the
.NET runtime.

## Known limitations / 已知限制

- Input is sent to the Windows control that currently owns keyboard focus.
- A normal-privilege process cannot inject input into an elevated process.
- Windows sign-in, secure desktop, some games and security fields reject
  simulated input.
- Rich-text editors can interpret `Ctrl+Home`, selection and Emoji boundaries
  differently.
- Moving the caret or editing only on the PC can make the phone's expected
  position differ from the actual PC position.
- The local IP address can change when the PC reconnects to Wi-Fi; scan the QR
  code again when an old home-screen shortcut stops connecting.

## Privacy / 隐私

PhoneInput does not contain an account system, analytics or cloud relay.
Text is sent directly from the phone browser to the Windows program over the
local network. Do not configure router port forwarding for its port.

## License

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
