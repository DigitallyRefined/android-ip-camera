@echo off
REM Android IP Camera - Build Setup Script
REM This script helps set up the build environment

echo ============================================
echo Android IP Camera - Build Setup
echo ============================================
echo.
echo This script will help you set up the build environment.
echo.
echo REQUIREMENTS:
echo 1. Java JDK 11 or higher
echo 2. Android SDK (via Android Studio)
echo.
echo Press any key to check current setup...
pause >nul

echo.
echo Checking Java installation...
java -version 2>nul
if %errorlevel% neq 0 (
    echo.
    echo ❌ Java is not installed or not in PATH!
    echo.
    echo Please install Java JDK:
    echo 1. Download from: https://adoptium.net/temurin/releases/
    echo 2. Choose JDK 11 or 17 (LTS versions)
    echo 3. Install and add to PATH
    echo 4. Set JAVA_HOME environment variable
    echo.
    echo Example: JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-11.x.x
    echo.
    pause
    exit /b 1
) else (
    echo ✅ Java is installed
)

echo.
echo Checking Android SDK...
if exist "%ANDROID_HOME%" (
    echo ✅ ANDROID_HOME is set: %ANDROID_HOME%
) else if exist "%ANDROID_SDK_ROOT%" (
    echo ✅ ANDROID_SDK_ROOT is set: %ANDROID_SDK_ROOT%
) else (
    echo.
    echo ❌ Android SDK not found!
    echo.
    echo Please install Android Studio:
    echo 1. Download from: https://developer.android.com/studio
    echo 2. Install Android Studio
    echo 3. Open Android Studio and complete SDK setup
    echo 4. The SDK will be at: C:\Users\%USERNAME%\AppData\Local\Android\Sdk
    echo 5. Set ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
    echo.
    pause
    exit /b 1
)

echo.
echo Generating personal certificate...
call setup.bat
if %errorlevel% neq 0 (
    echo.
    echo ❌ Certificate generation failed!
    echo You can still build, but the app will need certificate setup.
    echo.
)

echo.
echo ============================================
echo Ready to build!
echo ============================================
echo.
echo Run this command to build the APK:
echo   .\gradlew.bat assembleDebug
echo.
echo The APK will be created at:
echo   app\build\outputs\apk\debug\app-debug.apk
echo.
pause