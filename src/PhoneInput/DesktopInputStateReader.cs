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
    public static DesktopInputState ReadCurrent()
    {
        var handle = ForegroundWindow.GetActualHandle();
        var targetId = handle == IntPtr.Zero ? string.Empty : handle.ToInt64().ToString("X");
        if (handle == IntPtr.Zero)
            return Unsupported(targetId, reason: "no-foreground-window");

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
            if (!IsSupportedTextControl(element, processName, out var unsupportedReason))
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
                return Unsupported(targetId, controlId, "no-readable-pattern");

            selectionStart = Math.Clamp(selectionStart, 0, text.Length);
            selectionEnd = Math.Clamp(selectionEnd, selectionStart, text.Length);
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
        out string reason)
    {
        reason = string.Empty;
        var controlType = element.Current.ControlType;
        var descriptor = string.Join(" ", element.Current.Name, element.Current.AutomationId, element.Current.ClassName)
            .ToLowerInvariant();

        if (IsChromiumProcess(processName))
        {
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

    private static bool IsChromiumProcess(string processName) =>
        processName is "chrome" or "msedge" or "brave" or "vivaldi" or "opera" or "chatgpt";

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

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);
}
