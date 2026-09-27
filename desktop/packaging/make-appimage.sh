#!/bin/bash
# Builds SnapTV-Desktop-x86_64.AppImage: the Compose app with its own trimmed Java runtime,
# packed into one executable file. Needs a JDK (for jpackage) and appimagetool on PATH or in
# $APPIMAGETOOL.
set -euo pipefail
cd "$(dirname "$0")/../.."
./gradlew -q :desktop:createDistributable
APP=desktop/build/compose/binaries/main/app/snaptv-desktop
DIR=desktop/build/appimage/SnapTV.AppDir
rm -rf "$DIR" && mkdir -p "$DIR/usr"
cp -r "$APP"/. "$DIR/usr/"
cp desktop/packaging/snaptv-desktop.desktop desktop/packaging/snaptv-desktop.png "$DIR/"
cat > "$DIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/snaptv-desktop" "$@"
RUN
chmod +x "$DIR/AppRun"
OUT=${1:-desktop/build/SnapTV-Desktop-x86_64.AppImage}
# --appimage-extract-and-run: works without FUSE (CI runners, containers).
ARCH=x86_64 "${APPIMAGETOOL:-appimagetool}" --appimage-extract-and-run "$DIR" "$OUT" >/dev/null
echo "$OUT"
