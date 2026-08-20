# Current native release: 1.4.0

preview.10 adds a Windows bottom image relay tray for the existing phone→PC image upload path. Mouse/gesture behavior remains frozen. Android voice relay behavior from preview.9-buildfix1 is unchanged.

Windows image workflow: phone screenshot/share → Host upload → `PhoneInputImageTray.exe` → newest image shown first → drag thumbnail as real file (`CF_HDROP`) into ChatGPT/Chrome.

The main touchpad ChatGPT / Chrome / 微信 task-window controls remain intentionally present. Browser/CRX deep readback stays deferred.

## Stable Release

`1.4.0` 正式作为当前 Native 主线稳定版。该版本由 `1.4.0-native-preview.10` 功能冻结候选基线提升而来；后续优先修复实机 Bug、兼容性和正式发布问题，权威功能摘要见 `CURRENT_VERSION_SUMMARY.md`。
