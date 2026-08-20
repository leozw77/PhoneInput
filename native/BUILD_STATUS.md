# Build Status - 1.4.0 Stable

- Windows Host/Launcher/SendTo/ImageTray: Go 1.23 cross-build PASS.
- Windows Go cross-build: PASS for all four Native executables. Go 1.26.5 `go vet` reports unsafe.Pointer warnings in Windows OLE/ImageTray and input code.
- Browser JavaScript syntax/smoke: release packaging can run smoke tests.
- ImageTray uses Win32 + GDI+ for PNG/JPEG display and OLE CF_HDROP for Explorer-compatible file drag. Cross-build is validated here; real Windows drag-to-ChatGPT remains a physical acceptance item.
- Android voice relay pure Kotlin diff test remains available.
- Android Debug APK assembly: PASS with Android SDK 36 and JDK 17.
- Android unsigned Release APK assembly: PASS; production signing was not performed because no release keystore was supplied.
