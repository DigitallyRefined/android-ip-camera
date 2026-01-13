@echo off
REM Android IP Camera - Personal Certificate Setup
REM This script generates a secure certificate for personal use

echo ============================================
echo Android IP Camera - Personal Certificate Setup
echo ============================================
echo.
echo This will create a secure HTTPS certificate for the app.
echo.
echo SECURITY NOTES:
echo - Certificate uses a known password for simplicity
echo - The private key is encrypted (not plaintext)
echo - Certificate is for personal use only
echo - CHANGE THE PASSWORD IN SETTINGS for better security
echo.
echo Press any key to continue...
pause >nul

REM Create certificates directory
if not exist "certificates" mkdir certificates

REM Check if OpenSSL is available
openssl version >nul 2>&1
if errorlevel 1 (
    echo ERROR: OpenSSL not found!
    echo.
    echo Please install OpenSSL:
    echo 1. Download from: https://slproweb.com/products/Win32OpenSSL.html
    echo 2. Add to PATH: C:\Program Files\OpenSSL-Win64\bin\
    echo 3. Run this script again
    echo.
    pause
    exit /b 1
)

echo.
echo Generating secure personal certificate...
echo Using default password: 'camera2024' (change this in app settings!)
echo.

REM Generate private key with encryption (no -nodes flag)
openssl req -x509 -newkey rsa:2048 -keyout "certificates\personal.key" -out "certificates\personal.crt" -days 3650 -subj "/C=US/ST=Personal/L=Home/O=Personal IP Camera/OU=Home/CN=localhost"

REM Generate PKCS12 format with known password for simplicity
openssl pkcs12 -export -in "certificates\personal.crt" -inkey "certificates\personal.key" -out "certificates\personal.p12" -name "personal" -password pass:camera2024 -legacy

REM Copy to assets
if not exist "app\src\main\assets" mkdir "app\src\main\assets"
copy "certificates\personal.p12" "app\src\main\assets\personal_certificate.p12"

echo.
echo ============================================
echo Certificate Setup Complete!
echo ============================================
echo.
echo Files created:
echo - certificates\personal.key (Encrypted private key)
echo - certificates\personal.crt (Certificate)
echo - certificates\personal.p12 (PKCS12 format)
echo - app\src\main\assets\personal_certificate.p12 (App certificate)
echo.
echo IMPORTANT SETUP STEPS:
echo 1. Default certificate password: 'camera2024'
echo 2. In the app Settings ^> Advanced Security, set certificate password to: camera2024
echo 3. Also configure a strong username/password for authentication
echo 4. Build and install the app
echo.
echo SECURITY WARNING:
echo - Change the certificate password in settings for better security!
echo - This is just to get you started quickly.
echo.
pause