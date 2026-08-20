using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace PhoneInput;

internal static class ForegroundWindow
{
    private static IntPtr _cachedHandle;

    internal sealed record WindowSwitchResult(
        bool Success,
        bool Found,
        string? Target,
        string? Description,
        string? TargetId,
        string? ActualForegroundId,
        string? FailureReason);

    // GetForegroundWindow can return zero when called from a background
    // server thread on a different desktop context. Refresh this value from
    // the WinForms UI thread and let HTTP handlers consume the cached value.
    public static void Refresh() => Interlocked.Exchange(ref _cachedHandle, GetForegroundWindow());

    public static IntPtr GetHandle()
    {
        var handle = Interlocked.CompareExchange(ref _cachedHandle, IntPtr.Zero, IntPtr.Zero);
        return handle != IntPtr.Zero ? handle : GetForegroundWindow();
    }

    public static IntPtr GetActualHandle() => GetForegroundWindow();

    public static string GetId()
    {
        var handle = GetHandle();
        return handle == IntPtr.Zero ? string.Empty : handle.ToInt64().ToString("X");
    }

    public static string GetTargetKind()
    {
        var handle = GetHandle();
        if (handle == IntPtr.Zero)
            return "other";

        GetWindowThreadProcessId(handle, out var processId);
        string processName;
        try { processName = Process.GetProcessById((int)processId).ProcessName; }
        catch { processName = string.Empty; }

        if (processName.Equals("chrome", StringComparison.OrdinalIgnoreCase) ||
            processName.Equals("msedge", StringComparison.OrdinalIgnoreCase) ||
            processName.Equals("brave", StringComparison.OrdinalIgnoreCase) ||
            processName.Equals("vivaldi", StringComparison.OrdinalIgnoreCase) ||
            processName.Equals("opera", StringComparison.OrdinalIgnoreCase))
            return "chrome";
        if (processName.Equals("weixin", StringComparison.OrdinalIgnoreCase) ||
            processName.Equals("wechat", StringComparison.OrdinalIgnoreCase))
            return "wechat";

        var title = GetWindowTitle(handle);
        if (processName.Contains("chatgpt", StringComparison.OrdinalIgnoreCase) ||
            title.Contains("chatgpt", StringComparison.OrdinalIgnoreCase))
            return "chatgpt";
        return "other";
    }

    public static string GetDescription()
    {
        var handle = GetHandle();
        if (handle == IntPtr.Zero) return "未检测到输入目标";

        var titleLength = GetWindowTextLength(handle);
        var title = new StringBuilder(titleLength + 1);
        _ = GetWindowText(handle, title, title.Capacity);

        _ = GetWindowThreadProcessId(handle, out var processId);
        string process;
        try { process = Process.GetProcessById((int)processId).ProcessName; }
        catch { process = "未知程序"; }

        return title.Length > 0 ? $"{process} · {title}" : process;
    }

    public static async Task<WindowSwitchResult> TryActivateAsync(
        string target,
        CancellationToken cancellationToken = default)
    {
        var criteria = target.ToLowerInvariant() switch
        {
            "chatgpt" => new WindowCriteria(["chatgpt"], ["chatgpt"], true),
            "chrome" => new WindowCriteria(["chrome"], [], false),
            "wechat" => new WindowCriteria(["weixin", "wechat"], ["微信", "wechat"], true),
            _ => null
        };

        if (criteria is null)
            return Failure(target, false, null, "unsupported-target");

        var candidate = FindWindow(criteria);
        if (candidate == IntPtr.Zero)
        {
            PhoneInputLog.Warn("window-switch", $"target={target}; result=not-found");
            return Failure(target, false, null, "not-found");
        }

        var targetId = ToId(candidate);
        var description = Describe(candidate);
        PhoneInputLog.Info("window-switch", $"target={target}; candidate={targetId}; description={description}");

        var requested = false;
        for (var attempt = 1; attempt <= 6; attempt++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var actual = GetForegroundWindow();
            var actualId = ToId(actual);
            if (actual == candidate)
            {
                Interlocked.Exchange(ref _cachedHandle, candidate);
                PhoneInputLog.Info(
                    "window-switch",
                    $"target={target}; result=success; attempt={attempt}; requested={requested}; actual={actualId}");
                return new WindowSwitchResult(true, true, target, description, targetId, actualId, null);
            }

            requested = RequestActivation(candidate) || requested;

            PhoneInputLog.Warn(
                "window-switch",
                $"target={target}; result=pending; attempt={attempt}; requested={requested}; actual={actualId}");
            await Task.Delay(50, cancellationToken).ConfigureAwait(false);
        }

        var finalId = ToId(GetForegroundWindow());
        PhoneInputLog.Warn(
            "window-switch",
            $"target={target}; result=failed; requested={requested}; expected={targetId}; actual={finalId}");
        return new WindowSwitchResult(false, true, target, description, targetId, finalId, "foreground-not-confirmed");
    }

