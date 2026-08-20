# PhoneInputEnhanced 1.4.0-native-preview.3

Date: 2026-08-09

## Android Native preview.3 scope

This preview changes the Android touchpad to a Windows Precision Touchpad-like right-click model and adds the requested native input entry gesture.

### Added

- Two-finger tap -> right click.
- Two-finger movement -> vertical/horizontal scroll.
- Two-finger hold for about 520 ms -> opens the native Android input panel.
- Two-finger tap / scroll / hold are mutually exclusive.
- A successful two-finger hold never emits a right click when the fingers are lifted.
- Three or more fingers are ignored and any held drag state is released for safety.
- Native batch input panel supports Chinese IME, English, emoji, multiline text, Send, and Send+Enter.
- Android 11+ observes IME visibility with WindowInsets; after the IME has actually been shown, hiding it closes the input dialog and returns to the touchpad.
- BuildConfig generation is explicitly enabled because NativeWebSocket uses BuildConfig.VERSION_NAME.

### Kept from preview.2

- Single-finger cursor movement.
- Single tap left click.
- Double click.
- 220 ms press then move drag.
- Double-tap second press then move drag.
- Drag lock.
- Direct left/middle/right buttons.
- Screenshot / copy / paste.
- ChatGPT / Chrome / WeChat switching.
- Protocol v2 WebSocket background writer fix.

### Deliberately not added

- Single-finger long-press right click. Android Native now prefers the Windows touchpad convention: two-finger tap for right click.
- Native realtime input/readback/selection migration. The new dialog uses the already stable batch text endpoint for this preview.
- Native inertial scrolling. Basic two-finger scrolling is enabled first so direction/speed can be tuned on a real phone before adding inertia.
