#!/usr/bin/env bash
set -e
export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
SRC=/mnt/c/Users/Asus/Desktop/android-ip-camera
DST=/root/aip
TOOLS=/mnt/c/Users/Asus/Desktop/Gm5Plus-Root/TestServer/tools
rm -rf "$DST"; mkdir -p "$DST"
cd "$SRC"
cp -r app build.gradle.kts settings.gradle.kts gradle gradlew gradle.properties "$DST"/
[ -f signing.properties.default ] && cp signing.properties.default "$DST"/ || true
cd "$DST"
echo "sdk.dir=/opt/android-sdk" > local.properties
sed -i 's/\r$//' gradlew; chmod +x gradlew
./gradlew --no-daemon assembleDebug 2>&1 | tail -25
EXIT=${PIPESTATUS[0]}
echo "BUILD_EXIT=$EXIT"
if [ "$EXIT" = "0" ]; then
  APK=$(find . -name "*arm64-v8a-debug.apk" | head -1)
  mkdir -p "$TOOLS"
  cp "$APK" "$TOOLS/androidipcamera-h264-debug.apk"
  echo "COPIED_TO_TOOLS: $TOOLS/androidipcamera-h264-debug.apk"
  ls -l "$TOOLS/androidipcamera-h264-debug.apk"
fi
