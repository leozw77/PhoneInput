using System.IO;
using Microsoft.Win32;

namespace PhoneInput;

internal static class StartupManager
{
    private const string RegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "PhoneInputEnhanced";

    public static bool IsEnabled
    {
        get
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegistryPath, false);
                return key?.GetValue(ValueName) is string value &&
                       TryGetExecutablePath(value, out var executable) &&
                       File.Exists(executable);
            }
            catch { return false; }
        }
    }

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RegistryPath, true)
            ?? throw new InvalidOperationException("Unable to open Windows startup settings.");
        if (enabled)
        {
            var executable = GetCurrentExecutablePath();
            key.SetValue(ValueName, $"\"{executable}\" --startup", RegistryValueKind.String);
        }
        else
        {
            key.DeleteValue(ValueName, false);
        }
    }

    private static string GetCurrentExecutablePath()
    {
        var executable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(executable) ||
            !executable.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
            Path.GetFileNameWithoutExtension(executable).Equals("dotnet", StringComparison.OrdinalIgnoreCase) ||
            !File.Exists(executable))
            throw new InvalidOperationException("Enable startup from PhoneInputEnhanced.exe, not from a DLL or dotnet.exe.");

        return Path.GetFullPath(executable);
    }

    private static bool TryGetExecutablePath(string value, out string executable)
    {
        executable = string.Empty;
        var text = value.Trim();
        if (text.Length == 0) return false;

        if (text[0] == '"')
        {
            var endQuote = text.IndexOf('"', 1);
            if (endQuote <= 1) return false;
            executable = text[1..endQuote];
        }
        else
        {
            var separator = text.IndexOf(' ');
            executable = separator > 0 ? text[..separator] : text;
        }

        return executable.EndsWith(".exe", StringComparison.OrdinalIgnoreCase);
    }
}
