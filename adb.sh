JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug -PVERSION_CODE=194

set -a; . /tmp/opencode/ks.env; set +a

/data/codigo/android-sdk/build-tools/36.0.0/apksigner sign \
  --ks "$ANDROID_KEYSTORE_PATH" \
  --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --key-pass "pass:$ANDROID_KEY_PASSWORD" \
  --out /tmp/opencode/jkanime.apk \
  app/build/outputs/apk/debug/app-debug.apk

ADB=/data/codigo/android-sdk/platform-tools/adb

$ADB -s emulator-5554 install -r /tmp/opencode/jkanime.apk
$ADB -s emulator-5554 shell am force-stop com.macastil.tv
$ADB -s emulator-5554 shell monkey \
  -p com.macastil.tv \
  -c android.intent.category.LEANBACK_LAUNCHER 1