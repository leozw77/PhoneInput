@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo [PhoneInputEnhanced] Building Native 1.4.0 Debug APK...

if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if not defined JAVA_HOME (
  echo ERROR: JAVA_HOME not found. Install Android Studio or set JAVA_HOME to JDK 17.
  exit /b 1
)
if not defined ANDROID_HOME (
  echo ERROR: Android SDK not found. Open Android Studio once or set ANDROID_HOME.
  exit /b 1
)

if not exist local.properties (
  powershell -NoProfile -Command "$p=$env:ANDROID_HOME -replace '\\','/'; Set-Content -LiteralPath 'local.properties' -Value ('sdk.dir='+$p) -Encoding ASCII"
)

set "GRADLE_CMD="
if exist gradlew.bat set "GRADLE_CMD=%CD%\gradlew.bat"
if not defined GRADLE_CMD for /f "delims=" %%G in ('where gradle.bat 2^>nul') do if not defined GRADLE_CMD set "GRADLE_CMD=%%G"
if not defined GRADLE_CMD for /f "delims=" %%G in ('powershell -NoProfile -Command "$f=Get-ChildItem \"$env:USERPROFILE\.gradle\wrapper\dists\gradle-*-bin\*\gradle-*\bin\gradle.bat\" -File -ErrorAction SilentlyContinue ^| Sort-Object LastWriteTime -Descending ^| Select-Object -First 1 -ExpandProperty FullName; if($f){$f}"') do set "GRADLE_CMD=%%G"

if not defined GRADLE_CMD (
  echo ERROR: Gradle executable was not found. Open the project in Android Studio and build once, or generate a Gradle Wrapper.
  exit /b 1
)

echo JAVA_HOME=%JAVA_HOME%
echo ANDROID_HOME=%ANDROID_HOME%
echo Gradle=%GRADLE_CMD%
call "%GRADLE_CMD%" -p "%CD%" assembleDebug
if errorlevel 1 exit /b %errorlevel%

echo.
echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
exit /b 0
