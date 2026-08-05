using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Automation;
using System.Windows.Automation.Text;

namespace PhoneInput;

internal sealed record DesktopInputState(
    string TargetId,
    string ControlId,
    string Text,
    int SelectionStart,
    int SelectionEnd,
    bool Supported,
    string Source = "",
    string Reason = "");

internal static class DesktopInputStateReader
{
    public static DesktopInputState ReadCurrent(
        string expectedTargetId,
        bool allowGoogleSearchComboBox = false,
        bool allowClipboardFallback = false)
    {
        var handle = ForegroundWindow.GetActualHandle();
        var targetId = ToTargetId(handle);
        if (handle == IntPtr.Zero)
            return Unsupported(targetId, reason: "no-foreground-window");

        if (!string.Equals(expectedTargetId, targetId, StringComparison.OrdinalIgnoreCase))
            return TargetMismatch(targetId);

        try
        {
            var element = AutomationElement.FocusedElement;
            if (element is null)
                return Unsupported(targetId, reason: "no-focused-element");

            GetWindowThreadProcessId(handle, out var targetProcessId);
            var processName = GetProcessName(targetProcessId);
            if ((uint)element.Current.ProcessId != targetProcessId)
            {
                PhoneInputLog.Warn(
                    "input-read",
                    $"result=unsupported; reason=focused-process-mismatch; target={targetId}; targetProcess={processName}; focusedProcess={element.Current.ProcessId}");
                return Unsupported(targetId, reason: "focused-process-mismatch");
            }

            var controlType = element.Current.ControlType;
            if (IsChromiumProcess(processName) && controlType == ControlType.Document)
            {
                var focusedEdit = FindFocusedEdit(handle);
                if (focusedEdit is not null)
                {
                    element = focusedEdit;
                    controlType = element.Current.ControlType;
                }
            }

            var controlId = GetControlId(element);
            var isGoogleSearchComboBox = IsGoogleSearchComboBox(element, processName);
            if (!IsSupportedTextControl(
                    element,
                    processName,
                    allowGoogleSearchComboBox && isGoogleSearchComboBox,
                    out var unsupportedReason))
            {
                PhoneInputLog.Warn(
                    "input-read",
                    $"result=unsupported; reason={unsupportedReason}; target={targetId}; control={controlId}");
                return Unsupported(targetId, controlId, unsupportedReason);
            }

            var text = string.Empty;
            var hasText = false;
            var source = string.Empty;

            if (element.TryGetCurrentPattern(ValuePattern.Pattern, out var valuePattern))
            {
                text = ((ValuePattern)valuePattern).Current.Value ?? string.Empty;
                hasText = true;
                source = "ValuePattern";
            }

            var selectionStart = 0;
            var selectionEnd = 0;
            if (element.TryGetCurrentPattern(TextPattern.Pattern, out var textPatternObject))
            {
                var textPattern = (TextPattern)textPatternObject;
                var document = textPattern.DocumentRange;
                if (!hasText)
                {
                    text = document.GetText(-1) ?? string.Empty;
                    hasText = true;
                    source = "TextPattern";
                }
                else
                {
                    source += "+TextPattern";
                }

                var selection = textPattern.GetSelection();
                if (selection.Length > 0)
                {
                    selectionStart = GetOffset(document, selection[0]);
                    selectionEnd = GetOffset(document, selection[0], end: true);
                }
            }

            if (!hasText)
            {
                if (isGoogleSearchComboBox && allowClipboardFallback && TryReadClipboardText(out text))
                {
                    source = "Clipboard.ManualCopy";
                    hasText = true;
                    selectionStart = text.Length;
                    selectionEnd = text.Length;
                }
                else
                {
                    return Unsupported(
                        targetId,
                        controlId,
                        isGoogleSearchComboBox
                            ? "google-search-pattern-unavailable"
                            : "no-readable-pattern");
                }
            }

            // ChatGPT Desktop exposes its empty-editor placeholder through UIA as if it
            // were editor content. Treat only this known ChatGPT-specific placeholder
            // as empty; do not apply a global string filter to other applications.
            if (IsChatGptDesktopPlaceholder(processName, element, text, selectionStart, selectionEnd))
            {
                text = string.Empty;
                selectionStart = 0;
                selectionEnd = 0;
                source += "+ChatGptPlaceholder";
            }

            selectionStart = Math.Clamp(selectionStart, 0, text.Length);
            selectionEnd = Math.Clamp(selectionEnd, selectionStart, text.Length);

            var finalTargetId = ToTargetId(ForegroundWindow.GetActualHandle());
            if (!string.Equals(targetId, finalTargetId, StringComparison.OrdinalIgnoreCase))
                return TargetMismatch(finalTargetId);

            PhoneInputLog.Info(
                "input-read",
                $"result=supported; target={targetId}; process={processName}; control={controlId}; source={source}; textLength={text.Length}; selection={selectionStart}-{selectionEnd}");
            return new DesktopInputState(targetId, controlId, text, selectionStart, selectionEnd, true, source);
        }
        catch (ElementNotAvailableException)
        {
            return Unsupported(targetId, reason: "element-not-available");
        }
        catch (COMException)
        {
            return Unsupported(targetId, reason: "uia-com-exception");
        }
        catch (InvalidOperationException)
        {
            return Unsupported(targetId, reason: "uia-invalid-operation");
        }
        catch (Exception exception)
        {
            PhoneInputLog.Error("input-read", exception);
            return Unsupported(targetId, reason: "unexpected-exception");
        }
    }

