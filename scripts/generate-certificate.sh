#!/bin/bash

# Android IP Camera - Certificate Generator
# This script generates a secure certificate for Android IP Camera

echo "=============================================="
echo "Android IP Camera - Certificate Generator"
echo "=============================================="
echo
echo "This script will create a secure HTTPS certificate."
echo
echo "SECURITY NOTES:"
echo "- You will be prompted to create a strong certificate password"
echo "- The private key will be encrypted (not stored in plaintext)"
echo "- Certificate can be used for personal or development setups"
echo

# Set variables
CERT_DIR="certificates"
CERT_NAME="personal_certificate"
DAYS_VALID=3650
COUNTRY="US"
STATE="Personal"
LOCALITY="Home"
ORGANIZATION="Personal IP Camera"
ORGANIZATIONAL_UNIT="Home"
COMMON_NAME="localhost"

# Create certificates directory if it doesn't exist
mkdir -p $CERT_DIR

echo "Generating secure certificate..."
echo "You will be prompted to enter a certificate password (remember this!)"
echo

# Generate private key with encryption (no -nodes flag)
openssl req -x509 \
    -newkey rsa:2048 \
    -keyout "$CERT_DIR/$CERT_NAME.key" \
    -out "$CERT_DIR/$CERT_NAME.crt" \
    -days $DAYS_VALID \
    -subj "/C=$COUNTRY/ST=$STATE/L=$LOCALITY/O=$ORGANIZATION/OU=$ORGANIZATIONAL_UNIT/CN=$COMMON_NAME"

# Prompt user for certificate password
read -sp "Enter a strong password for the certificate: " PASSWORD
echo
read -sp "Confirm password: " PASSWORD_CONFIRM
echo

# Check if passwords match
if [ "$PASSWORD" != "$PASSWORD_CONFIRM" ]; then
    echo "ERROR: Passwords do not match!"
    exit 1
fi

# Generate PKCS12 format with user-provided password
openssl pkcs12 -export \
    -in "$CERT_DIR/$CERT_NAME.crt" \
    -inkey "$CERT_DIR/$CERT_NAME.key" \
    -out "$CERT_DIR/$CERT_NAME.p12" \
    -name "$CERT_NAME" \
    -password pass:$PASSWORD \
    -legacy

# Copy to app assets
mkdir -p "../app/src/main/assets"
cp "$CERT_DIR/$CERT_NAME.p12" "../app/src/main/assets/personal_certificate.p12"

echo
echo "=============================================="
echo "Certificate Generation Complete!"
echo "=============================================="
echo
echo "Files created:"
echo "  - $CERT_DIR/$CERT_NAME.key (Encrypted private key)"
echo "  - $CERT_DIR/$CERT_NAME.crt (Certificate)"
echo "  - $CERT_DIR/$CERT_NAME.p12 (PKCS12 format)"
echo "  - ../app/src/main/assets/personal_certificate.p12 (App certificate)"
echo
echo "IMPORTANT SECURITY STEPS:"
echo "1. Save your certificate password securely"
echo "2. In the app Settings, configure your certificate password"
echo "3. Also configure a strong username/password for authentication"
echo "4. Build and install the app"
echo
echo "The app will NOT work until you configure credentials in Settings!"
echo
