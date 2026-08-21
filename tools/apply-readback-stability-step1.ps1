$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo 'native/android-native/app/src/main/java/com/phoneinputenhanced/nativeclient/NativeInputDialog.kt'
$desktop = Join-Path $repo 'src/PhoneInput/DesktopInputStateReader.cs'
$androidBuild = Join-Path $repo 'native/android-native/app/build.gradle.kts'
$notes = Join-Path $repo 'docs/READBACK_STABILITY_STEP1_20260821.md'

function Replace-Exact {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Old,
        [Parameter(Mandatory=$true)][string]$New,
        [Parameter(Mandatory=$true)][string]$Label
    )

    $text = [IO.File]::ReadAllText($Path)
    $count = ([regex]::Matches($text, [regex]::Escape($Old))).Count
    if ($count -ne 1) {
        throw "[$Label] expected exactly one match in $Path, found $count"
    }
    $text = $text.Replace($Old, $New)
    [IO.File]::WriteAllText($Path, $text, [Text.UTF8Encoding]::new($false))
    Write-Host "Applied: $Label"
}

# Android: distinguish desktop->phone readback application from real user edits.
Replace-Exact -Path $android -Label 'android remote update depth field' -Old @'
    private var suppressTextCallbacks = false
    private var composing = false
'@ -New @'
    private var suppressTextCallbacks = false
    private var remoteUpdateDepth = 0
    private var composing = false
'@

Replace-Exact -Path $android -Label 'android dismiss reset' -Old @'
            readbackInFlight = false
            dialog = null
'@ -New @'
            readbackInFlight = false
            remoteUpdateDepth = 0
            dialog = null
'@

Replace-Exact -Path $android -Label 'android text watcher remote guard' -Old @'
            override fun afterTextChanged(value: Editable?) {
                if (!isActive(token) || suppressTextCallbacks || !realtime) return
'@ -New @'
            override fun afterTextChanged(value: Editable?) {
                if (!isActive(token) || suppressTextCallbacks || remoteUpdateDepth > 0 || !realtime) return
'@

Replace-Exact -Path $android -Label 'android selection remote guard' -Old @'
        input.onSelectionChangedListener = selection@ { start, end ->
            if (!isActive(token) || !realtime || suppressTextCallbacks || composing) return@selection
'@ -New @'
        input.onSelectionChangedListener = selection@ { start, end ->
            if (!isActive(token) || !realtime || suppressTextCallbacks || remoteUpdateDepth > 0 || composing) return@selection
'@

Replace-Exact -Path $android -Label 'android desktop state apply gate' -Old @'
            projectedSelectionStart = start
            projectedSelectionEnd = end
            setEditProgrammatically(state.text, start, end)
            setStatus(if (manual) "已从电脑同步。" else "已回读电脑当前输入内容。", false)
'@ -New @'
            projectedSelectionStart = start
            projectedSelectionEnd = end
            applyDesktopStateProgrammatically(state.text, start, end)
            setStatus(if (manual) "已从电脑同步。" else "已回读电脑当前输入内容。", false)
'@

Replace-Exact -Path $android -Label 'android desktop state helper' -Old @'
    private fun setEditProgrammatically(value: String, start: Int, end: Int) {
'@ -New @'
    private fun applyDesktopStateProgrammatically(value: String, start: Int, end: Int) {
        remoteUpdateDepth++
        try {
            setEditProgrammatically(value, start, end)
        } finally {
            remoteUpdateDepth = (remoteUpdateDepth - 1).coerceAtLeast(0)
        }
    }

    private fun setEditProgrammatically(value: String, start: Int, end: Int) {
'@

# Windows: diagnostics only in Step 1. Do not broaden UIA acceptance yet.
Replace-Exact -Path $desktop -Label 'uia diagnostic on unsupported control' -Old @'
            if (!IsSupportedTextControl(
                    element,
                    processName,
                    allowGoogleSearchComboBox && isGoogleSearchComboBox,
                    out var unsupportedReason))
            {
                PhoneInputLog.Warn(
'@ -New @'
            if (!IsSupportedTextControl(
                    element,
                    processName,
                    allowGoogleSearchComboBox && isGoogleSearchComboBox,
                    out var unsupportedReason))
            {
                LogElementDiagnostics(element, handle, targetId, processName, controlId, unsupportedReason);
                PhoneInputLog.Warn(
'@

Replace-Exact -Path $desktop -Label 'uia diagnostic on unreadable pattern' -Old @'
                else
                {
                    return Unsupported(
                        targetId,
                        controlId,
                        isGoogleSearchComboBox
                            ? "google-search-pattern-unavailable"
                            : "no-readable-pattern");
                }
'@ -New @'
                else
                {
                    var reason = isGoogleSearchComboBox
                        ? "google-search-pattern-unavailable"
                        : "no-readable-pattern";
                    LogElementDiagnostics(element, handle, targetId, processName, controlId, reason);
                    return Unsupported(targetId, controlId, reason);
                }
'@

Replace-Exact -Path $desktop -Label 'uia diagnostic helper' -Old @'
    private static int GetOffset(TextPatternRange document, TextPatternRange selection, bool end = false)
'@ -New @'
    private static void LogElementDiagnostics(
        AutomationElement element,
        IntPtr handle,
        string targetId,
        string processName,
        string controlId,
        string reason)
    {
        try
        {
            string runtimeId;
            try
            {
                runtimeId = string.Join(".", element.GetRuntimeId());
            }
            catch
            {
                runtimeId = "unavailable";
            }

            string supportedPatterns;
            try
            {
                supportedPatterns = string.Join(",", element.GetSupportedPatterns().Select(pattern => pattern.ProgrammaticName));
            }
            catch
            {
                supportedPatterns = "unavailable";
            }

            PhoneInputLog.Warn(
                "input-read-diagnostic",
                $"reason={reason}; process={processName}; window=0x{handle.ToInt64():X}; target={targetId}; control={controlId}; " +
                $"controlType={DiagnosticValue(element.Current.ControlType?.ProgrammaticName)}; " +
                $"name={DiagnosticValue(element.Current.Name)}; automationId={DiagnosticValue(element.Current.AutomationId)}; " +
                $"className={DiagnosticValue(element.Current.ClassName)}; frameworkId={DiagnosticValue(element.Current.FrameworkId)}; " +
                $"runtimeId={runtimeId}; patterns={supportedPatterns}");
        }
        catch (Exception exception) when (exception is ElementNotAvailableException or COMException or InvalidOperationException)
        {
            PhoneInputLog.Warn("input-read-diagnostic", $"reason={reason}; diagnostic=failed; exception={exception.GetType().Name}");
        }
    }

    private static string DiagnosticValue(string? value)
    {
        var normalized = (value ?? string.Empty)
            .Replace("\r", "\\r", StringComparison.Ordinal)
            .Replace("\n", "\\n", StringComparison.Ordinal);
        return normalized.Length <= 180 ? normalized : normalized[..180] + "…";
    }

    private static int GetOffset(TextPatternRange document, TextPatternRange selection, bool end = false)
'@

# Branch-only preview version; main remains 1.4.0.
Replace-Exact -Path $androidBuild -Label 'android preview version' -Old @'
        versionCode = 13
        versionName = "1.4.0"
'@ -New @'
        versionCode = 14
        versionName = "1.4.1-preview.1-readback-stability"
'@

$notesDir = Split-Path -Parent $notes
New-Item -ItemType Directory -Path $notesDir -Force | Out-Null
@'
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
'@ | Set-Content -LiteralPath $notes -Encoding UTF8

Write-Host 'Readback stability Step 1 patch completed.'
