# 稳定核心状态

PhoneInputEnhanced 的原始 Windows 文字输入核心源自 `v1.2.5`。历史交付没有包含 C# 项目，因此 preview.9 继续使用用户实机验证的 preview.2 核心二进制，并通过 SHA-256 锁定和等长元数据补丁升级版本。

preview.9 新增功能的完整源码位于本包的 Go、HTML 和 JavaScript 文件中。核心二进制不被反编译重写，避免破坏原文字输入、窗口识别和 UI Automation 行为。
