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
# Fewer malloc arenas: less native memory held per thread (glibc's default is 8 per core).
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
exec "$HERE/usr/bin/snaptv-desktop" "$@"
RUN
chmod +x "$DIR/AppRun"
OUT=${1:-desktop/build/SnapTV-Desktop-x86_64.AppImage}
# Update information: AppImageUpdate, Gear Lever etc. find new releases on GitHub and download
# only the changed parts, using the .zsync file appimagetool writes next to the AppImage.
UPDATE_INFO="gh-releases-zsync|thinke|snaptv|latest|SnapTV-Desktop-*-x86_64.AppImage.zsync"
# --appimage-extract-and-run: works without FUSE (CI runners, containers).
ARCH=x86_64 "${APPIMAGETOOL:-appimagetool}" --appimage-extract-and-run -u "$UPDATE_INFO" "$DIR" "$OUT" >/dev/null
command -v zsyncmake >/dev/null || echo "warning: zsyncmake not installed, so no $OUT.zsync (needed for delta updates)" >&2
echo "$OUT"
