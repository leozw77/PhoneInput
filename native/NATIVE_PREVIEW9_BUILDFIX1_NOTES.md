# 1.4.0-native-preview.9-buildfix1

Priority fix for the phone-IME voice relay submit path.

## Root cause

The preview.9 voice relay used a plain multiline `EditText`. Many Android IMEs (including Baidu in some modes) therefore treated Enter as a local newline. Because voice tail mirroring is asynchronous, a subsequent Windows Enter could race the last text revision.

## Fix

- Voice relay now reuses `RealtimeEditText`, the existing IME submit bridge that normalizes `IME_ACTION_SEND`, Enter key events, and `commitText("\\n")` while respecting active Chinese composition.
- Added an explicit `发送` button next to `关闭`.
- `发送` waits for any in-flight relay update, flushes the latest visible text, and then sends Windows Enter on the same serialized input worker.
- The relay dialog closes only after the flush + Enter sequence succeeds.
- Windows Host protocol is unchanged; preview.9 Windows x64 remains compatible.

## Acceptance

1. Dictate with Baidu input method.
2. Tap the relay panel `发送`: the final text must be present on the PC and exactly one Enter must be generated.
3. Tap the Baidu keyboard Send/Enter key: it should follow the same submit path instead of inserting a local newline when the IME reports a submit action.
4. Rapidly tap Send while the last voice revision is still syncing: no duplicate tail and no Enter-before-text race.
