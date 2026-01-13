@echo off
REM Android IP Camera - Personal Certificate Setup
REM Creates secure certificate for personal use

echo ===============================================
echo Android IP Camera - Personal Certificate Setup
echo ===============================================
echo.
echo This will create a secure HTTPS certificate for personal use.
echo.
echo SECURITY NOTES:
echo - You will be prompted to create a certificate password
echo - Private keys are encrypted (not stored in plaintext)
echo - Certificate is for personal use only
echo.

REM Create certificates directory
if not exist "certificates" mkdir certificates

REM Check if OpenSSL is available
openssl version >nul 2>&1
if errorlevel 1 (
    echo ERROR: OpenSSL not found! Please install OpenSSL first.
    pause
    exit /b 1
)

REM Generate certificate
echo.
echo Generating secure personal certificate...
echo You will be prompted to enter a certificate password (remember this!)
echo.

REM Generate private key with encryption (no -nodes flag)
openssl req -x509 -newkey rsa:2048 -keyout "certificates\personal.key" -out "certificates\personal.crt" -days 3650 -subj "/C=US/ST=Personal/L=Home/O=Personal IP Camera/OU=Home/CN=localhost"

REM Ask user for certificate password
set /p CERT_PASSWORD="Enter a strong password for the certificate: "

REM Generate PKCS12 format with user-provided password
openssl pkcs12 -export -in "certificates\personal.crt" -inkey "certificates\personal.key" -out "certificates\personal.p12" -name "personal" -password pass:%CERT_PASSWORD% -legacy

REM Copy to assets
if not exist "..\app\src\main\assets" mkdir "..\app\src\main\assets"
copy "certificates\personal.p12" "..\app\src\main\assets\personal_certificate.p12"

echo.
echo ===============================================
echo Certificate Setup Complete!
echo ===============================================
echo.
echo Files created:
echo - certificates\personal.key (Encrypted private key)
echo - certificates\personal.crt (Certificate)
echo - certificates\personal.p12 (PKCS12 format)
echo - ..\app\src\main\assets\personal_certificate.p12 (App certificate)
echo.
echo IMPORTANT SECURITY STEPS:
echo 1. Save your certificate password securely
echo 2. In the app Settings, configure your certificate password
echo 3. Also configure strong username/password for authentication
echo 4. Build and install the app
echo.
echo The app will NOT work until you configure credentials in Settings!
echo.
pause