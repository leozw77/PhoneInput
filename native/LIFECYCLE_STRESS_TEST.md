# Native preview.6 lifecycle / disconnect stress test

Version: `1.4.0-native-preview.6`

## Goal

Verify that Android backgrounding, network loss, abrupt socket loss, repeated media operations, and multiple control sessions never leave Windows mouse buttons held and recover without restarting the app.

## Automated coverage in source tree

- Go tests cover per-session mouse-button ownership and disconnect release semantics.
- Protocol v2 read timeout is 45 s; Android heartbeat is 15 s.
- Android connection diagnostics exposes reconnect count, writer queue depth, ACK age, heartbeat age, connection duration, last error, and network-wait state.
- `android-native/tools/ADB_LIFECYCLE_STRESS.ps1` repeatedly backgrounds/resumes and sleeps/wakes an installed Android build. `-ToggleWifi` adds Wi-Fi loss/recovery cycles.

## Physical-device acceptance matrix

1. Background/foreground x30: connection recovers, cursor works, writerQueue returns to 0, no held button.
2. Screen off/on x10: foreground recovery works without manually re-entering IP.
3. Wi-Fi off/on x10: diagnostics changes to network waiting/reconnecting and returns to Connected after LAN is back.
4. Disconnect while left-dragging x10: Windows must release left button; reconnect must start from neutral input state.
5. Disconnect while drag lock is enabled x10: same neutral-state requirement.
6. Browser client + Android client simultaneously: disconnecting one client must not release a mouse button intentionally held by the other client.
7. Screenshot x20: each image opens and closes; pointer/input traffic remains responsive.
8. Clipboard alternating Android -> Windows -> Android x100 text changes: no endless echo loop and latest text wins after settling.
9. Window switching x50 among ChatGPT / Chrome / WeChat: current-window highlight follows the actual foreground app.
10. Windows Core stopped while Host stays up: diagnostics must show `coreAvailable=false`; native pointer control should continue while text/window-switch features report Core failure.

## Expected diagnostics during healthy operation

- `writerQueue`: normally 0; transient 1-3 is acceptable during bursts.
- `ackAge`: periodically refreshed by commands/heartbeat; should recover after reconnect.
- `heartbeatAge`: normally below the heartbeat interval plus network jitter when connected.
- `waitingForNetwork`: true only while no eligible LAN transport is available.
- `lastError`: records the most recent transport failure for troubleshooting; a historical error can remain after recovery.

## Notes

The current CI/container does not have an Android SDK/device, so Android APK assembly and physical-device stress must be performed on a real Android build. Host/unit/browser regression is performed before packaging.
