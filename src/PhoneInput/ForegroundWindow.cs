using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace PhoneInput;

internal static class ForegroundWindow
{
    public static string GetId()
    {
        var handle = GetForegroundWindow();
        return handle == IntPtr.Zero ? string.Empty : handle.ToInt64().ToString("X");
    }

    public static string GetDescription()
    {
        var handle = GetForegroundWindow();
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

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr handle, StringBuilder text, int count);

    [DllImport("user32.dll")]
    private static extern int GetWindowTextLength(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);
}
