using System.IO;
using System.Threading.Channels;

namespace PhoneInput;

internal static class PhoneInputLog
{
    private const int QueueCapacity = 512;
    private const long MaxLogBytes = 5 * 1024 * 1024;
    private static readonly Channel<string> Entries = Channel.CreateBounded<string>(
        new BoundedChannelOptions(QueueCapacity)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        });
    private static readonly object StartGate = new();
    private static Task? _writerTask;
    private static string _logPath = string.Empty;

    public static string LogPath => _logPath;

    public static void Start()
    {
        lock (StartGate)
        {
            if (_writerTask is not null)
                return;

            _logPath = GetLogPath();
            _writerTask = Task.Run(WriteLoopAsync);
        }

        Info("startup", $"version={typeof(PhoneInputLog).Assembly.GetName().Version}; logPath={_logPath}");
    }

    public static async Task StopAsync()
    {
        Task? writer;
        lock (StartGate)
        {
            writer = _writerTask;
            Entries.Writer.TryComplete();
        }

        if (writer is not null)
        {
            try { await writer.ConfigureAwait(false); }
            catch { }
        }
    }

    public static void Info(string stage, string message) => Enqueue("INFO", stage, message);

    public static void Warn(string stage, string message) => Enqueue("WARN", stage, message);

    public static void Error(string stage, Exception exception) =>
        Enqueue("ERROR", stage, $"{exception.GetType().Name}: {exception.Message}");

    private static void Enqueue(string level, string stage, string message)
    {
        var line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] [{level}] [{Sanitize(stage)}] {Sanitize(message)}";
        _ = Entries.Writer.TryWrite(line);
    }

    private static async Task WriteLoopAsync()
    {
        try
        {
            var directory = Path.GetDirectoryName(_logPath);
            if (!string.IsNullOrWhiteSpace(directory))
                Directory.CreateDirectory(directory);
            RotateIfNeeded();

            await using var stream = new FileStream(
                _logPath,
                FileMode.Append,
                FileAccess.Write,
                FileShare.ReadWrite,
                bufferSize: 4096,
                useAsync: true);
            await using var writer = new StreamWriter(stream) { AutoFlush = true };

            await foreach (var line in Entries.Reader.ReadAllAsync())
                await writer.WriteLineAsync(line);
        }
        catch
        {
            // Logging must never affect input, focus, or shutdown behavior.
        }
    }

    private static void RotateIfNeeded()
    {
        try
        {
            if (!File.Exists(_logPath) || new FileInfo(_logPath).Length < MaxLogBytes)
                return;

            var previous = _logPath + ".1";
            if (File.Exists(previous))
                File.Delete(previous);
            File.Move(_logPath, previous);
        }
        catch { }
    }

    private static string GetLogPath()
    {
        try
        {
            var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (!string.IsNullOrWhiteSpace(root))
                return Path.Combine(root, "PhoneInputEnhanced", "PhoneInput.log");
        }
        catch { }

        return Path.Combine(AppContext.BaseDirectory, "PhoneInput.log");
    }

    private static string Sanitize(string value) =>
        value.Replace('\r', ' ').Replace('\n', ' ');
}
