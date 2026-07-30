using QRCoder;

namespace PhoneInput;

internal sealed class QrCodeForm : Form
{
    public QrCodeForm(string address)
    {
        Text = "手机扫码连接";
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(390, 470);
        BackColor = Color.White;

        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(address, QRCodeGenerator.ECCLevel.Q);
        using var code = new QRCode(data);
        var bitmap = code.GetGraphic(12, Color.Black, Color.White, true);

        var picture = new PictureBox
        {
            Image = bitmap,
            SizeMode = PictureBoxSizeMode.Zoom,
            Location = new Point(35, 25),
            Size = new Size(320, 320)
        };
        picture.Disposed += (_, _) => bitmap.Dispose();

        var label = new Label
        {
            Text = address,
            ForeColor = Color.FromArgb(30, 41, 59),
            TextAlign = ContentAlignment.MiddleCenter,
            AutoEllipsis = true,
            Location = new Point(20, 355),
            Size = new Size(350, 34),
            Font = new Font("Segoe UI", 10)
        };
        var copy = new Button
        {
            Text = "复制地址",
            Location = new Point(95, 405),
            Size = new Size(95, 34)
        };
        copy.Click += (_, _) =>
        {
            Clipboard.SetText(address);
            copy.Text = "已复制";
        };
        var close = new Button
        {
            Text = "关闭",
            DialogResult = DialogResult.OK,
            Location = new Point(200, 405),
            Size = new Size(95, 34)
        };

        Controls.AddRange(new Control[] { picture, label, copy, close });
        AcceptButton = close;
    }
}
