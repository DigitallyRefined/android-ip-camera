@echo off
REM Android IP Camera - Quick Build Script

echo Building Android IP Camera APK...
echo.

REM Check if Java is available
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Java is not installed or not in PATH
    echo Run 'build-setup.bat' first to set up your environment
    pause
    exit /b 1
)

REM Check for certificate
if not exist "app\src\main\assets\personal_certificate.p12" (
    echo WARNING: Personal certificate not found.
    echo The app will prompt you to run setup.bat after installation.
    echo.
)

REM Build the APK
echo Starting Gradle build...
call .\gradlew.bat assembleDebug

if %errorlevel% equ 0 (
    echo.
    echo ============================================
    echo ✅ BUILD SUCCESSFUL!
    echo ============================================
    echo.
    echo APK created at:
    echo app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo Copy this file to your Android phone and install it!
    echo.
) else (
    echo.
    echo ❌ BUILD FAILED!
    echo.
    echo Check the error messages above.
    echo Make sure you have:
    echo - Java JDK installed and in PATH
    echo - Android SDK installed (via Android Studio)
    echo - ANDROID_HOME environment variable set
    echo.
)

pause