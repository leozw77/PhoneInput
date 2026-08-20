param(
    [int]$LifecycleCycles = 30,
    [int]$ScreenCycles = 10,
    [int]$PauseMs = 900,
    [switch]$ToggleWifi,
    [int]$WifiCycles = 5
)
$ErrorActionPreference = 'Stop'
$pkg = 'com.phoneinputenhanced.nativeclient'
$activity = "$pkg/.MainActivity"
function ADB([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args) {
    & adb @Args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Args -join ' ')" }
}
ADB get-state | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $PSScriptRoot "stress-$stamp-logcat.txt"
Write-Host "[1/3] Background/foreground cycles: $LifecycleCycles"
for ($i=1; $i -le $LifecycleCycles; $i++) {
    ADB shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Milliseconds $PauseMs
    ADB shell am start -n $activity | Out-Null
    Start-Sleep -Milliseconds $PauseMs
    if ($i % 5 -eq 0) { Write-Host "  lifecycle $i/$LifecycleCycles" }
}
Write-Host "[2/3] Screen off/on cycles: $ScreenCycles"
for ($i=1; $i -le $ScreenCycles; $i++) {
    ADB shell input keyevent KEYCODE_POWER | Out-Null
    Start-Sleep -Milliseconds ([Math]::Max(1200,$PauseMs))
    ADB shell input keyevent KEYCODE_POWER | Out-Null
    Start-Sleep -Milliseconds 500
    ADB shell input keyevent KEYCODE_MENU | Out-Null
    ADB shell am start -n $activity | Out-Null
    Start-Sleep -Milliseconds $PauseMs
}
if ($ToggleWifi) {
    Write-Host "[3/3] Wi-Fi loss/recovery cycles: $WifiCycles"
    Write-Warning 'This intentionally disconnects Wi-Fi. Run only when safe to interrupt the current LAN session.'
    for ($i=1; $i -le $WifiCycles; $i++) {
        ADB shell svc wifi disable | Out-Null
        Start-Sleep -Seconds 2
        ADB shell svc wifi enable | Out-Null
        Start-Sleep -Seconds 5
        ADB shell am start -n $activity | Out-Null
        Start-Sleep -Milliseconds $PauseMs
    }
} else {
    Write-Host '[3/3] Wi-Fi toggle skipped. Re-run with -ToggleWifi when ready.'
}
& adb logcat -d -v threadtime | Out-File -LiteralPath $log -Encoding utf8
Write-Host "Stress pass completed. Logcat saved to: $log"
Write-Host 'Open the app Diagnostics page and confirm: writerQueue stays low, ACK/heartbeat recover, reconnectCount matches interruptions, and no mouse button remains held.'
