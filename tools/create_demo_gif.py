from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "demo.gif"
W, H = 960, 540


def font(size: int, bold: bool = False):
    name = "msyhbd.ttc" if bold else "msyh.ttc"
    return ImageFont.truetype(str(Path("C:/Windows/Fonts") / name), size)


def rounded(draw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def frame(phone_text, pc_text, caret=None, selection=None, caption="", pulse=0):
    im = Image.new("RGB", (W, H), "#0b1020")
    d = ImageDraw.Draw(im)

    d.text((42, 28), "PhoneInput", font=font(30, True), fill="#f8fafc")
    d.text((42, 72), "同一 Wi-Fi · 手机直接输入到 Windows", font=font(18), fill="#94a3b8")

    # Phone
    rounded(d, (55, 120, 390, 500), 38, "#111827", "#334155", 3)
    rounded(d, (75, 145, 370, 475), 24, "#f8fafc")
    d.text((98, 170), "手机输入", font=font(18, True), fill="#0f172a")
    d.text((98, 204), "已连接", font=font(14), fill="#16a34a")
    rounded(d, (95, 245, 350, 360), 16, "#ffffff", "#94a3b8", 2)
    d.multiline_text((112, 272), phone_text, font=font(23), fill="#0f172a", spacing=8)
    rounded(d, (95, 388, 350, 445), 15, "#2563eb", None)
    d.text((195, 403), "发送", font=font(20, True), fill="white")

    # PC
    rounded(d, (440, 120, 910, 420), 18, "#1e293b", "#475569", 3)
    rounded(d, (465, 155, 885, 385), 12, "#ffffff")
    d.text((490, 180), "Windows 当前输入框", font=font(17), fill="#64748b")
    x, y = 492, 254
    f = font(27)
    if selection:
        a, b = selection
        before, selected, after = pc_text[:a], pc_text[a:b], pc_text[b:]
        bx = x + d.textlength(before, font=f)
        sw = d.textlength(selected, font=f)
        d.rectangle((bx, y - 2, bx + sw, y + 38), fill="#bfdbfe")
        d.text((x, y), before, font=f, fill="#0f172a")
        d.text((bx, y), selected, font=f, fill="#0f172a")
        d.text((bx + sw, y), after, font=f, fill="#0f172a")
    else:
        d.text((x, y), pc_text, font=f, fill="#0f172a")
    if caret is not None:
        cx = x + d.textlength(pc_text[:caret], font=f)
        d.rectangle((cx, y - 3, cx + 3, y + 39), fill="#2563eb")

    # Connection
    d.line((390, 300, 440, 300), fill="#38bdf8", width=5)
    r = 7 + pulse
    d.ellipse((408 - r, 300 - r, 408 + r, 300 + r), fill="#38bdf8")

    rounded(d, (440, 445, 910, 500), 16, "#172554")
    d.text((470, 460), caption, font=font(19, True), fill="#dbeafe")
    return im


steps = [
    ("", "", 0, None, "手机输入，电脑立即出现文字"),
    ("躺着也能", "躺着也能", 5, None, "中文输入法与 Emoji 都支持"),
    ("躺着也能打字 😊", "躺着也能打字 😊", 9, None, "无需触碰电脑键盘"),
    ("躺着也能打字 😊", "躺着也能打字 😊", None, (2, 6), "手机点选文字，电脑选区同步"),
    ("躺着轻松打字 😊", "躺着轻松打字 😊", 6, None, "选中后可直接替换"),
]

frames = []
for i, (phone, pc, caret, selection, caption) in enumerate(steps):
    for pulse in (0, 2, 4, 2):
        frames.append(frame(phone, pc, caret, selection, caption, pulse))

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
frames[0].save(
    OUTPUT,
    save_all=True,
    append_images=frames[1:],
    duration=280,
    loop=0,
    optimize=True,
)
print(OUTPUT)
