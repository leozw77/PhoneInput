# PhoneInput OTP preview

This preview adds a notification listener to the Native Android touchpad app and a separate Windows OTP panel. The Android app does not gain a keyboard or touchpad feature; its existing touchpad remains as it is.

## Data path

1. On Android, grant PhoneInput the system's notification-access permission.
2. Keep the default SMS app's OTP notification visible to notification access. PhoneInput reads notifications from the current default SMS app only.
3. PhoneInput extracts a 4–8 digit code and sends only the code, sender label, notification time, and deduplication ID to the saved computer IP over the existing local network connection.
4. The Windows touchpad host keeps the latest code in memory. The separately launched `PhoneInputOtpPanel.exe` displays it and can type it into the foreground input target, copy it, or send user-maintained snippets and custom keys.

The notification body is neither stored nor sent to the computer. The host log does not contain the code or sender. The Windows panel does not open with each message and does not invoke the Windows on-screen keyboard. The panel uses a non-activating window so its buttons can send text to the window that was active before the panel was opened.

## Preview installation

- Install `PhoneInputEnhanced-OTP-Preview.apk` alongside the existing stable app. It uses a separate preview package ID and has its own settings.
- Open the preview app, enter the PC's existing PhoneInput address, and connect once so the address is saved.
- Tap the connection-status line, grant notification access, then return to PhoneInput. The same line will show whether access is enabled.
- Replace the running Native host with the preview `PhoneInputTouchpadHost.exe` while preserving the stable install as a rollback copy; the Windows host already used by the Native app owns port 51877.
- Start the host and launch `PhoneInputOtpPanel.exe` when an OTP is expected. Existing stable Windows Core input service must remain running for typing into the current target.

The Android APK and Windows binaries are built by the `PhoneInput OTP preview` GitHub Actions workflow. No Android SDK is needed on the development PC.
