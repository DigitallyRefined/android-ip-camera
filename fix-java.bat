@echo off
REM Fix Java environment for Android builds

echo ============================================
echo Android IP Camera - Java Setup Fix
echo ============================================
echo.

echo Checking Java installation...
java -version
if %errorlevel% neq 0 (
    echo.
    echo ❌ Java is not available.
    echo Please install Java JDK from: https://adoptium.net/temurin/releases/
    echo.
    pause
    exit /b 1
)

echo.
echo ✅ Java is installed. Setting JAVA_HOME...

REM Try to find Java home from registry (common for Oracle JDK)
for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\JavaSoft\Java Development Kit" /v CurrentVersion 2^>nul') do set "JAVA_VERSION=%%b"
if defined JAVA_VERSION (
    for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\JavaSoft\Java Development Kit\%JAVA_VERSION%" /v JavaHome 2^>nul') do set "JAVA_HOME=%%b"
)

REM If not found in registry, try common paths
if not defined JAVA_HOME (
    if exist "C:\Program Files\Java\jdk*" (
        for /d %%i in ("C:\Program Files\Java\jdk*") do set "JAVA_HOME=%%i"
    )
)

if not defined JAVA_HOME (
    if exist "C:\Program Files\Eclipse Adoptium\jdk*" (
        for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk*") do set "JAVA_HOME=%%i"
    )
)

if defined JAVA_HOME (
    echo Found JAVA_HOME: %JAVA_HOME%
    setx JAVA_HOME "%JAVA_HOME%" /M
    echo ✅ JAVA_HOME set globally
) else (
    echo.
    echo ❌ Could not automatically detect JAVA_HOME
    echo.
    echo Please set JAVA_HOME manually:
    echo 1. Find your Java installation directory
    echo 2. Run: setx JAVA_HOME "C:\path\to\your\jdk" /M
    echo 3. Restart command prompt and try again
    echo.
    echo Common locations:
    echo - C:\Program Files\Java\jdk-xx.x.x
    echo - C:\Program Files\Eclipse Adoptium\jdk-xx.x.x
    echo.
    pause
    exit /b 1
)

echo.
echo Testing Java setup...
java -version
if %errorlevel% neq 0 (
    echo ❌ Java test failed
    pause
    exit /b 1
)

echo.
echo ✅ Java setup complete!
echo.
echo Now you can build the APK with:
echo   .\build.bat
echo.
pause