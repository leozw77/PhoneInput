using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace PhoneInput;

internal sealed class TrayApplicationContext : ApplicationContext
{
    // Keep the service on a dedicated five-digit port so it is less likely to
    // collide with another local development service.
    private const int DefaultPort = 51876;
    private readonly NotifyIcon _icon;
    private readonly CancellationTokenSource _shutdown = new();
    private readonly System.Windows.Forms.Timer _foregroundTimer;
    private readonly int _port;
    private readonly IReadOnlyList<string> _urls;
    private WebApplication? _server;

    public TrayApplicationContext(string[] args)
    {
        _port = ReadPort(args);
        _urls = GetLanAddresses(_port);
        PhoneInputLog.Info("startup", $"port={_port}; lanUrls={string.Join(',', _urls)}");

        var menu = new ContextMenuStrip();
        menu.Items.Add("打开诊断日志", null, (_, _) => OpenLog());
        menu.Items.Add("手机输入：正在启动", null, (_, _) => ShowAddress())!.Name = "status";
        menu.Items.Add("显示连接二维码", null, (_, _) => ShowQrCode());
        menu.Items.Add("显示访问地址", null, (_, _) => ShowAddress());
        menu.Items.Add("复制访问地址", null, (_, _) => CopyAddress());
        var startupItem = new ToolStripMenuItem("开机自动启动")
        {
            Checked = StartupManager.IsEnabled,
            CheckOnClick = true
        };
        startupItem.Click += (_, _) => ToggleStartup(startupItem);
        menu.Items.Add(startupItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, async (_, _) => await ExitAsync());

        _icon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "手机输入到电脑",
            ContextMenuStrip = menu,
            Visible = true
        };
        _icon.DoubleClick += (_, _) => ShowAddress();

        // Sample the desktop from the interactive WinForms thread. The web
        // server runs on thread-pool threads and must not be the source of
        // truth for foreground-window state.
        ForegroundWindow.Refresh();
        _foregroundTimer = new System.Windows.Forms.Timer { Interval = 100 };
        _foregroundTimer.Tick += (_, _) => ForegroundWindow.Refresh();
        _foregroundTimer.Start();

