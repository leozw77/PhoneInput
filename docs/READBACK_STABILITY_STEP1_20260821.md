# PhoneInput Readback Stability - Step 1

Date: 2026-08-21
Branch: `feature/readback-stability-android-uia-20260821`
Base main commit: `a7e6d2afedfc739d5f676f59ac372f109c0bc34c`

## Scope

This is a diagnostic/low-risk preview. `main` is intentionally untouched.

### Android

- Added an explicit re-entrant `remoteUpdateDepth` gate around desktop-to-phone readback application.
- `TextWatcher` and selection callbacks now ignore mutations while a desktop readback is being applied.
- Existing `suppressTextCallbacks`, `localRevision`, target locking and realtime diff logic remain intact.
- Preview version: `1.4.1-preview.1-readback-stability`.

### Windows UIA

- No UIA architecture rewrite in Step 1.
- Added failure diagnostics for unsupported controls and missing readable patterns.
- Diagnostic fields include process, HWND, ControlType, Name, AutomationId, ClassName, FrameworkId, RuntimeId and all supported UIA patterns.
- Existing Chromium acceptance rules, ForegroundWindow cache, SendInput batching and selection-writing logic are unchanged.

## Test focus

1. Enter or paste 1000+ Chinese characters on the PC while realtime mode is open on Android.
2. Verify the Android input box does not repeatedly refresh or resend readback text.
3. Test ChatGPT Desktop, Chrome input, Chrome textarea and WeChat input.
4. When readback fails, preserve `input-read-diagnostic` log lines for Step 2 analysis.
5. Do not judge placeholder behavior from this preview; general placeholder filtering is deliberately not changed yet.
