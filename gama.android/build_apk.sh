#!/bin/bash
set -e

# Sync gradle wrapper if missing
if [ ! -f "gradlew" ]; then
    gradle wrapper --gradle-version 8.2.1
fi

chmod +x gradlew

# Build the APK
./gradlew assembleDebug

echo "Build successful. APK located at: app/build/outputs/apk/debug/app-debug.apk"
