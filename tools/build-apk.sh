#!/usr/bin/env bash
# Genera el APK debug por demanda y lo deja listo para sideload en dist/.
#
#   ./tools/build-apk.sh              # build + copia a dist/ con nombre versionado
#   ./tools/build-apk.sh --install    # además lo instala en el dispositivo/emulador conectado
#   ./tools/build-apk.sh --clean      # ./gradlew clean antes de compilar (build limpio)
#   ./tools/build-apk.sh --out DIR    # copiar a otro directorio en vez de dist/
#
# Requiere: JDK 17+ (por defecto usa java-21-openjdk si existe), Android SDK
# con platforms;android-36 y build-tools;36.0.0 (ver README.md).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OUT_DIR="$ROOT/dist"
DO_INSTALL=0
DO_CLEAN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --install) DO_INSTALL=1 ;;
    --clean) DO_CLEAN=1 ;;
    --out)
      OUT_DIR="$2"
      shift
      ;;
    -h|--help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "✗ opción desconocida: $1" >&2
      exit 2
      ;;
  esac
  shift
done

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
[ -d "$JAVA_HOME" ] || { echo "✗ JAVA_HOME inválido: $JAVA_HOME" >&2; exit 1; }

if [ "$DO_CLEAN" -eq 1 ]; then
  echo "→ ./gradlew clean"
  JAVA_HOME="$JAVA_HOME" ./gradlew clean
fi

echo "→ ./gradlew :app:assembleDebug"
JAVA_HOME="$JAVA_HOME" ./gradlew :app:assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "✗ no se generó $APK" >&2; exit 1; }

# versionName declarado en app/build.gradle.kts (defaultConfig.versionName = "x.y.z")
VERSION="$(grep -m1 'versionName' "$ROOT/app/build.gradle.kts" | sed -E 's/.*"([^"]+)".*/\1/')"
VERSION="${VERSION:-0.0.0}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
DEST="$OUT_DIR/jkanime-tv-${VERSION}-debug-${STAMP}.apk"
cp "$APK" "$DEST"

SHA256="$(sha256sum "$DEST" | cut -d' ' -f1)"
SIZE_MB="$(du -h "$DEST" | cut -f1)"

echo
echo "✓ APK listo: $DEST"
echo "  tamaño:  $SIZE_MB"
echo "  sha256:  $SHA256"

if [ "$DO_INSTALL" -eq 1 ]; then
  SDK="${ANDROID_SDK_ROOT:-/data/codigo/android-sdk}"
  ADB="$SDK/platform-tools/adb"
  [ -x "$ADB" ] || ADB="adb"
  if "$ADB" devices | grep -q "device$"; then
    echo "→ instalando en el dispositivo conectado"
    "$ADB" install -r -g "$DEST"
  else
    echo "✗ no hay dispositivo/emulador conectado (adb devices vacío), se omite --install" >&2
  fi
fi

echo
echo "Sideload manual:"
echo "  adb connect <IP-DEL-TV>:5555 && adb install -r \"$DEST\""
