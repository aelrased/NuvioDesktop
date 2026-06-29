#!/usr/bin/env bash
set -euo pipefail

# ── JDK from SDK extension ──
export JAVA_HOME=/usr/lib/sdk/openjdk21
export PATH="$JAVA_HOME/bin:$PATH"

# pkg-config must find mpv from the module built earlier
export PKG_CONFIG_PATH="/app/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"

chmod +x gradlew

echo "==> Building Nuvio desktop distribution..."
./gradlew :composeApp:createReleaseDistributable \
  --no-daemon \
  -Pcompose.desktop.packaging.checkJdkVendor=false

# ── Locate the output AppDir ──
dist_base="composeApp/build/compose/binaries/main-release"
dist_dir=$(find "$dist_base" -mindepth 1 -maxdepth 1 -type d ! -name '*.deb' ! -name '*.rpm' | head -1)
if [[ -z "$dist_dir" ]]; then
  echo "ERROR: no distribution directory found in $dist_base" >&2
  exit 1
fi
echo "    Distribution: $dist_dir"

# ── Install to /app ──
install -d /app/lib/nuvio /app/bin
cp -a "$dist_dir"/* /app/lib/nuvio/

# ── Launcher ──
cat > /app/bin/nuvio << 'LAUNCHER'
#!/usr/bin/env bash
exec "/app/lib/nuvio/bin/Nuvio" "$@"
LAUNCHER
chmod +x /app/bin/nuvio

# ── Desktop entry & icon ──
install -d /app/share/applications /app/share/icons/hicolor/256x256/apps

cp "composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png" \
   /app/share/icons/hicolor/256x256/apps/io.github.nuvio.Nuvio.png

cat > /app/share/applications/io.github.nuvio.Nuvio.desktop << DESKTOP
[Desktop Entry]
Name=Nuvio
Comment=Stream movies and TV shows
Exec=nuvio %u
Icon=io.github.nuvio.Nuvio
Type=Application
Categories=AudioVideo;Video;Player;
StartupWMClass=com.nuvio.app.MainKt
Terminal=false
MimeType=x-scheme-handler/magnet;
DESKTOP

echo "==> Flatpak build complete"
