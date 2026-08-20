# PhoneInputEnhanced 1.4.0-native-preview.7

## Goal

Preview.7 keeps the preview.6 file bridge and slim input UI, and adds two high-frequency controls requested for daily remote use:

1. ChatGPT / Chrome / 微信 window-switch buttons inside the native input dialog itself.
2. A touchpad-overlay voice-input button with a dedicated Enter button immediately beside it.

No Windows protocol change is required for these controls; preview.7 reuses the existing `window_switch`, `key enter`, and `/api/text` paths.

## Input-dialog window switching

The native input dialog now shows a compact 3-button row directly below its title:

`ChatGPT | Chrome | 微信`

Batch mode keeps the draft while switching. Realtime mode intentionally clears the old target lock/projection before switching, then reacquires the new Windows target after a short delay. This prevents text from the previous app from being injected into the newly selected app.

## Touchpad voice input

A small overlay row is placed inside the bottom of the touchpad:

`语音 | 回车`

`语音` starts one-shot Android system speech recognition. Only the final committed recognition result is sent to Windows through the existing `/api/text` endpoint. Partial hypotheses are never injected, avoiding duplicate/revised Chinese text during recognition.

`回车` sends the existing Protocol v2 Enter key command immediately. Typical use is therefore:

`tap 语音 -> speak -> recognized text is typed on PC -> tap 回车`

Tapping `语音` again while listening cancels the current recognition session.

## Android requirements

Preview.7 adds `RECORD_AUDIO` runtime permission and declares the Android speech recognition service query needed by apps targeting modern Android versions. The recognizer is one-shot, not continuous, and is destroyed with the Activity lifecycle.

## Compatibility

- Windows preview.6 remains protocol-compatible; a Windows rebuild is only for matching version packaging.
- Existing mouse/scroll/drag/two-finger gestures are unchanged.
- Existing Windows system screenshot button remains unchanged.
- Existing phone screenshot/file bridge remains unchanged.
- Browser UI remains unchanged.