    private static AutomationElement? FindFocusedEdit(IntPtr handle)
    {
        try
        {
            var root = AutomationElement.FromHandle(handle);
            var condition = new AndCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit),
                new PropertyCondition(AutomationElement.HasKeyboardFocusProperty, true));
            return root.FindFirst(TreeScope.Descendants, condition);
        }
        catch (Exception exception) when (exception is ElementNotAvailableException or COMException or InvalidOperationException)
        {
            PhoneInputLog.Warn("input-read", $"focused-edit-search=failed; reason={exception.GetType().Name}");
            return null;
        }
    }

    private static int GetOffset(TextPatternRange document, TextPatternRange selection, bool end = false)
    {
        var prefix = document.Clone();
        prefix.MoveEndpointByRange(
            TextPatternRangeEndpoint.End,
            selection,
            end ? TextPatternRangeEndpoint.End : TextPatternRangeEndpoint.Start);
        return prefix.GetText(-1)?.Length ?? 0;
    }

    private static string GetControlId(AutomationElement element)
    {
        var processName = GetProcessName((uint)element.Current.ProcessId);
        var automationId = element.Current.AutomationId;
        var controlType = element.Current.ControlType.ProgrammaticName;
        var className = element.Current.ClassName;
        return $"{processName}|{controlType}|{automationId}|{className}";
    }

    private static bool IsSupportedTextControl(
        AutomationElement element,
        string processName,
        bool allowGoogleSearchComboBox,
        out string reason)
    {
        reason = string.Empty;
        var controlType = element.Current.ControlType;
        var descriptor = string.Join(" ", element.Current.Name, element.Current.AutomationId, element.Current.ClassName)
            .ToLowerInvariant();

        if (IsChromiumProcess(processName))
        {
            if (allowGoogleSearchComboBox && IsGoogleSearchComboBox(element, processName))
                return true;

            if (controlType != ControlType.Edit)
            {
                reason = "chromium-page-root-not-edit";
                return false;
            }

            if (descriptor.Contains("omnibox") || descriptor.Contains("address") || descriptor.Contains("url"))
            {
                reason = "browser-address-bar";
                return false;
            }

            return true;
        }

        if (controlType != ControlType.Edit && controlType != ControlType.Document)
        {
            reason = "unsupported-control-type";
            return false;
        }

        if (processName is "explorer" &&
            (descriptor.Contains("address") || descriptor.Contains("location")))
        {
            reason = "explorer-address-bar";
            return false;
        }

        return true;
    }

    private static bool IsGoogleSearchComboBox(AutomationElement element, string processName) =>
        string.Equals(processName, "chrome", StringComparison.OrdinalIgnoreCase) &&
        element.Current.ControlType == ControlType.ComboBox &&
        string.Equals(element.Current.AutomationId, "APjFqb", StringComparison.OrdinalIgnoreCase) &&
        string.Equals(element.Current.ClassName, "gLFyf", StringComparison.OrdinalIgnoreCase);

    private static bool IsChromiumProcess(string processName) =>
        processName is "chrome" or "msedge" or "brave" or "vivaldi" or "opera" or "chatgpt";

    private static bool IsChatGptDesktopPlaceholder(
        string processName,
        AutomationElement element,
        string text,
        int selectionStart,
        int selectionEnd) =>
        string.Equals(processName, "chatgpt", StringComparison.OrdinalIgnoreCase) &&
        (element.Current.ControlType == ControlType.Edit || element.Current.ControlType == ControlType.Document) &&
        selectionStart == 0 &&
        selectionEnd == 0 &&
        string.Equals(text.Trim(), "Do anything", StringComparison.Ordinal);

    private static string GetProcessName(uint processId)
    {
        try { return Process.GetProcessById((int)processId).ProcessName.ToLowerInvariant(); }
        catch { return "unknown"; }
    }

    private static DesktopInputState Unsupported(
        string targetId,
        string controlId = "",
        string reason = "unsupported") =>
        new(targetId, controlId, string.Empty, 0, 0, false, "", reason);

    private static DesktopInputState TargetMismatch(string actualTargetId) =>
        Unsupported(actualTargetId, reason: "target-mismatch");

    private static string ToTargetId(IntPtr handle) =>
        handle == IntPtr.Zero ? string.Empty : handle.ToInt64().ToString("X");

    private static bool TryReadClipboardText(out string text)
    {
        text = string.Empty;
        var clipboardText = string.Empty;
        using var completed = new ManualResetEventSlim(false);
        var thread = new Thread(() =>
        {
            try
            {
                if (Clipboard.ContainsText())
                    clipboardText = Clipboard.GetText();
            }
            catch
            {
                clipboardText = string.Empty;
            }
            finally
            {
                completed.Set();
            }
        });
        thread.IsBackground = true;
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();

        if (!completed.Wait(TimeSpan.FromMilliseconds(500)))
            return false;

        thread.Join();
        text = clipboardText;
        return !string.IsNullOrEmpty(text);
    }

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);
}
