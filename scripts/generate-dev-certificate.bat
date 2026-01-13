@echo off
REM Android IP Camera - Development Certificate Generator
REM This script generates a self-signed certificate for development/testing
REM DO NOT USE IN PRODUCTION - Generate your own certificates for production use

echo =====================================================
echo Android IP Camera - Development Certificate Setup
echo =====================================================
echo.
echo This will generate a development certificate for TESTING ONLY.
echo The certificate will be placed in the app's assets directory.
echo.
echo SECURITY WARNINGS:
echo - This certificate is for DEVELOPMENT/TESTING ONLY
echo - Uses a known password (devcamera123) for convenience
echo - Anyone with the APK can access the private key
echo - Generate unique certificates for production use
echo.
echo Press any key to continue...
pause

REM Check if OpenSSL is available
openssl version >nul 2>&1
if errorlevel 1 (
    echo ERROR: OpenSSL is not installed or not in PATH
    echo Please install OpenSSL from: https://slproweb.com/products/Win32OpenSSL.html
    pause
    exit /b 1
)

REM Create certificates directory
if not exist "certificates" mkdir certificates

REM Generate unique certificate with timestamp
for /f "tokens=2 delims==" %%a in ('wmic OS Get localdatetime /value') do set "dt=%%a"
set "TIMESTAMP=%dt:~0,8%_%dt:~8,6%"
set "CERT_NAME=dev_camera_%TIMESTAMP%"

echo Generating development certificate: %CERT_NAME%
echo.

REM Generate private key with encryption (no -nodes flag)
openssl req -x509 -newkey rsa:2048 -keyout "certificates\%CERT_NAME%.key" -out "certificates\%CERT_NAME%.crt" -days 365 -subj "/C=DEV/ST=Development/L=Testing/O=Android IP Camera/OU=Development/CN=localhost"

REM Generate PKCS12 format with known dev password
openssl pkcs12 -export -in "certificates\%CERT_NAME%.crt" -inkey "certificates\%CERT_NAME%.key" -out "certificates\%CERT_NAME%.p12" -name "%CERT_NAME%" -password pass:devcamera123 -legacy

REM Copy to assets directory with consistent naming
if not exist "..\app\src\main\assets" mkdir "..\app\src\main\assets"
copy "certificates\%CERT_NAME%.p12" "..\app\src\main\assets\personal_certificate.p12"

echo.
echo =====================================================
echo Development Certificate Generation Complete!
echo =====================================================
echo.
echo Files created:
echo   - certificates\%CERT_NAME%.key (Encrypted private key)
echo   - certificates\%CERT_NAME%.crt (Certificate)
echo   - certificates\%CERT_NAME%.p12 (PKCS12 format)
echo   - app\src\main\assets\personal_certificate.p12 (App asset)
echo.
echo Certificate Password: devcamera123
echo.
echo NEXT STEPS:
echo 1. The certificate is now included in the app
echo 2. In app Settings, set certificate password to: devcamera123
echo 3. Configure strong username/password for authentication
echo 4. Build and install the app
echo.
echo SECURITY WARNING:
echo - This certificate is for DEVELOPMENT/TESTING ONLY
echo - Uses known password for convenience
echo - Anyone with the APK has the same certificate
echo - Generate unique certificates for production use
echo =====================================================
echo.
pause