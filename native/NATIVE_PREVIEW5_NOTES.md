# Native preview.5 notes

Version: `1.4.0-native-preview.5`

This milestone consolidates productization and stability work. It deliberately does **not** add mDNS/NSD discovery, a new auto-connect workflow, or an offline-hotspot wizard.

## Added

- Text clipboard synchronization between Android and Windows while the Android app is in the foreground.
- Native Android screenshot preview backed by a new Windows PNG screenshot endpoint.
- Foreground-window status for ChatGPT / Chrome / WeChat, including active button highlighting.
- Persisted touch settings: pointer sensitivity, scroll speed, natural scrolling, haptics, clipboard sync.
- Light haptic feedback for discrete gestures/actions; pointer movement itself never vibrates.
- Diagnostics center with Android transport metrics plus Windows Host/Core status.
- LAN lifecycle monitoring and immediate recovery after network return.
- Per-control-session mouse-button ownership on Windows, preventing one session disconnect from releasing another session's intentional hold.
- 15 s Android heartbeat and 45 s Host read timeout to reduce stale-control lifetime after abrupt loss.

## Clipboard scope

Clipboard synchronization is text-only in preview.5. Android monitoring is intentionally foreground-only. Existing Copy/Paste buttons remain and still issue Windows Ctrl+C / Ctrl+V; clipboard sync makes the resulting text available on the other device.

## Screenshot scope

The Host captures the full Windows virtual desktop as PNG and the Android client presents it in a native image dialog. This is an on-demand preview, not a remote-desktop video stream.

## Readback

Existing UIA-based readback remains best-effort and unchanged in priority. CRX/browser-DOM readback is intentionally deferred.
