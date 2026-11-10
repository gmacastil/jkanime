#!/usr/bin/env bash
# Genera un APK versionado y lo deja listo para sideload en dist/.
#
#   ./tools/build-apk.sh --version 0.1.2
#   ./tools/build-apk.sh --version 0.1.2 --release
#   ./tools/build-apk.sh --version 0.1.2 --release --publish
#   ./tools/build-apk.sh --install --clean --out DIR
#
# Requiere: JDK 17+ (por defecto usa java-21-openjdk si existe), Android SDK
# con platforms;android-36 y build-tools;36.0.0 (ver README.md).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OUT_DIR="$ROOT/dist"
DO_INSTALL=0
DO_CLEAN=0
DO_RELEASE=0
DO_PUBLISH=0
VERSION_NAME="${VERSION_NAME:-}"
VERSION_CODE="${VERSION_CODE:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --install) DO_INSTALL=1 ;;
    --clean) DO_CLEAN=1 ;;
    --release) DO_RELEASE=1 ;;
    --publish) DO_RELEASE=1; DO_PUBLISH=1 ;;
    --version)
      VERSION_NAME="$2"
      shift
      ;;
    --version-code)
      VERSION_CODE="$2"
      shift
      ;;
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

if [ -z "$VERSION_NAME" ]; then
  VERSION_NAME="$(awk -F= '$1 == "VERSION_NAME" { print $2; exit }' gradle.properties | tr -d '[:space:]')"
fi
if [ -z "$VERSION_NAME" ]; then
  echo "✗ VERSION_NAME no está definido en gradle.properties" >&2
  exit 2
fi
if ! [[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
  echo "✗ versión inválida: $VERSION_NAME (usa X.Y o X.Y.Z)" >&2
  exit 2
fi
if [ -z "$VERSION_CODE" ]; then
  # Misma fórmula que el workflow de release (100 + commits) para que un
  # build local no quede por debajo del versionCode ya instalado en la TV.
  VERSION_CODE="$((100 + $(git rev-list --count HEAD)))"
fi
if ! [[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]]; then
  echo "✗ VERSION_CODE inválido: $VERSION_CODE" >&2
  exit 2
fi

if [ "$DO_CLEAN" -eq 1 ]; then
  echo "→ ./gradlew clean"
  JAVA_HOME="$JAVA_HOME" ./gradlew clean
fi

VARIANT="debug"
GRADLE_TASK=":app:assembleDebug"
if [ "$DO_RELEASE" -eq 1 ]; then
  VARIANT="release"
  GRADLE_TASK=":app:assembleRelease"
fi

echo "→ ./gradlew $GRADLE_TASK -PVERSION_NAME=$VERSION_NAME -PVERSION_CODE=$VERSION_CODE"
JAVA_HOME="$JAVA_HOME" ./gradlew "$GRADLE_TASK" \
  -PVERSION_NAME="$VERSION_NAME" \
  -PVERSION_CODE="$VERSION_CODE"

APK="$ROOT/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$APK" ] || { echo "✗ no se generó $APK" >&2; exit 1; }

STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
DEST="$OUT_DIR/jkanimetv-${VERSION_NAME}-${VARIANT}-${STAMP}.apk"
cp "$APK" "$DEST"

SHA256="$(sha256sum "$DEST" | cut -d' ' -f1)"
SIZE_MB="$(du -h "$DEST" | cut -f1)"

echo
echo "✓ APK listo: $DEST"
echo "  versión:  $VERSION_NAME ($VERSION_CODE)"
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

if [ "$DO_PUBLISH" -eq 1 ]; then
  TAG="v$VERSION_NAME"
  if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "✗ el tag $TAG ya existe localmente" >&2
    exit 1
  fi
  echo "→ publicando tag $TAG para GitHub Actions"
  git tag -a "$TAG" -m "jkanimetv $TAG"
  git push origin "$TAG"
  echo "✓ tag publicado: $TAG"
  echo "  GitHub Actions generará el APK release y el GitHub Release."
fi

echo
echo "Sideload manual:"
echo "  adb connect <IP-DEL-TV>:5555 && adb install -r \"$DEST\""
