using System.Windows.Automation;
using System.Windows.Automation.Text;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace PhoneInput;

internal sealed record DesktopInputState(
    string TargetId,
    string ControlId,
    string Text,
    int SelectionStart,
    int SelectionEnd,
    bool Supported);

internal static class DesktopInputStateReader
{
    public static DesktopInputState ReadCurrent()
    {
        var handle = ForegroundWindow.GetHandle();
        var targetId = handle == IntPtr.Zero ? string.Empty : handle.ToInt64().ToString("X");
        if (handle == IntPtr.Zero)
            return Unsupported(targetId);

        try
        {
            var element = AutomationElement.FocusedElement;
            if (element is null)
                return Unsupported(targetId);

            var controlId = GetControlId(element);
            if (!IsSupportedTextControl(element))
                return Unsupported(targetId, controlId);
            var text = string.Empty;
            var hasText = false;

            if (element.TryGetCurrentPattern(ValuePattern.Pattern, out var valuePattern))
            {
                text = ((ValuePattern)valuePattern).Current.Value ?? string.Empty;
                hasText = true;
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
                }

                var selection = textPattern.GetSelection();
                if (selection.Length > 0)
                {
                    selectionStart = GetOffset(document, selection[0]);
                    selectionEnd = GetOffset(document, selection[0], end: true);
                }
            }

            if (!hasText)
                return Unsupported(targetId, controlId);

            selectionStart = Math.Clamp(selectionStart, 0, text.Length);
            selectionEnd = Math.Clamp(selectionEnd, selectionStart, text.Length);
            return new DesktopInputState(targetId, controlId, text, selectionStart, selectionEnd, true);
        }
        catch (ElementNotAvailableException)
        {
            return Unsupported(targetId);
        }
        catch (COMException)
        {
            return Unsupported(targetId);
        }
        catch (InvalidOperationException)
        {
            return Unsupported(targetId);
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
        var processName = "unknown";
        try { processName = Process.GetProcessById(element.Current.ProcessId).ProcessName; }
        catch { }
        var automationId = element.Current.AutomationId;
        var controlType = element.Current.ControlType.ProgrammaticName;
        var className = element.Current.ClassName;
        return $"{processName}|{controlType}|{automationId}|{className}";
    }

    private static bool IsSupportedTextControl(AutomationElement element)
    {
        var controlType = element.Current.ControlType;
        if (controlType != ControlType.Edit && controlType != ControlType.Document)
            return false;

        var processName = "";
        try { processName = Process.GetProcessById(element.Current.ProcessId).ProcessName.ToLowerInvariant(); }
        catch { }
        var descriptor = string.Join(" ", element.Current.Name, element.Current.AutomationId, element.Current.ClassName)
            .ToLowerInvariant();

        var browser = processName is "chrome" or "msedge" or "brave" or "vivaldi" or "opera";
        var explorer = processName is "explorer";
        if ((browser && (descriptor.Contains("omnibox") || descriptor.Contains("address") || descriptor.Contains("url"))) ||
            (explorer && (descriptor.Contains("address") || descriptor.Contains("location"))))
            return false;

        return true;
    }

    private static DesktopInputState Unsupported(string targetId, string controlId = "") =>
        new(targetId, controlId, string.Empty, 0, 0, false);
}
