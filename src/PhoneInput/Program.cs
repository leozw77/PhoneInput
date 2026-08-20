using PhoneInput;

// A named kernel mutex is released by Windows even if the process crashes.
// Use the global namespace so elevated and non-elevated launches still share
// the same single-instance lock.
const string SingleInstanceName = @"Global\PhoneInputEnhanced.SingleInstance";
using var singleInstance = new Mutex(initiallyOwned: true, SingleInstanceName, out var ownsInstance);
if (!ownsInstance)
    return;

PhoneInputLog.Start();
ApplicationConfiguration.Initialize();
try
{
    Application.Run(new TrayApplicationContext(args));
}
finally
{
    await PhoneInputLog.StopAsync();
}
