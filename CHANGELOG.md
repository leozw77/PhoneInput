# Changelog

## 1.4.0 Native Stable - 2026-08-09

- Integrate the Native Android + Windows stable line under `native/`.
- Add the Windows x64 package, Android APKs, and SHA-256 verification records
  under `Release/v1.4.0-native/`.
- Add the Native 1.4.0 development baseline and handoff rules under `docs/`.
- Preserve the supplied implementation summary and the historical v1.2.5
  C#/.NET stable line for rollback.
- Document that Native Android is included, while readback remains incomplete,
  browser/CRX deep readback is deferred, and real device acceptance is still
  bounded by the known limitations.

## 1.2.5 - 2026-08-06

- Promote the tested preview changes to the stable release.

- Fix ChatGPT Desktop automatic synchronization treating the empty editor's `Do anything` placeholder as user text. The placeholder is now treated as empty only for the ChatGPT Desktop target, so switching to an empty ChatGPT editor clears the phone input without copying the placeholder.
- Add bounded asynchronous diagnostic logging under the user's local application data.
- Record window activation, focused-control metadata, read source, retry state, and
  failure reasons without recording input text by default.
- Reject Chromium page-root text such as ChatGPT `RootWebArea` content before it can
  be synchronized as desktop input.
- Fence desktop reads with the requested foreground target and avoid applying a
  read when the target or focused control changed during the request.
- Allow only manual, exact Google Chrome `APjFqb`/`gLFyf` reads to probe
  `ValuePattern`/`TextPattern`; provide an explicit manual clipboard fallback
  without issuing background `Ctrl+A`/`Ctrl+C`.

## 1.2.4 - 2026-08-06

- Add phone controls for switching the foreground window to an already-open
  ChatGPT desktop app, Chrome window, or WeChat window.
- Keep window switching explicit; the app is never started automatically.
- Read desktop text and caret after an explicit switch only when the focused
  control is supported; Chrome address bars and ordinary web pages remain
  excluded.

## 1.2.3 - 2026-08-05

- Enforce a strict single-instance lock across elevated and normal launches.
- Exit duplicate launches immediately instead of leaving invisible tray or
  port-owning background instances.

## 1.2.2 - 2026-08-05

- Sample the foreground window from the interactive tray UI thread so target
  detection remains available to the HTTP service thread.

## 1.2.1 - 2026-08-05

- Move the default local-network service to dedicated port `51876`.
- Keep the framework-dependent release small and compatible with the installed
  .NET 8 Desktop Runtime.

## 1.2.0 - 2026-08-05

- Keep a separate phone draft and caret position for each Windows target window.
- Pause the active realtime session when the foreground window changes and
  restore the matching draft when returning to a previous window.
- Read the current desktop text and selection automatically when a window
  session is resumed, with a manual synchronization fallback.
- Do not import text automatically from previously unused windows such as
  File Explorer or a browser; automatic restore is limited to known drafts.
- Restore the realtime session immediately with a recovered draft so caret
  synchronization does not require an extra character first.
- Reject browser address bars and File Explorer path bars as desktop input
  controls, and only auto-restore a previously identified control.
- Validate realtime text, key, and selection operations against the foreground
  target to prevent stale queued input from reaching another application.
- Validate the Windows startup registration and reject stale paths or
  registrations created from dotnet.exe/DLL launches.
- Publish the stable package as framework-dependent for this machine, which
  already has the .NET 8 Desktop Runtime installed.

## 1.1.1

- Do not send caret or selection keys when the phone input area is empty.
- Start target locking only after the first text input, preventing clicks on the
  phone input area from controlling video players or non-editable PC windows.
- Keep the original target lock when the foreground window changes.

## 1.1.0

- Add a phone-side screenshot shortcut that opens the Windows snipping overlay.
- Add an animated usage demo to the README.

## 1.0.0 — 2026-07-31

- Android browser input over the local Wi-Fi network.
- Immediate and batch input modes.
- Unicode, Chinese input method and Emoji support.
- Phone caret and selection synchronization with Windows.
- Selection replacement, deletion, cut and drag-delete synchronization.
- Configurable Enter behavior.
- Local QR-code connection window.
- Windows startup option.
- Android and iPhone home-screen web-app metadata.
- No cloud service or account required.
