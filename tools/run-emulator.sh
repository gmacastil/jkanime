#!/usr/bin/env bash
# Crea (si no existe) y arranca un emulador Android TV, e instala el APK debug.
#
#   ./tools/run-emulator.sh            # arranca + instala + abre la app
#   ./tools/run-emulator.sh --wipe     # borra los datos del AVD antes de arrancar
#   ./tools/run-emulator.sh --headless # sin ventana (capturas por adb)
#
# Requiere: emulator + system-images;android-36;android-tv;x86_64 instalados,
# y /dev/kvm accesible (sin KVM el emulador x86_64 es inusable).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/data/codigo/android-sdk}"
AVD_NAME="${AVD_NAME:-jktv}"
API=36
TAG=android-tv
ABI=x86_64
IMAGE="system-images;android-$API;$TAG;$ABI"
DEVICE="tv_1080p"
PKG="com.macastil.tv"
ACTIVITY="com.macastil.tv/.MainActivity"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"

WIPE=0
HEADLESS=0
for arg in "$@"; do
  case "$arg" in
    --wipe) WIPE=1 ;;
    --headless) HEADLESS=1 ;;
    *) echo "opción desconocida: $arg" >&2; exit 2 ;;
  esac
done

[ -x "$SDK/emulator/emulator" ] || {
  echo "✗ falta el emulador. Instálalo con:" >&2
  echo "  sdkmanager --sdk_root=$SDK \"emulator\" \"$IMAGE\"" >&2
  exit 1
}
[ -d "$SDK/system-images/android-$API/$TAG/$ABI" ] || {
  echo "✗ falta la imagen $IMAGE. Instálala con:" >&2
  echo "  sdkmanager --sdk_root=$SDK \"$IMAGE\"" >&2
  exit 1
}
[ -r /dev/kvm ] && [ -w /dev/kvm ] || {
  echo "✗ /dev/kvm no es accesible: añade tu usuario al grupo kvm y vuelve a entrar" >&2
  exit 1
}

# --- AVD -------------------------------------------------------------------
if ! avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME\$"; then
  echo "→ creando AVD $AVD_NAME ($DEVICE, API $API $TAG)"
  echo "no" | avdmanager create avd \
    --name "$AVD_NAME" \
    --package "$IMAGE" \
    --device "$DEVICE" \
    --force >/dev/null
fi

# Ajustes del AVD: más RAM para WebView y sin arranque rápido (evita estados corruptos).
CFG="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
if [ -f "$CFG" ]; then
  set_cfg() { # clave valor
    if grep -q "^$1=" "$CFG"; then
      sed -i "s|^$1=.*|$1=$2|" "$CFG"
    else
      echo "$1=$2" >> "$CFG"
    fi
  }
  set_cfg hw.ramSize 3072
  set_cfg hw.keyboard yes
  set_cfg disk.dataPartition.size 6G
  set_cfg hw.lcd.density 320
fi

# --- arranque --------------------------------------------------------------
ADB="$SDK/platform-tools/adb"
"$ADB" start-server >/dev/null 2>&1 || true

if "$ADB" devices | grep -q "^emulator-.*device$"; then
  echo "→ ya hay un emulador corriendo, no arranco otro"
else
  EMU_ARGS=(
    -avd "$AVD_NAME"
    -no-boot-anim
    -netdelay none
    -netspeed full
    -camera-back none
    -camera-front none
    -no-audio
  )
  [ "$WIPE" -eq 1 ] && EMU_ARGS+=(-wipe-data)
  if [ "$HEADLESS" -eq 1 ] || [ -z "${DISPLAY:-}" ]; then
    EMU_ARGS+=(-no-window)
  else
    # GPU del host: es lo que hace que el <video> del embed se decodifique bien.
    EMU_ARGS+=(-gpu host)
  fi

  echo "→ arrancando $AVD_NAME"
  nohup "$SDK/emulator/emulator" "${EMU_ARGS[@]}" \
    >"$ROOT/.emulator.log" 2>&1 &
  echo "  log: $ROOT/.emulator.log"
fi

echo -n "→ esperando a que arranque el sistema"
"$ADB" wait-for-device
for _ in $(seq 1 180); do
  BOOTED="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [ "$BOOTED" = "1" ] && break
  echo -n "."
  sleep 2
done
echo
[ "${BOOTED:-}" = "1" ] || { echo "✗ el emulador no arrancó (ver .emulator.log)" >&2; exit 1; }

echo "→ dispositivo: $("$ADB" shell getprop ro.product.model | tr -d '\r') / API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r') / leanback=$("$ADB" shell pm list features | grep -c leanback | tr -d '\r')"

# --- app -------------------------------------------------------------------
[ -f "$APK" ] || { echo "✗ falta el APK: ./gradlew :app:assembleDebug" >&2; exit 1; }

echo "→ instalando $(basename "$APK")"
"$ADB" install -r -g "$APK" >/dev/null

echo "→ abriendo la app"
"$ADB" shell am start -n "$ACTIVITY" >/dev/null

echo
echo "✓ listo. La app debería estar en pantalla."
echo "  captura:   adb exec-out screencap -p > /tmp/jktv.png"
echo "  logs:      adb logcat -s JkBridge chromium AndroidRuntime"
echo "  navegar:   adb shell input keyevent 20  # abajo (19=arriba 21=izq 22=der 23=ok 4=atrás)"
echo "  apagar:    adb emu kill"
