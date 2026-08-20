# Known Issues - 1.4.0 Stable

- Windows ImageTray has been cross-built but cannot be interactively exercised in this Linux build environment. Real Windows acceptance must verify PNG/JPEG rendering and drag/drop into the exact ChatGPT/browser targets in use.
- GDI+ decoding is intended primarily for PNG/JPEG/BMP/GIF screenshots/images; exotic codecs may depend on Windows codecs.
- Phone→PC file transfer still uses the current Downloads path implementation and does not yet resolve a redirected Windows Known Folder.
- Browser/CRX deep input readback remains deferred.
- Third-party Android IMEs differ in composing/final-text behavior; preview.9-buildfix1 send sequencing remains the current voice relay implementation.
- Automatic clipboard sync remains removed; clipboard transfer is manual.
