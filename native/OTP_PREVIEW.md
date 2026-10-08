# PhoneInput OTP preview

This preview adds background SMS broadcast reception and a notification listener to the Native Android touchpad app, plus a separate Windows OTP panel. The Android app does not gain a keyboard or touchpad feature; its existing touchpad remains as it is.

## Data path

1. On Android, tap the connection-status line and grant the requested `RECEIVE_SMS` permission. This enables the background `SMS_RECEIVED` path; PhoneInput does not request inbox-read access or scan message history.
2. Grant notification access from the same status line as a supplementary path. Notifications are read from the current default SMS app only.
3. Either path extracts a 4–8 digit code and sends only the code, sender label, receive time, and deduplication ID to the saved computer IP over the existing local network connection.
4. The Windows touchpad host keeps the latest code in memory. The separately launched `PhoneInputOtpPanel.exe` displays it and can type it into the foreground input target, copy it, or send user-maintained snippets and custom keys.

The SMS or notification body is neither stored nor sent to the computer. A shared in-memory deduplicator suppresses notification updates with changed `postTime` and near-simultaneous SMS/notification duplicates. The host log does not contain the code or sender. The Windows panel does not open with each message and does not invoke the Windows on-screen keyboard. The panel uses a non-activating window so its buttons can send text to the window that was active before the panel was opened.

## OTP diagnostics

The Android preview mirrors privacy-safe flow events to logcat (`PhoneInputOTP`) and to the bounded app-private file `files/otp-diagnostics.log` (maximum 256 KiB, trimmed to the newest 128 KiB). Events identify the source (`sms_broadcast` or `notification`), parse outcome, configured-host decision, deduplication decision, each HTTP attempt and result. A random trace ID links those events to the Windows Host's `OTP event=host_received` entry. No OTP, message body, sender, notification key, or PC IP is written to these diagnostics.

For the debug preview package, the file can be read without opening the app using Android's `run-as` for `com.phoneinputenhanced.nativeclient.otpPreview2`, or the same events can be collected from logcat. This makes it possible to tell whether a background SMS broadcast reached the receiver, whether it contained a recognizable code, and where forwarding stopped.

## Preview installation

- Install `PhoneInputEnhanced-OTP-Preview.apk` alongside the existing stable app. It uses the separate `com.phoneinputenhanced.nativeclient.otpPreview2` preview package ID and has its own settings; the earlier `.otpPreview` install remains untouched.
- Open the preview app, enter the PC's existing PhoneInput address, and connect once so the address is saved.
- Tap the connection-status line and grant SMS permission, then tap it again to open notification access settings if desired. The line shows each permission state.
- Replace the running Native host with the preview `PhoneInputTouchpadHost.exe` while preserving the stable install as a rollback copy; the Windows host already used by the Native app owns port 51877.
- Start the host and launch `PhoneInputOtpPanel.exe` when an OTP is expected. Existing stable Windows Core input service must remain running for typing into the current target.

The Android APK and Windows binaries are built by the `PhoneInput OTP preview` GitHub Actions workflow. No Android SDK is needed on the development PC.
