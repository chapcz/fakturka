#!/usr/bin/env bash
# Packs the Compose distributable (build/compose/binaries/main/app/fakturka) into an AppImage.
# Usage: packaging/build-appimage.sh <version>   (run ./gradlew createDistributable first)
set -euo pipefail
VERSION="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/build/compose/binaries/main/app/fakturka"
WORK="$ROOT/build/appimage"
APPDIR="$WORK/Fakturka.AppDir"
TOOL="$WORK/appimagetool"

[ -x "$APP/bin/fakturka" ] || { echo "missing $APP, run ./gradlew createDistributable" >&2; exit 1; }
rm -rf "$APPDIR"; mkdir -p "$APPDIR/usr"
cp -a "$APP" "$APPDIR/usr/fakturka"
cp "$ROOT/packaging/fakturka.png" "$APPDIR/fakturka.png"
cat > "$APPDIR/fakturka.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Fakturka
Comment=Fakturace a hlášení DPH
Exec=fakturka
Icon=fakturka
Categories=Office;Finance;
DESKTOP
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/fakturka/bin/fakturka" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

if [ ! -x "$TOOL" ]; then
    curl -fsSL -o "$TOOL" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
    chmod +x "$TOOL"
fi
OUT="$ROOT/build/appimage/Fakturka-$VERSION-x86_64.AppImage"
# --appimage-extract-and-run: CI runners have no FUSE.
ARCH=x86_64 "$TOOL" --appimage-extract-and-run --no-appstream "$APPDIR" "$OUT"
echo "$OUT"
