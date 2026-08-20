# PhoneInputEnhanced 1.4.0-native-preview.8

## Voice control redesign

Android no longer performs speech recognition. The touchpad voice control sends Protocol v2 `hotkey.voice`; the Windows Host injects `Win+H` to toggle Windows voice typing. This removes Android `RECORD_AUDIO`, `SpeechRecognizer`, recognizer lifecycle callbacks, and transcript forwarding.

The touchpad voice strip is:

```text
[ ⌫ 退格 ] [ 🎤 语音 ] [ ↵ 回车 ]
```

- `退格`: sends Windows Backspace; holding the button repeats after 360 ms at about 72 ms intervals.
- `语音`: sends Windows `Win+H`.
- `回车`: sends Windows Enter.

Voice hotkey diagnostics record send count, last-send age, and the ACK state.

## Compatibility

The native input dialog keeps the preview.7 ChatGPT / Chrome / 微信 window switch buttons and the slimmed action layout. File transfer, Windows system screenshot, mouse gestures, realtime input, readback, browser fallback, and Protocol v2 remain compatible.

## Required pairing

The Android preview.8 client must be paired with the preview.8 Windows Host for `hotkey.voice`; older Hosts reject that action. Other existing v2 commands remain backward compatible.
