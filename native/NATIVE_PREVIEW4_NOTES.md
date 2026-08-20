# Native preview.4 notes

Version: `1.4.0-native-preview.4`
Date: 2026-08-09

## 1. Cursor smoothness

Preview.3 forwarded every `MotionEvent` historical sample directly to `NativeWebSocket.sendMove/sendScroll`. High sample-rate devices could therefore enqueue many JSON WebSocket writes during a single Android display frame. The Windows cursor would keep processing old relative movement after the finger had already moved on, which feels like stutter/trailing latency.

Preview.4 adds `MotionFrameBatcher` between `NativeGestureEngine` and `NativeWebSocket`:

- all move samples in one Android display frame are accumulated;
- all scroll samples in one Android display frame are accumulated;
- one frame normally produces one move/scroll command;
- accumulated deltas are preserved exactly;
- values beyond Protocol v2 limits are split into multiple legal chunks rather than clipped;
- the final drag delta is flushed before `LEFT_UP`, preserving drag ordering.

The gesture state machine itself is unchanged, so click/drag/two-finger recognition thresholds do not change in this performance fix.

## 2. Realtime native input

The native input dialog now provides **批量输入 / 即时输入** modes. The selected mode is persisted on Android.

Realtime mode reuses the existing Windows 51876 input core through the 51877 `/core-api/*` compatibility bridge:

- `/core-api/status`
- `/core-api/input-state`
- `/core-api/text`
- `/core-api/selection`
- `/core-api/key/*`

No new incompatible Windows text engine was added.

### Target safety

Realtime mode reads `targetId` and locks the session to that Windows target when the user starts editing. If the Windows target changes, realtime injection pauses instead of sending text into a different window. The user can choose **从电脑同步** to establish the new target.

### Android IME

`EditText` composition spans are observed through `BaseInputConnection`. While Chinese/IME composition is active, intermediate composing text is not transmitted. When composition finishes, the committed local change is diffed against the projected Windows text and only the committed replacement is sent.

### Local edit -> Windows edit

`RealtimeDiffEngine` computes the minimal replace span between projected Windows text and the current Android `EditText`:

1. synchronize selection if necessary;
2. send Backspace when replacing/deleting an existing span;
3. send committed inserted text;
4. update projected text/caret immediately while the serial HTTP executor preserves network ordering.

This supports append, middle insertion, replacement, deletion and IME commit without resending the whole field.

## 3. Readback and selection

Realtime mode now:

- detects the current Windows target;
- automatically reads the current text/selection when entering realtime mode;
- retries initial automatic readback briefly when the core is not ready yet;
- provides **从电脑同步** for manual readback;
- preserves the existing Google-search manual copy-back fallback behavior;
- synchronizes Android selection changes back to Windows after a debounce;
- prevents delayed automatic readback responses from overwriting text typed after the read request started.

## 4. Realtime shortcut keys

The native dialog exposes:

- Backspace
- Enter
- Tab
- Esc
- Left / Up / Down / Right

Backspace edits the Android field first so local and projected Windows state remain aligned. Enter clears the local realtime session and allows the next edit to lock onto the current Windows target again.

## 5. Still intentionally unchanged

- Browser client remains fully supported.
- Native Protocol v2 remains compatible with preview.3.
- Batch input remains available.
- Existing single/two-finger gesture semantics are unchanged.
- Three-finger gestures are still deferred.
