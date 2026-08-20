# Native preview.4 BuildFix1 — Android IME Enter/Send

## Problem

On some Android keyboards, pressing the IME enter/send key did not produce a usable Windows Enter action. The preview.4 input box was multiline and Android keyboards may report submit in different forms:

- `performEditorAction(IME_ACTION_SEND / DONE / GO)`
- `sendKeyEvent(KEYCODE_ENTER)`
- `commitText("\\n")`

Depending on the keyboard, more than one of these may be emitted for a single tap.

## Fix

`RealtimeEditText` now normalizes all supported IME submit forms into one `onImeSubmit` callback.

- Batch mode: IME submit => send current text => send exactly one Windows Enter => close panel.
- Realtime mode: committed text is already mirrored => IME submit sends exactly one Windows Enter.
- Active Chinese/IME composition is not treated as submit. Candidate confirmation remains owned by the IME.
- Duplicate action/key/newline notifications are suppressed for 320 ms so one tap cannot inject multiple Windows Enter keys.
- The editor explicitly advertises `IME_ACTION_SEND` and clears Android's multiline `IME_FLAG_NO_ENTER_ACTION`.

## Version

- versionCode: 6
- versionName: `1.4.0-native-preview.4-buildfix1`

## Windows compatibility

No Windows-side change is required. Continue using the existing preview.4 Windows package.
