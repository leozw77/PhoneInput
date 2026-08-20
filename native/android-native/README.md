# PhoneInputEnhanced Native Android

Version: `1.4.0`

## Touchpad

Native Kotlin UI + custom `TouchpadView`; existing preview.8 mouse/gesture tuning is intentionally unchanged. The main ChatGPT / Chrome / 微信 task-window row remains on the touchpad screen.

## Voice IME relay (preview.9)

The touchpad row remains:

```text
[ ⌫ 退格 ] [ 🎤 语音 ] [ ↵ 回车 ]
```

`🎤 语音` opens a small normal Android `EditText` and displays the user's current IME. Tap the IME's own microphone (Baidu/Sogou/Gboard/etc.). Committed voice text is mirrored to the focused Windows input. Tail revisions are synchronized by deleting/retyping only the changed suffix, with Unicode code-point-safe deletion.

PhoneInputEnhanced does **not** use Android `SpeechRecognizer`, does not request `RECORD_AUDIO`, and preview.9 does not trigger Windows `Win+H`. Closing the IME automatically closes the small relay panel.

## Full input panel

Two-finger hold still opens the full native input panel. Batch/realtime input, IME composition protection, Enter handling, selection/readback and the ChatGPT / Chrome / 微信 switch row remain. Browser readback remains best-effort and is intentionally deferred.

## Files / clipboard

The preview.6 lightweight LAN transfer/manual clipboard design remains unchanged.

## Build

The project uses AGP 8.11.1, Kotlin 2.1.20, compile/target SDK 36 and Java 17. A local Android SDK is required to assemble the APK.

## preview.9-buildfix1

The voice IME relay now has `发送 | 关闭`. `发送` flushes the final phone-IME text revision and then issues one Windows Enter. The relay also reuses `RealtimeEditText` so IME submit actions are normalized instead of becoming local newlines where the keyboard exposes a submit action.
