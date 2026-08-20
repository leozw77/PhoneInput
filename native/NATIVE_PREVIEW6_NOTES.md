# PhoneInputEnhanced 1.4.0-native-preview.6

## Goal

Preview.6 corrects two preview.5 product-direction mistakes and keeps the daily remote-control path small:

1. Windows screenshot remains a remote **system screenshot key**, not PC screenshot streaming/preview.
2. Cross-device transfer is an explicit small-file bridge, not background automatic clipboard synchronization.

Automatic discovery/new auto-connect design and offline-hotspot mode remain deferred. Browser CRX readback remains deferred.

## Android input UI

The native input dialog removes the two permanent secondary-key rows and the trailing status row. It keeps one visible action row. Realtime-only Backspace/Tab/Esc/arrows move into `快捷键`; no key capability is removed.

## Phone -> Windows

Android is registered for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`. Normal phone screenshots can therefore use the system Share sheet:

`phone screenshot -> Share -> PhoneInputEnhanced -> Windows`

Images are saved under `Downloads\PhoneInputEnhanced\Images`; other files under `Downloads\PhoneInputEnhanced\Files`. App menu `文件` also offers explicit image/file selection.

## Windows -> Android

The Windows runtime includes `PhoneInputSendTo.exe`. On launcher startup it installs a user-level Explorer Send To entry:

`right-click APK/file -> Send to -> PhoneInputEnhanced`

The helper only stages validated local file paths in memory for a short 15-minute one-shot window. Android polls while connected/foregrounded and downloads the staged file using HTTP. The staging entry is removed after successful download. It is not a persistent offline queue.

Received files are stored in `Download/PhoneInputEnhanced` on Android 10+ via MediaStore. APK receipt offers to open the system package installer.

## Transfer isolation

Mouse, scroll, buttons, keys and window-switch commands remain on `/v2/ws`. File bytes never enter the WebSocket writer queue; upload/download uses independent HTTP workers and routes. This avoids file-transfer command backlog affecting cursor latency.

## Clipboard

Preview.5 automatic Android<->Windows clipboard monitoring is removed. `文件` provides two explicit operations:

- `发手机剪贴板`
- `取电脑剪贴板`

This avoids Android background clipboard/lifecycle restrictions and makes transfer behavior deterministic.

## Diagnostics

Diagnostics now include transfer uploading/downloading flags, sent/received file counts and bytes, pending Windows files, latest transfer and latest transfer error in addition to the existing connection/writer/ACK/heartbeat/Host/Core data.
