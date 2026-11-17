#!/usr/bin/env bash
# Compila, firma, instala y abre JK Mobile en el emulador de teléfono.
#
# Uso:
#   ./adb-mobile.sh
#   ADB_SERIAL=emulator-5556 VERSION_CODE=195 ./adb-mobile.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/data/codigo/android-sdk}"
ADB="${ADB:-$SDK/platform-tools/adb}"
SERIAL="${ADB_SERIAL:-emulator-5556}"
AVD_NAME="${AVD_NAME:-jkphone}"
EMULATOR="$SDK/emulator/emulator"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
VERSION_CODE="${VERSION_CODE:-194}"
APK="$ROOT/app-mobile/build/outputs/apk/debug/app-mobile-debug.apk"
SIGNED_APK="/tmp/opencode/jkanime-mobile-debug-signed.apk"
CLEAN=0

for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    *) echo "Uso: $0 [--clean]" >&2; exit 2 ;;
  esac
done

cd "$ROOT"
export JAVA_HOME

if [[ ! -x "$ADB" ]]; then
  echo "✗ No existe adb: $ADB" >&2
  exit 1
fi

if [[ ! -x "$EMULATOR" ]]; then
  echo "✗ No existe el emulador: $EMULATOR" >&2
  exit 1
fi

echo "→ Compilando JK Mobile"
./gradlew :app-mobile:assembleDebug -PVERSION_CODE="$VERSION_CODE"

INSTALL_APK="$APK"
if [[ -f /tmp/opencode/ks.env ]]; then
  set -a
  . /tmp/opencode/ks.env
  set +a
  echo "→ Firmando APK con el keystore configurado"
  "$SDK/build-tools/36.0.0/apksigner" sign \
    --ks "$ANDROID_KEYSTORE_PATH" \
    --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --key-pass "pass:$ANDROID_KEY_PASSWORD" \
    --out "$SIGNED_APK" \
    "$APK"
  INSTALL_APK="$SIGNED_APK"
else
  echo "⚠ No existe /tmp/opencode/ks.env; se usará la firma debug"
fi

"$ADB" start-server >/dev/null
if ! "$ADB" devices | awk 'NR > 1 {print $1}' | grep -qx "$SERIAL"; then
  if [[ "$SERIAL" != emulator-* ]]; then
    echo "✗ El dispositivo $SERIAL no está conectado y no es un AVD" >&2
    exit 1
  fi
  if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
    echo "✗ No existe el AVD $AVD_NAME. Disponibles:" >&2
    "$EMULATOR" -list-avds >&2
    exit 1
  fi
  PORT="${SERIAL#emulator-}"
  echo "→ Arrancando AVD $AVD_NAME en $SERIAL"
  nohup "$EMULATOR" \
    -avd "$AVD_NAME" \
    -port "$PORT" \
    -no-snapshot-save \
    -no-boot-anim \
    -gpu host \
    -no-audio \
    >"/tmp/opencode/${AVD_NAME}.log" 2>&1 &
fi

echo "→ Esperando a que arranque $SERIAL"
"$ADB" -s "$SERIAL" wait-for-device
for _ in $(seq 1 120); do
  BOOTED="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$BOOTED" == "1" ]] && break
  sleep 2
done
[[ "${BOOTED:-}" == "1" ]] || {
  echo "✗ El emulador no terminó de arrancar. Revisa /tmp/opencode/${AVD_NAME}.log" >&2
  exit 1
}

if [[ "$CLEAN" -eq 1 ]]; then
  echo "→ Desinstalando la instalación anterior (--clean)"
  "$ADB" -s "$SERIAL" uninstall com.macastil.mobile >/dev/null || true
fi
echo "→ Instalando en $SERIAL"
if ! "$ADB" -s "$SERIAL" install -r -d -g "$INSTALL_APK"; then
  echo "✗ No se pudo instalar. Si hay conflicto de firma, ejecuta: $0 --clean" >&2
  exit 1
fi
"$ADB" -s "$SERIAL" shell am force-stop com.macastil.mobile
"$ADB" -s "$SERIAL" shell am start -n com.macastil.mobile/.MainActivity >/dev/null

echo "✓ JK Mobile abierta en $SERIAL"
