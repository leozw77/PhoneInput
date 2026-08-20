# PhoneInputEnhanced 1.4.0-native-preview.10

## Goal

Turn the existing phone screenshot/share upload into a fast ChatGPT workflow without adding a file manager: after an image reaches Windows, show it in a small bottom relay bar and let the user drag the real file directly into ChatGPT/Chrome.

## Windows ImageTray

New `PhoneInputImageTray.exe` is a dependency-free Go/Win32 helper. It is started only after an image upload. A second invocation finds the existing single-instance window and forwards the new file path via `WM_COPYDATA`.

- bottom-center, top-most tool window, shown without stealing focus on arrival
- newest first, maximum 5 images
- GDI+ thumbnail decoding/drawing
- single click opens the real file using the Windows default viewer
- close button hides the tray; the process remains ready and the next incoming image reopens it
- dragging a thumbnail exposes `CF_HDROP` through OLE `IDataObject` / `IDropSource`, matching a normal Explorer file drag
- image bytes are not copied into the drag payload; the saved PNG/JPEG path is handed to the drop target

## Host integration

`/api/files/upload` behavior is unchanged for ordinary files. When the upload category resolves to `Images`, successful finalization asynchronously notifies `PhoneInputImageTray.exe`. File upload remains independent from WebSocket mouse traffic.

## Frozen areas

- Touchpad mouse/gesture algorithms unchanged.
- Main ChatGPT / Chrome / 微信 task-window controls retained.
- preview.9-buildfix1 voice relay/send behavior retained.
- Browser/CRX deep readback deferred.