        _ = StartServerAsync();
    }

    private async Task StartServerAsync()
    {
        try
        {
            var builder = WebApplication.CreateSlimBuilder();
            builder.Logging.ClearProviders();
            builder.WebHost.UseUrls(GetListenUrls(_port).ToArray());
            builder.Services.ConfigureHttpJsonOptions(options =>
                options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase);

            var app = builder.Build();
            _server = app;
            var input = new InputSender();

            app.MapGet("/", () => Results.Content(MobilePage.Html, "text/html; charset=utf-8"));
            app.MapGet("/manifest.webmanifest", () =>
                Results.Content(MobilePage.Manifest, "application/manifest+json; charset=utf-8"));
            app.MapGet("/sw.js", () =>
                Results.Content(MobilePage.ServiceWorker, "application/javascript; charset=utf-8"));
            app.MapGet("/icon.svg", () =>
                Results.Content(MobilePage.IconSvg, "image/svg+xml; charset=utf-8"));
            app.MapGet("/api/status", () =>
            {
                var target = ForegroundWindow.GetDescription();
                var targetId = ForegroundWindow.GetId();
                var targetType = ForegroundWindow.GetTargetKind();
                return Results.Ok(new { connected = true, target, targetId, targetType });
            });
            app.MapGet("/api/input-state", (string? targetId, string? source, bool? copyBack) =>
            {
                var expectedTargetId = targetId?.Trim() ?? string.Empty;
                if (expectedTargetId.Length == 0)
                    return Results.BadRequest(new { error = "targetId is required", reason = "target-id-required" });

                var requestSource = string.Equals(source, "manual", StringComparison.OrdinalIgnoreCase)
                    ? "manual"
                    : "automatic";
                var allowClipboardFallback = requestSource == "manual" && copyBack == true;
                PhoneInputLog.Info(
                    "sync-read",
                    $"phase=start; requestSource={requestSource}; targetId={expectedTargetId}; copyBack={allowClipboardFallback}");

                var state = DesktopInputStateReader.ReadCurrent(
                    expectedTargetId,
                    allowGoogleSearchComboBox: requestSource == "manual",
                    allowClipboardFallback);
                var textLength = state.Supported ? state.Text.Length : 0;
                PhoneInputLog.Info(
                    "sync-read",
                    $"phase=end; result={(state.Supported ? "success" : "failed")}; requestSource={requestSource}; targetId={expectedTargetId}; actualTargetId={state.TargetId}; control={state.ControlId}; reason={state.Reason}; source={state.Source}; textLength={textLength}");

                if (state.Reason == "target-mismatch")
                    return Results.Conflict(state);
                return Results.Ok(state);
            });
            app.MapPost("/api/window-switch/{target}", async (string target, CancellationToken cancellationToken) =>
            {
                var result = await ForegroundWindow.TryActivateAsync(target, cancellationToken);
                if (result.Success)
                    return Results.Ok(result);
                if (!result.Found)
                    return Results.NotFound(new { error = $"{target} 未启动或没有可切换的窗口" });
                return Results.Conflict(new { error = $"{target} 窗口切换失败，请稍后重试" });
            });
            app.MapPost("/api/text", async (TextRequest request, CancellationToken cancellationToken) =>
            {
                if (string.IsNullOrEmpty(request.Text))
                    return Results.BadRequest(new { error = "文字不能为空" });
                if (request.Text.Length > 20_000)
                    return Results.BadRequest(new { error = "一次最多发送 20000 个字符" });

                if (!IsCurrentTarget(request.TargetId))
                    return Results.Conflict(new { error = "target changed" });
                Func<bool>? targetValidator = request.TargetId is null ? null : () => IsCurrentTarget(request.TargetId);
                try
                {
                    await input.SendTextAsync(request.Text, Math.Clamp(request.DelayMs ?? 3, 0, 50), cancellationToken, targetValidator);
                }
                catch (InputTargetChangedException)
                {
                    return Results.Conflict(new { error = "target changed" });
                }
                if (request.EnterAfter == true)
                {
                    try { await input.SendKeyAsync("enter", cancellationToken, targetValidator); }
                    catch (InputTargetChangedException)
                    {
                        return Results.Conflict(new { error = "target changed" });
                    }
                }
                return Results.Ok(new { sent = request.Text.Length });
            });
            app.MapPost("/api/key/{key}", async (string key, string? targetId, CancellationToken cancellationToken) =>
            {
                if (!InputSender.IsSupportedKey(key))
                    return Results.BadRequest(new { error = "不支持这个按键" });
                if (!IsCurrentTarget(targetId))
                    return Results.Conflict(new { error = "target changed" });
                try { await input.SendKeyAsync(key, cancellationToken, () => IsCurrentTarget(targetId)); }
                catch (InputTargetChangedException)
                {
                    return Results.Conflict(new { error = "target changed" });
                }
                return Results.Ok();
            });
            app.MapPost("/api/selection", async (SelectionRequest request, CancellationToken cancellationToken) =>
            {
                if (string.IsNullOrEmpty(request.TargetId) ||
                    !string.Equals(request.TargetId, ForegroundWindow.GetId(), StringComparison.OrdinalIgnoreCase))
                    return Results.Conflict(new { error = "电脑目标窗口已经变化，已暂停光标同步" });
                if (request.Start < 0 || request.End < request.Start || request.End > 20_000)
                    return Results.BadRequest(new { error = "光标位置无效" });

                try { await input.SetSelectionAsync(request.Start, request.End, cancellationToken, () => IsCurrentTarget(request.TargetId)); }
                catch (InputTargetChangedException)
                {
                    return Results.Conflict(new { error = "target changed" });
                }
                return Results.Ok();
            });

            await app.StartAsync(_shutdown.Token);
            SetStatus("手机输入：运行中");
            await app.WaitForShutdownAsync(_shutdown.Token);
        }
        catch (OperationCanceledException) when (_shutdown.IsCancellationRequested) { }
        catch (Exception ex)
        {
            PhoneInputLog.Error("startup", ex);
            SetStatus("手机输入：启动失败");
            _icon.ShowBalloonTip(5000, "手机输入启动失败", ex.Message, ToolTipIcon.Error);
        }
    }

    private void SetStatus(string text)
    {
        if (_icon.ContextMenuStrip?.InvokeRequired == true)
        {
            _icon.ContextMenuStrip.BeginInvoke(() => SetStatus(text));
            return;
        }
        if (_icon.ContextMenuStrip?.Items["status"] is ToolStripItem item) item.Text = text;
    }

    private void OpenLog()
    {
        PhoneInputLog.Info("diagnostics", "open-log");
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "notepad.exe",
                Arguments = $"\"{PhoneInputLog.LogPath}\"",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            PhoneInputLog.Error("diagnostics", ex);
            MessageBox.Show(
                $"日志路径：\n{PhoneInputLog.LogPath}\n\n{ex.Message}",
                "无法打开诊断日志",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }

    private void ShowAddress()
    {
        var address = PreferredAddress;
        MessageBox.Show(
            $"请让手机和电脑连接同一 Wi-Fi，然后在手机浏览器打开：\n\n{address}\n\n" +
            "程序平时只驻留在系统托盘，不会抢占输入焦点。\n" +
            "如果手机打不开，请允许 Windows 防火墙的“专用网络”访问。",
            "手机输入到电脑",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
    }

    private void CopyAddress()
    {
        try
        {
            Clipboard.SetText(PreferredAddress);
            _icon.ShowBalloonTip(1500, "已复制", PreferredAddress, ToolTipIcon.Info);
        }
        catch { }
    }

    private void ShowQrCode()
    {
        using var form = new QrCodeForm(PreferredAddress);
        form.ShowDialog();
    }

    private void ToggleStartup(ToolStripMenuItem item)
    {
        try
        {
            StartupManager.SetEnabled(item.Checked);
            _icon.ShowBalloonTip(
                1800,
                "手机输入到电脑",
                item.Checked ? "已开启开机自动启动" : "已关闭开机自动启动",
                ToolTipIcon.Info);
        }
        catch (Exception ex)
        {
            item.Checked = !item.Checked;
            MessageBox.Show(ex.Message, "无法修改开机启动", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private string PreferredAddress => _urls.FirstOrDefault() ?? $"http://localhost:{_port}";

    private async Task ExitAsync()
    {
        _icon.Visible = false;
        _foregroundTimer.Stop();
        _foregroundTimer.Dispose();
        _shutdown.Cancel();
        if (_server is not null)
        {
            try { await _server.StopAsync(TimeSpan.FromSeconds(2)); } catch { }
            await _server.DisposeAsync();
        }
        _icon.Dispose();
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _shutdown.Cancel();
            _foregroundTimer.Stop();
            _foregroundTimer.Dispose();
            _shutdown.Dispose();
            _icon.Dispose();
        }
        base.Dispose(disposing);
    }

    private static int ReadPort(string[] args)
    {
        var index = Array.IndexOf(args, "--port");
        return index >= 0 && index + 1 < args.Length &&
               int.TryParse(args[index + 1], out var port) && port is > 0 and <= 65535
            ? port : DefaultPort;
    }

    private static IReadOnlyList<string> GetListenUrls(int port)
    {
        var urls = GetPrivateIpv4Addresses()
            .Select(ip => $"http://{ip}:{port}")
            .ToList();
        urls.Add($"http://127.0.0.1:{port}");
        return urls;
    }

    private static IReadOnlyList<string> GetLanAddresses(int port) =>
        GetPrivateIpv4Addresses().Select(ip => $"http://{ip}:{port}").ToArray();

    private static IEnumerable<string> GetPrivateIpv4Addresses() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                        n.NetworkInterfaceType is not NetworkInterfaceType.Loopback and
                            not NetworkInterfaceType.Tunnel)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork &&
                        IsPrivate(a.Address))
            .Select(a => a.Address.ToString())
            .Distinct();

    private static bool IsPrivate(IPAddress address)
    {
        var b = address.GetAddressBytes();
        return b[0] == 10 || b[0] == 192 && b[1] == 168 ||
               b[0] == 172 && b[1] is >= 16 and <= 31;
    }

    private static bool IsCurrentTarget(string? targetId) =>
        targetId is null || string.Equals(targetId, ForegroundWindow.GetId(), StringComparison.OrdinalIgnoreCase);

    private sealed record TextRequest(string Text, int? DelayMs, bool? EnterAfter, string? TargetId);
    private sealed record SelectionRequest(int Start, int End, string TargetId);
}
