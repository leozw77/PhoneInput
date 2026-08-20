# Test Report - 1.4.0 Stable

Date: 2026-08-09

## Automated checks run in this environment

- `go test ./...` compiled all packages, but 5 Windows integration tests failed in this restricted desktop session: file-upload destination creation, system hotkey dispatch, and Windows input ownership/access checks. These are environment-interaction failures, not Go compile failures.
- `go vet ./...` under Go 1.26.5 reports unsafe.Pointer warnings in Windows OLE/ImageTray and input code; the Windows cross-build still completes successfully.
- `go vet ./...` on the native Linux build surface — PASS.
- Windows amd64 cross-build — PASS for `PhoneInputTouchpadHost.exe`, `PhoneInputEnhanced.exe` launcher, `PhoneInputSendTo.exe`, and new `PhoneInputImageTray.exe`.
- Browser JavaScript syntax / smoke — PASS; legacy fallback gestures, input panel, window buttons and shortcut behaviors remain present.
- `VoiceRelayDiffEngineSmoke.kt` — PASS (append / tail correction / emoji deletion).
- `NativeGestureEngineSmoke.kt` — PASS; only the existing unused-parameter compiler warning remains.

## preview.10 image relay implementation checks

- Host image uploads still finalize to `Downloads/PhoneInputEnhanced/Images` before ImageTray notification.
- Ordinary file uploads do not invoke the image tray path.
- `PhoneInputImageTray.exe` is a single-instance Win32 helper; subsequent invocations forward image paths via `WM_COPYDATA`.
- Image display code cross-builds against Windows GDI+.
- Drag source code cross-builds against OLE and exposes the real file through `CF_HDROP`/`TYMED_HGLOBAL`; an `IEnumFORMATETC` implementation advertises the file-drop format to drag targets.

## Still requires real Windows / Android acceptance

- Actual bottom-bar rendering on the user's Windows display/DPI setup.
- Phone screenshot Share → upload → automatic ImageTray appearance.
- Holding a thumbnail with the phone touchpad and dropping it into the user's exact ChatGPT/Chrome UI.
- Android Debug APK assembly completed with the local Android SDK. The unsigned Release APK also assembled; production signing remains pending a release keystore.

CRX/browser deep readback remains intentionally deferred and is not part of preview.10 acceptance.