    private static WindowSwitchResult Failure(
        string target,
        bool found,
        string? description,
        string reason) =>
        new(false, found, target, description, null, ToId(GetForegroundWindow()), reason);

    private static bool RequestActivation(IntPtr candidate)
    {
        var foreground = GetForegroundWindow();
        var foregroundThreadId = foreground == IntPtr.Zero
            ? 0u
            : GetWindowThreadProcessId(foreground, out _);
        var currentThreadId = GetCurrentThreadId();
        var attached = false;

        try
        {
            if (foregroundThreadId != 0 && foregroundThreadId != currentThreadId)
                attached = AttachThreadInput(currentThreadId, foregroundThreadId, true);

            if (IsIconic(candidate))
                _ = ShowWindow(candidate, ShowNormal);
            _ = BringWindowToTop(candidate);
            return SetForegroundWindow(candidate);
        }
        finally
        {
            if (attached)
                _ = AttachThreadInput(currentThreadId, foregroundThreadId, false);
        }
    }

    private static IntPtr FindWindow(WindowCriteria criteria)
    {
        var current = IntPtr.Zero;
        EnumWindows((handle, _) =>
        {
            if (!IsWindowVisible(handle) || GetWindow(handle, GetOwner) != IntPtr.Zero)
                return true;

            var title = GetWindowTitle(handle);
            GetWindowThreadProcessId(handle, out var processId);
            string processName;
            try { processName = Process.GetProcessById((int)processId).ProcessName; }
            catch { return true; }

            var processMatches = criteria.ProcessNames.Any(x =>
                string.Equals(processName, x, StringComparison.OrdinalIgnoreCase));
            var titleMatches = criteria.TitleTokens.Length == 0 || criteria.TitleTokens.Any(x =>
                title.Contains(x, StringComparison.OrdinalIgnoreCase));
            var titleFallback = criteria.AllowTitleFallback && titleMatches && !IsBrowserProcess(processName);
            if (processMatches || titleFallback)
            {
                current = handle;
                return false;
            }

            return true;
        }, IntPtr.Zero);
        return current;
    }

    private static string Describe(IntPtr handle)
    {
        var title = GetWindowTitle(handle);
        _ = GetWindowThreadProcessId(handle, out var processId);
        string process;
        try { process = Process.GetProcessById((int)processId).ProcessName; }
        catch { process = "unknown"; }
        return title.Length > 0 ? $"{process} - {title}" : process;
    }

    private static string GetWindowTitle(IntPtr handle)
    {
        var length = GetWindowTextLength(handle);
        var title = new StringBuilder(length + 1);
        _ = GetWindowText(handle, title, title.Capacity);
        return title.ToString();
    }

    private static string ToId(IntPtr handle) =>
        handle == IntPtr.Zero ? string.Empty : handle.ToInt64().ToString("X");

    private static bool IsBrowserProcess(string processName) =>
        processName.Equals("chrome", StringComparison.OrdinalIgnoreCase) ||
        processName.Equals("msedge", StringComparison.OrdinalIgnoreCase) ||
        processName.Equals("firefox", StringComparison.OrdinalIgnoreCase);

    private sealed record WindowCriteria(string[] ProcessNames, string[] TitleTokens, bool AllowTitleFallback);

    private delegate bool EnumWindowsProc(IntPtr handle, IntPtr parameter);

    private const uint GetOwner = 4;
    private const int ShowNormal = 9;

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr handle, StringBuilder text, int count);

    [DllImport("user32.dll")]
    private static extern int GetWindowTextLength(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern IntPtr GetWindow(IntPtr handle, uint command);

    [DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr handle, int command);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern bool BringWindowToTop(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
}
