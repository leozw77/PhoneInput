using System.Drawing;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PhoneInputOtpPanel;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new OtpPanel());
    }
}

internal sealed class OtpPanel : Form
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(2) };
    private static readonly string ConfigPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "PhoneInputEnhanced", "otp-panel.json");

    private readonly Label _code = new();
    private readonly Label _source = new();
    private readonly FlowLayoutPanel _snippets = new();
    private readonly FlowLayoutPanel _keyboard = new();
    private readonly System.Windows.Forms.Timer _poll = new() { Interval = 900 };
    private readonly OtpSettings _settings = LoadSettings();
    private bool _qwerty;
    private bool _polling;
    private string _lastEvent = "";

    public OtpPanel()
    {
        Text = "PhoneInput 验证码与常用信息";
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedToolWindow;
        TopMost = true;
        ClientSize = new Size(560, 620);
        BackColor = Color.FromArgb(246, 247, 250);
        Font = new Font("Microsoft YaHei UI", 10F);

        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), ColumnCount = 1, RowCount = 7 };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 76));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 150));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Controls.Add(root);

        root.Controls.Add(new Label { Text = "最近验证码", Dock = DockStyle.Fill, Font = new Font(Font, FontStyle.Bold), ForeColor = Color.FromArgb(38, 48, 69) }, 0, 0);
        _code.Text = "等待手机验证码…";
        _code.Dock = DockStyle.Fill;
        _code.TextAlign = ContentAlignment.MiddleCenter;
        _code.Font = new Font("Segoe UI", 34F, FontStyle.Bold);
        _code.ForeColor = Color.FromArgb(55, 82, 180);
        _code.BackColor = Color.White;
        root.Controls.Add(_code, 0, 1);

        _source.Dock = DockStyle.Fill;
        _source.TextAlign = ContentAlignment.MiddleCenter;
        _source.ForeColor = Color.DimGray;
        root.Controls.Add(_source, 0, 2);

        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = false };
        actions.Controls.Add(AsyncAction("输入验证码", () => SendTextAsync(_code.Text)));
        actions.Controls.Add(Action("复制验证码", () => CopyCode()));
        actions.Controls.Add(Action("编辑常用信息", () => EditSnippets()));
        root.Controls.Add(actions, 0, 3);

        var snippetBox = new GroupBox { Text = "常用信息 · 点击直接输入", Dock = DockStyle.Fill, Padding = new Padding(8, 4, 8, 4) };
        _snippets.Dock = DockStyle.Fill;
        _snippets.AutoScroll = true;
        snippetBox.Controls.Add(_snippets);
        root.Controls.Add(snippetBox, 0, 4);

        var keyboardTitle = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        keyboardTitle.Controls.Add(new Label { Text = "自定义按键（不会调用 Windows 屏幕键盘）", AutoSize = true, Padding = new Padding(0, 8, 10, 0) });
        keyboardTitle.Controls.Add(Action("切换数字 / QWERTY", () => { _qwerty = !_qwerty; DrawKeyboard(); }));
        root.Controls.Add(keyboardTitle, 0, 5);
        _keyboard.Dock = DockStyle.Fill;
        _keyboard.AutoScroll = true;
        _keyboard.Padding = new Padding(2);
        root.Controls.Add(_keyboard, 0, 6);

        RefreshSnippets();
        DrawKeyboard();
        _poll.Tick += async (_, _) => await PollAsync();
        Shown += (_, _) => _poll.Start();
        FormClosed += (_, _) => _poll.Stop();
    }

    protected override bool ShowWithoutActivation => true;
    protected override CreateParams CreateParams
    {
        get { var cp = base.CreateParams; cp.ExStyle |= 0x08000000; return cp; } // WS_EX_NOACTIVATE
    }

    private async Task PollAsync()
    {
        if (_polling) return;
        _polling = true;
        try
        {
            var record = await Http.GetFromJsonAsync<OtpRecord>("http://127.0.0.1:51877/api/otp/latest");
            if (record is null || string.IsNullOrWhiteSpace(record.Code))
            {
                _source.Text = "已连接 PhoneInput · 等待手机验证码";
                return;
            }
            if (record.EventId == _lastEvent) return;
            _lastEvent = record.EventId;
            _code.Text = record.Code;
            _source.Text = $"{record.Sender}  ·  {record.ReceivedAt.ToLocalTime():yyyy-MM-dd HH:mm:ss}";
        }
        catch { _source.Text = "PhoneInput 服务未连接 · 请先启动电脑端 PhoneInput"; }
        finally { _polling = false; }
    }

    private async Task SendTextAsync(string text)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Contains("等待手机")) return;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, "http://127.0.0.1:51877/api/text")
            { Content = JsonContent.Create(new { text, delayMs = 0, enterAfter = false }) };
            request.Headers.TryAddWithoutValidation("Origin", "http://127.0.0.1:51877");
            using var response = await Http.SendAsync(request);
            _source.Text = response.IsSuccessStatusCode ? "已输入到当前目标窗口" : "输入失败 · 检查 PhoneInput 是否已启动";
        }
        catch { _source.Text = "输入失败 · PhoneInput 服务未连接"; }
    }

    private void CopyCode()
    {
        if (!string.IsNullOrWhiteSpace(_code.Text) && !_code.Text.Contains("等待"))
        {
            Clipboard.SetText(_code.Text);
            _source.Text = "验证码已复制";
        }
    }

    private void DrawKeyboard()
    {
        _keyboard.Controls.Clear();
        var keys = _qwerty ? "qwertyuiopasdfghjklzxcvbnm".ToCharArray().Select(char.ToString)
            .Concat(new[] { "@", ".", "-", "_" }).ToArray()
            : new[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0" };
        foreach (var key in keys) _keyboard.Controls.Add(AsyncAction(key, () => SendTextAsync(key), 46));
        _keyboard.Controls.Add(AsyncAction("空格", () => SendTextAsync(" "), 68));
        _keyboard.Controls.Add(AsyncAction("退格", () => SendKeyAsync("backspace"), 68));
        _keyboard.Controls.Add(AsyncAction("回车", () => SendKeyAsync("enter"), 68));
    }

    private async Task SendKeyAsync(string key)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, $"http://127.0.0.1:51877/api/key/{key}") { Content = JsonContent.Create(new { }) };
            request.Headers.TryAddWithoutValidation("Origin", "http://127.0.0.1:51877");
            using var response = await Http.SendAsync(request);
        }
        catch { _source.Text = "PhoneInput 服务未连接"; }
    }

    private void RefreshSnippets()
    {
        _snippets.Controls.Clear();
        foreach (var item in _settings.Items.Where(x => !string.IsNullOrWhiteSpace(x.Value)))
            _snippets.Controls.Add(AsyncAction(item.Label, () => SendTextAsync(item.Value), 112));
    }

    private void EditSnippets()
    {
        using var dialog = new SnippetEditor(_settings);
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        File.WriteAllText(ConfigPath, JsonSerializer.Serialize(_settings, new JsonSerializerOptions { WriteIndented = true }));
        RefreshSnippets();
    }

    private static Button Action(string text, Action action, int width = 150)
    {
        var button = new Button { Text = text, Width = width, Height = 34, Margin = new Padding(4), FlatStyle = FlatStyle.Flat, BackColor = Color.White };
        button.FlatAppearance.BorderColor = Color.FromArgb(215, 219, 228);
        button.Click += (_, _) => action();
        return button;
    }
    private static Button AsyncAction(string text, Func<Task> action, int width = 150) => Action(text, () => _ = action(), width);

    private static OtpSettings LoadSettings()
    {
        try { return JsonSerializer.Deserialize<OtpSettings>(File.ReadAllText(ConfigPath)) ?? new OtpSettings(); }
        catch { return new OtpSettings(); }
    }
    private sealed class OtpRecord
    {
        public string Code { get; set; } = "";
        public string Sender { get; set; } = "";
        public DateTimeOffset ReceivedAt { get; set; }
        public string EventId { get; set; } = "";
    }
}

