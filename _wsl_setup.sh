#!/usr/bin/env bash
set -e
export ANDROID_HOME=/opt/android-sdk
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
if [ ! -f cmdtools.zip ]; then
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip
fi
unzip -q -o cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
rm -rf "$ANDROID_HOME/cmdline-tools/latest"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
{
  echo "export ANDROID_HOME=/opt/android-sdk"
  echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools'
} > /etc/profile.d/android.sh
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/tmp/sdk.log 2>&1
echo "sdk_exit=$?"
tail -2 /tmp/sdk.log
ls "$ANDROID_HOME"
