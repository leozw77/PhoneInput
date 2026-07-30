using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace PhoneInput;

internal sealed class InputSender
{
    private const uint InputKeyboard = 1;
    private const uint KeyeventfExtendedkey = 0x0001;
    private const uint KeyeventfKeyup = 0x0002;
    private const uint KeyeventfUnicode = 0x0004;
    private readonly SemaphoreSlim _gate = new(1, 1);

    private static readonly IReadOnlyDictionary<string, ushort> Keys =
        new Dictionary<string, ushort>(StringComparer.OrdinalIgnoreCase)
        {
            ["enter"] = 0x0D,
            ["backspace"] = 0x08,
            ["tab"] = 0x09,
            ["escape"] = 0x1B,
            ["left"] = 0x25,
            ["up"] = 0x26,
            ["right"] = 0x27,
            ["down"] = 0x28,
            ["delete"] = 0x2E
        };

    public static bool IsSupportedKey(string key) =>
        Keys.ContainsKey(key)
        || key.Equals("shift-enter", StringComparison.OrdinalIgnoreCase)
        || key.Equals("screenshot", StringComparison.OrdinalIgnoreCase);

    public async Task SendTextAsync(string text, int delayMs, CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken);
        try
        {
            foreach (var rune in text.EnumerateRunes())
            {
                cancellationToken.ThrowIfCancellationRequested();
                foreach (var character in rune.ToString())
                    SendUnicode(character);
                if (delayMs > 0) await Task.Delay(delayMs, cancellationToken);
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task SendKeyAsync(string key, CancellationToken cancellationToken)
    {
        var shiftEnter = key.Equals("shift-enter", StringComparison.OrdinalIgnoreCase);
        var screenshot = key.Equals("screenshot", StringComparison.OrdinalIgnoreCase);
        ushort virtualKey = 0;
        if (!shiftEnter && !screenshot && !Keys.TryGetValue(key, out virtualKey))
            throw new ArgumentOutOfRangeException(nameof(key));
        await _gate.WaitAsync(cancellationToken);
        try
        {
            if (shiftEnter)
            {
                Send(new[]
                {
                    KeyboardInput(0x10, '\0', 0),
                    KeyboardInput(0x0D, '\0', 0),
                    KeyboardInput(0x0D, '\0', KeyeventfKeyup),
                    KeyboardInput(0x10, '\0', KeyeventfKeyup)
                });
            }
            else if (screenshot)
            {
                Send(new[]
                {
                    KeyboardInput(0x5B, '\0', KeyeventfExtendedkey), // Win down
                    KeyboardInput(0x10, '\0', 0), // Shift down
                    KeyboardInput(0x53, '\0', 0), // S down
                    KeyboardInput(0x53, '\0', KeyeventfKeyup),
                    KeyboardInput(0x10, '\0', KeyeventfKeyup),
                    KeyboardInput(0x5B, '\0', KeyeventfExtendedkey | KeyeventfKeyup)
                });
            }
            else SendVirtualKey(virtualKey);
        }
        finally { _gate.Release(); }
    }

    public async Task SetSelectionAsync(int start, int end, CancellationToken cancellationToken)
    {
        start = Math.Max(0, start);
        end = Math.Max(start, end);
        await _gate.WaitAsync(cancellationToken);
        try
        {
            var inputs = new List<INPUT>(8 + end * 2)
            {
                KeyboardInput(0x11, '\0', 0), // Ctrl down
                KeyboardInput(0x24, '\0', KeyeventfExtendedkey), // Home down
                KeyboardInput(0x24, '\0', KeyeventfExtendedkey | KeyeventfKeyup),
                KeyboardInput(0x11, '\0', KeyeventfKeyup) // Ctrl up
            };
            for (var i = 0; i < start; i++)
            {
                inputs.Add(KeyboardInput(0x27, '\0', KeyeventfExtendedkey));
                inputs.Add(KeyboardInput(0x27, '\0', KeyeventfExtendedkey | KeyeventfKeyup));
            }

            if (end > start)
            {
                inputs.Add(KeyboardInput(0x10, '\0', 0)); // Shift down
                for (var i = start; i < end; i++)
                {
                    inputs.Add(KeyboardInput(0x27, '\0', KeyeventfExtendedkey));
                    inputs.Add(KeyboardInput(0x27, '\0', KeyeventfExtendedkey | KeyeventfKeyup));
                }
                inputs.Add(KeyboardInput(0x10, '\0', KeyeventfKeyup)); // Shift up
            }
            Send(inputs.ToArray());
        }
        finally
        {
            _gate.Release();
        }
    }

    private static void SendUnicode(char value)
    {
        var inputs = new[]
        {
            KeyboardInput(0, value, KeyeventfUnicode),
            KeyboardInput(0, value, KeyeventfUnicode | KeyeventfKeyup)
        };
        Send(inputs);
    }

    private static void SendVirtualKey(ushort key)
    {
        var inputs = new[]
        {
            KeyboardInput(key, '\0', 0),
            KeyboardInput(key, '\0', KeyeventfKeyup)
        };
        Send(inputs);
    }

    private static INPUT KeyboardInput(ushort virtualKey, char scanCode, uint flags) => new()
    {
        type = InputKeyboard,
        data = new InputUnion
        {
            keyboard = new KEYBDINPUT
            {
                virtualKey = virtualKey,
                scanCode = scanCode,
                flags = flags
            }
        }
    };

    private static void Send(INPUT[] inputs)
    {
        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        if (sent != inputs.Length)
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Windows 没有接受输入事件");
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion data;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public KEYBDINPUT keyboard;
        [FieldOffset(0)] public MOUSEINPUT mouse;
        [FieldOffset(0)] public HARDWAREINPUT hardware;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort virtualKey;
        public ushort scanCode;
        public uint flags;
        public uint time;
        public nuint extraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint flags;
        public uint time;
        public nuint extraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HARDWAREINPUT
    {
        public uint message;
        public ushort parameterLow;
        public ushort parameterHigh;
    }
}
