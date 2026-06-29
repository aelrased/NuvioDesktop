#!/usr/bin/env bash
set -eo pipefail

usage() {
  cat <<'USAGE'
Build a Linux .deb package for Nuvio with bundled video player libraries.

Usage:
  ./scripts/build-linux-deb.sh [options] [-- extra-gradle-args...]

Options:
  --clean          Clean composeApp before building.
  --release        Build a release package (default).
  -h, --help       Show this help.

Environment:
  NUVIO_DEB_ARCH   Target architecture (default: amd64).
                   Supported: amd64, arm64.

The script produces a .deb following the standard Linux desktop layout:
  /usr/bin/nuvio              - launcher script
  /usr/lib/nuvio/             - JRE + app + bundled native libs
  /usr/share/applications/    - .desktop file (taskbar icon)
  /usr/share/icons/hicolor/   - app icons at multiple sizes

Requirements:
  - JDK 17+ (for Gradle and jpackage)
  - gcc, pkg-config
  - dpkg-deb
  - Development headers: libmpv-dev, libegl-dev, libgl-dev, libgbm-dev, libx11-dev
USAGE
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

arch="${NUVIO_DEB_ARCH:-amd64}"
clean=false
release=true
extra_gradle_args=()

while (($#)); do
  case "$1" in
    --clean)
      clean=true
      ;;
    --release)
      release=true
      ;;
    --debug)
      release=false
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      extra_gradle_args+=("$@")
      break
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

# ── Read version from config ──────────────────────────────────────────
version_file="composeApp/Configuration/DesktopVersion.properties"
if [[ ! -f "$version_file" ]]; then
  echo "Version config not found: $version_file" >&2
  exit 1
fi
version_name=$(grep '^VERSION_NAME=' "$version_file" | cut -d= -f2)
version_code=$(grep '^VERSION_CODE=' "$version_file" | cut -d= -f2)
echo "Building Nuvio .deb v${version_name} (code ${version_code}) for ${arch}"

# ── Common Gradle args ────────────────────────────────────────────────
common_gradle_args=(
  "-Pcompose.desktop.packaging.checkJdkVendor=false"
  "--no-daemon"
)

if [[ "$release" == true ]]; then
  gradle_task=":composeApp:packageReleaseDistributionForCurrentOS"
else
  gradle_task=":composeApp:packageDistributionForCurrentOS"
fi

# ── Build ─────────────────────────────────────────────────────────────
if [[ "$clean" == true ]]; then
  echo "Cleaning composeApp..."
  ./gradlew :composeApp:clean "${common_gradle_args[@]}"
fi

echo
echo "==> Building desktop distribution..."
echo "    Task: ${gradle_task}"
./gradlew "$gradle_task" "${common_gradle_args[@]}" "${extra_gradle_args[@]}"

# ── Locate build output ──────────────────────────────────────────────
if [[ "$release" == true ]]; then
  dist_base="composeApp/build/compose/binaries/main-release"
else
  dist_base="composeApp/build/compose/binaries/main"
fi

dist_dir=$(find "$dist_base" -mindepth 1 -maxdepth 1 -type d ! -name "*.deb" ! -name "*.rpm" | head -1)
if [[ -z "$dist_dir" ]]; then
  echo "No distribution directory found in ${dist_base}" >&2
  exit 1
fi
echo "    Source distribution: ${dist_dir}"

# ── Prepare .deb structure ───────────────────────────────────────────
pkg_name="nuvio"
deb_version="${version_name}"
pkg_dir=$(mktemp -d)
trap "rm -rf '$pkg_dir'" EXIT

# Standard Linux layout
bin_dir="${pkg_dir}/usr/bin"
lib_dir="${pkg_dir}/usr/lib/${pkg_name}"
share_dir="${pkg_dir}/usr/share"
applications_dir="${share_dir}/applications"
icons_dir="${share_dir}/icons/hicolor"

mkdir -p "$bin_dir"
mkdir -p "$lib_dir"
mkdir -p "$applications_dir"

echo
echo "==> Assembling .deb structure..."

# ── Copy distribution (JRE + app) into /usr/lib/nuvio/ ────────────────
echo "    Copying app files to /usr/lib/nuvio/..."
cp -a "${dist_dir}/"* "$lib_dir/"

# ── Create launcher script at /usr/bin/nuvio ──────────────────────────
echo "    Creating launcher: /usr/bin/nuvio"
cat > "${bin_dir}/${pkg_name}" << 'LAUNCHER'
#!/usr/bin/env bash
APP_DIR="/usr/lib/nuvio"
export LD_LIBRARY_PATH="${APP_DIR}/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export XDG_DATA_DIRS="${APP_DIR}/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
exec "${APP_DIR}/Nuvio" "$@"
LAUNCHER
chmod +x "${bin_dir}/${pkg_name}"

# ── Bundle native video libraries into /usr/lib/nuvio/lib/ ────────────
echo
echo "==> Bundling native video player libraries..."

# Ensure lib/ directory exists inside the app
app_lib="${lib_dir}/lib"
mkdir -p "$app_lib"

