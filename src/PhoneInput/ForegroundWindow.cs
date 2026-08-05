using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace PhoneInput;

internal static class ForegroundWindow
{
    private static IntPtr _cachedHandle;

    internal sealed record WindowSwitchResult(bool Success, bool Found, string? Target, string? Description);

    // GetForegroundWindow can return zero when called from a background
    // server thread on a different desktop context. Refresh this value from
    // the WinForms UI thread and let HTTP handlers consume the cached value.
    public static void Refresh() => Interlocked.Exchange(ref _cachedHandle, GetForegroundWindow());

    public static IntPtr GetHandle()
    {
        var handle = Interlocked.CompareExchange(ref _cachedHandle, IntPtr.Zero, IntPtr.Zero);
        return handle != IntPtr.Zero ? handle : GetForegroundWindow();
    }

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

    public static WindowSwitchResult TryActivate(string target)
    {
        var criteria = target.ToLowerInvariant() switch
        {
            "chatgpt" => new WindowCriteria(["chatgpt"], ["chatgpt"], true),
            "chrome" => new WindowCriteria(["chrome"], [], false),
            "wechat" => new WindowCriteria(["weixin", "wechat"], ["微信", "wechat"], true),
            _ => null
        };

        if (criteria is null)
            return new WindowSwitchResult(false, false, target, null);

        var candidate = FindWindow(criteria);
        if (candidate == IntPtr.Zero)
            return new WindowSwitchResult(false, false, target, null);

        if (IsIconic(candidate))
            _ = ShowWindow(candidate, ShowNormal);

        var activated = SetForegroundWindow(candidate);
        if (activated)
        {
            Interlocked.Exchange(ref _cachedHandle, candidate);
            return new WindowSwitchResult(true, true, target, Describe(candidate));
        }

        return new WindowSwitchResult(false, true, target, Describe(candidate));
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
}
