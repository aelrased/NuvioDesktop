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

The script produces a .deb matching the jpackage layout:
  /opt/nuvio/bin/Nuvio           - jpackage launcher binary
  /opt/nuvio/lib/app/            - application JARs
  /opt/nuvio/lib/runtime/        - bundled JRE
  /opt/nuvio/lib/Nuvio.png       - app icon
  /usr/share/applications/       - .desktop file (taskbar icon)
  /usr/share/icons/hicolor/      - app icons at multiple sizes

Requirements:
  - JDK 17+ (for Gradle)
  - dpkg-deb
  - patchelf (for RPATH on bundled libs)
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
    --clean) clean=true ;;
    --release) release=true ;;
    --debug) release=false ;;
    -h|--help) usage; exit 0 ;;
    --) shift; extra_gradle_args+=("$@"); break ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

# ── Read version ─────────────────────────────────────────────────────
version_file="composeApp/Configuration/DesktopVersion.properties"
if [[ ! -f "$version_file" ]]; then
  echo "Version config not found: $version_file" >&2
  exit 1
fi
version_name=$(grep '^VERSION_NAME=' "$version_file" | cut -d= -f2)
version_code=$(grep '^VERSION_CODE=' "$version_file" | cut -d= -f2)
echo "Building Nuvio .deb v${version_name} (code ${version_code}) for ${arch}"

# ── Build with Gradle ────────────────────────────────────────────────
common_gradle_args=("-Pcompose.desktop.packaging.checkJdkVendor=false" "--no-daemon")

if [[ "$release" == true ]]; then
  gradle_task=":composeApp:packageReleaseDistributionForCurrentOS"
else
  gradle_task=":composeApp:packageDistributionForCurrentOS"
fi

if [[ "$clean" == true ]]; then
  echo "Cleaning composeApp..."
  ./gradlew :composeApp:clean "${common_gradle_args[@]}"
fi

echo
echo "==> Building desktop distribution..."
echo "    Task: ${gradle_task}"
./gradlew "$gradle_task" "${common_gradle_args[@]}" "${extra_gradle_args[@]}"

# ── Locate app directory ────────────────────────────────────────────
if [[ "$release" == true ]]; then
  dist_base="composeApp/build/compose/binaries/main-release"
else
  dist_base="composeApp/build/compose/binaries/main"
fi

app_src="${dist_base}/app/Nuvio"
if [[ ! -d "$app_src" ]]; then
  echo "App directory not found at ${app_src}" >&2
  exit 1
fi
echo "    Source: ${app_src}"

# ── Prepare .deb structure ───────────────────────────────────────────
pkg_name="nuvio"
pkg_root="/opt/${pkg_name}"
pkg_dir=$(mktemp -d)
trap "rm -rf '$pkg_dir'" EXIT

opt_dir="${pkg_dir}${pkg_root}"
usr_bin_dir="${pkg_dir}/usr/bin"
applications_dir="${pkg_dir}/usr/share/applications"
icons_base="${pkg_dir}/usr/share/icons/hicolor"

mkdir -p "$opt_dir" "$applications_dir" "$icons_base"

echo
echo "==> Assembling .deb structure..."

# ── Copy app contents into /opt/nuvio/ ────────────────────────────────
echo "    Copying app to ${pkg_root}/..."
cp -a "${app_src}/"* "$opt_dir/"

# ── Bundle native video libraries ─────────────────────────────────────
echo
echo "==> Bundling native video player libraries..."

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
        echo "      + ${lib_name}"
        cp -L "$lib" "${opt_dir}/lib/"
        found=true
      fi
    done
  done
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
if command -v patchelf &>/dev/null; then
  find "${opt_dir}/lib" -name '*.so' -type f 2>/dev/null | while read -r lib; do
    patchelf --set-rpath '$ORIGIN' "$lib" 2>/dev/null || true
  done
else
  echo "    Warning: patchelf not found, skipping RPATH fix"
fi

# ── Desktop file ─────────────────────────────────────────────────────
echo
echo "==> Creating .desktop file..."
cat > "${applications_dir}/${pkg_name}.desktop" << DESKTOP
[Desktop Entry]
Name=Nuvio
Comment=Stream movies and TV shows
Exec=${pkg_root}/bin/Nuvio %u
Icon=nuvio
Type=Application
Categories=AudioVideo;Video;Player;
StartupWMClass=com.nuvio.app.MainKt
Terminal=false
MimeType=x-scheme-handler/nuvio;
DESKTOP

# ── Install icons ─────────────────────────────────────────────────────
echo "    Installing icons..."
icon_src="${app_src}/lib/Nuvio.png"
if [[ -f "$icon_src" ]]; then
  for size in 16 32 48 64 128 256 512; do
    icon_dir="${icons_base}/${size}x${size}/apps"
    mkdir -p "$icon_dir"
    cp "$icon_src" "${icon_dir}/${pkg_name}.png"
  done
  icon_dir_256="${icons_base}/256x256@2/apps"
  mkdir -p "$icon_dir_256"
  cp "$icon_src" "${icon_dir_256}/${pkg_name}.png"
else
  echo "      WARNING: Icon not found at ${icon_src}"
fi

# ── DEBIAN control ───────────────────────────────────────────────────
echo
echo "==> Creating DEBIAN/control..."
debian_dir="${pkg_dir}/DEBIAN"
mkdir -p "$debian_dir"

installed_size=$(du -sk "$pkg_dir" --exclude="${pkg_dir}/DEBIAN" | cut -f1)

cat > "${debian_dir}/control" << CONTROL
Package: ${pkg_name}
Version: ${version_name}
Architecture: ${arch}
Installed-Size: ${installed_size}
Maintainer: Nuvio Media
Priority: optional
Depends: libegl1, libgl1, libgbm1, libx11-6, libvulkan1
Description: Nuvio - Stream movies and TV shows
 A desktop media player and streaming application with built-in
 video playback powered by mpv.
CONTROL

# ── postinst / prerm scripts ─────────────────────────────────────────
cat > "${debian_dir}/postinst" << 'SCRIPT'
#!/bin/sh
set -e
# Desktop file in /usr/share/applications/ is auto-detected
exit 0
SCRIPT
chmod 755 "${debian_dir}/postinst"

cat > "${debian_dir}/prerm" << 'SCRIPT'
#!/bin/sh
set -e
exit 0
SCRIPT
chmod 755 "${debian_dir}/prerm"

# ── Build .deb ───────────────────────────────────────────────────────
echo
echo "==> Building .deb package..."
output_dir="release-assets/linux"
mkdir -p "$output_dir"
final_deb="${output_dir}/Nuvio-${version_name}_${arch}.deb"

dpkg-deb --root-owner-group -Zxz --build "$pkg_dir" "$final_deb"

echo
echo "==> .deb package created:"
ls -lh "$final_deb"

echo
echo "==> Package layout:"
echo "    ${pkg_root}/bin/Nuvio           - launcher binary"
echo "    ${pkg_root}/lib/app/            - application JARs"
echo "    ${pkg_root}/lib/runtime/        - bundled JRE"
echo "    ${pkg_root}/lib/*.so            - bundled native libs"
echo "    /usr/share/applications/        - desktop entry"
echo "    /usr/share/icons/hicolor/       - app icons"
echo
echo "==> Install:"
echo "    sudo dpkg -i ${final_deb}"
echo
echo "==> Done!"