# Copy bundled libraries from live/ directory
live_dir="composeApp/src/desktopMain/native/linux/live"
if [[ -d "$live_dir" ]]; then
  echo "    Copying bundled libraries from ${live_dir}/..."
  find "$live_dir" -name '*.so*' -type f | while read -r lib; do
    lib_name=$(basename "$lib")
    echo "      + ${lib_name}"
    cp -L "$lib" "${app_lib}/"
  done
fi

# Helper: bundle a library from system paths
bundle_lib() {
  local libname="$1"
  local patterns=(
    "/usr/lib/x86_64-linux-gnu/${libname}*"
    "/usr/lib64/${libname}*"
    "/usr/local/lib/${libname}*"
    "/usr/lib/${libname}*"
  )
  local found=false
  for pattern in "${patterns[@]}"; do
    for lib in $pattern; do
      if [[ -f "$lib" ]]; then
        lib_name=$(basename "$lib")
        echo "      + bundling ${lib_name}"
        cp -L "$lib" "${app_lib}/"
        found=true
      fi
    done
  done
  if [[ "$found" == false ]]; then
    echo "      ! ${libname} not found (will use system package)"
  fi
}

echo "    Checking for libmpv..."
bundle_lib "libmpv.so"

echo "    Checking for libplacebo..."
bundle_lib "libplacebo.so"

echo "    Checking for libdav1d..."
bundle_lib "libdav1d.so"

echo "    Checking for libass..."
bundle_lib "libass.so"

echo "    Checking for libuchardet..."
bundle_lib "libuchardet.so"

echo "    Checking for libvulkan..."
bundle_lib "libvulkan.so"

# ── Fix RPATH on bundled libraries ───────────────────────────────────
echo
echo "==> Fixing RPATH on bundled libraries..."
find "$app_lib" -name '*.so' -type f 2>/dev/null | while read -r lib; do
  patchelf --set-rpath '$ORIGIN' "$lib" 2>/dev/null || true
done

# Fix RPATH on player bridge
find "$lib_dir" -name 'libplayer_bridge.so' -type f 2>/dev/null | while read -r lib; do
  patchelf --set-rpath "\$ORIGIN/../lib" "$lib" 2>/dev/null || true
done

# ── Create .desktop file ─────────────────────────────────────────────
echo
echo "==> Creating .desktop file..."
mkdir -p "$applications_dir"

cat > "${applications_dir}/${pkg_name}.desktop" << DESKTOP
[Desktop Entry]
Name=Nuvio
Comment=Stream movies and TV shows
Exec=/usr/bin/nuvio %u
Icon=nuvio
Type=Application
Categories=AudioVideo;Video;Player;
StartupWMClass=com.nuvio.app.MainKt
Terminal=false
DESKTOP

# ── Install icons at multiple sizes ───────────────────────────────────
echo "    Installing icons..."
icon_src="composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png"

if [[ -f "$icon_src" ]]; then
  for size in 16 32 48 64 128 256 512; do
    icon_dir="${icons_dir}/${size}x${size}/apps"
    mkdir -p "$icon_dir"
    cp "$icon_src" "${icon_dir}/${pkg_name}.png"
    echo "      + ${size}x${size}"
  done
  # HiDPI variant
  icon_dir_256="${icons_dir}/256x256@2/apps"
  mkdir -p "$icon_dir_256"
  cp "$icon_src" "${icon_dir_256}/${pkg_name}.png"
  echo "      + 256x256@2 (HiDPI)"
else
  echo "      WARNING: Icon not found at ${icon_src}"
fi

# ── Create DEBIAN/control ────────────────────────────────────────────
echo
echo "==> Creating DEBIAN/control..."
debian_dir="${pkg_dir}/DEBIAN"
mkdir -p "$debian_dir"

# Calculate installed size in KB
installed_size=$(du -sk "$pkg_dir" --exclude="${pkg_dir}/DEBIAN" | cut -f1)

cat > "${debian_dir}/control" << CONTROL
Package: ${pkg_name}
Version: ${deb_version}
Architecture: ${arch}
Installed-Size: ${installed_size}
Maintainer: Nuvio Media
Priority: optional
Depends: libegl1, libgl1, libgbm1, libx11-6, libx11-xcb1, libvulkan1
Description: Nuvio - Stream movies and TV shows
 A desktop media player and streaming application with built-in
 video playback powered by libmpv.
CONTROL

# ── Build .deb ───────────────────────────────────────────────────────
echo
echo "==> Building .deb package..."
output_dir="release-assets/linux"
mkdir -p "$output_dir"
final_deb="${output_dir}/Nuvio-${deb_version}_${arch}.deb"

dpkg-deb -Zxz --build "$pkg_dir" "$final_deb"

echo
echo "==> .deb package created:"
ls -lh "$final_deb"

# ── Summary ──────────────────────────────────────────────────────────
echo
echo "==> Package contents:"
echo "    /usr/bin/nuvio                          - launcher"
echo "    /usr/lib/nuvio/                         - JRE + app + native libs"
echo "    /usr/share/applications/nuvio.desktop   - desktop entry (taskbar icon)"
echo "    /usr/share/icons/hicolor/*/apps/nuvio.png - app icons"
echo
echo "==> Install:"
echo "    sudo dpkg -i ${final_deb}"
echo "    sudo apt-get install -f   # fix missing deps if needed"
echo
echo "==> Done!"