internal sealed class OtpSettings
{
    public List<Snippet> Items { get; set; } = new()
    {
        new("邮箱", ""), new("QQ号", ""), new("姓名", ""), new("地址", ""), new("手机号", ""), new("其他", "")
    };
}
internal sealed record Snippet(string Label, string Value);

internal sealed class SnippetEditor : Form
{
    private readonly OtpSettings _settings;
    public SnippetEditor(OtpSettings settings)
    {
        _settings = settings;
        Text = "编辑常用信息";
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        ClientSize = new Size(520, 330);
        var grid = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), ColumnCount = 2, RowCount = 7 };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120)); grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        Controls.Add(grid);
        for (var i = 0; i < 6; i++)
        {
            var item = i < settings.Items.Count ? settings.Items[i] : new Snippet("项目 " + (i + 1), "");
            var label = new TextBox { Text = item.Label, Dock = DockStyle.Fill };
            var value = new TextBox { Text = item.Value, Dock = DockStyle.Fill };
            grid.Controls.Add(label, 0, i); grid.Controls.Add(value, 1, i);
            _labels.Add(label); _values.Add(value);
        }
        var save = new Button { Text = "保存", DialogResult = DialogResult.OK, Dock = DockStyle.Right, Width = 90 };
        save.Click += (_, _) => _settings.Items = _labels.Zip(_values, (l, v) => new Snippet(l.Text.Trim(), v.Text)).ToList();
        grid.Controls.Add(save, 1, 6); AcceptButton = save;
    }
    private readonly List<TextBox> _labels = new();
    private readonly List<TextBox> _values = new();
}
