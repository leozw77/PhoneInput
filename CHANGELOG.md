# Changelog

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
