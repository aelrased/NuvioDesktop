#!/usr/bin/env bash
set -eo pipefail

usage() {
  cat <<'USAGE'
Build both Linux .deb and AppImage packages for Nuvio.

Usage:
  ./scripts/build-linux-release.sh [options]

Options:
  --clean          Clean composeApp before building.
  --deb-only       Build only the .deb package.
  --appimage-only  Build only the AppImage.
  --skip-deb       Skip .deb build.
  --skip-appimage  Skip AppImage build.
  -h, --help       Show this help.

Environment:
  NUVIO_ARCH  Target architecture (default: x86_64).
USAGE
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

clean=false
build_deb=true
build_appimage=true

while (($#)); do
  case "$1" in
    --clean)
      clean=true
      ;;
    --deb-only)
      build_deb=true
      build_appimage=false
      ;;
    --appimage-only)
      build_deb=false
      build_appimage=true
      ;;
    --skip-deb)
      build_deb=false
      ;;
    --skip-appimage)
      build_appimage=false
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "$build_deb" == false && "$build_appimage" == false ]]; then
  echo "Nothing to build." >&2
  exit 2
fi

# ── Read version ─────────────────────────────────────────────────────
version_file="composeApp/Configuration/DesktopVersion.properties"
version_name=$(grep '^VERSION_NAME=' "$version_file" | cut -d= -f2)
version_code=$(grep '^VERSION_CODE=' "$version_file" | cut -d= -f2)
arch="${NUVIO_ARCH:-x86_64}"

echo "============================================"
echo "  Nuvio Linux Release Builder"
echo "  Version: ${version_name} (${version_code})"
echo "  Arch:    ${arch}"
echo "============================================"
echo

# ── Shared Gradle build (once for both) ──────────────────────────────
common_gradle_args=(
  "-Pcompose.desktop.packaging.checkJdkVendor=false"
  "--no-daemon"
)

if [[ "$clean" == true ]]; then
  echo "==> Cleaning composeApp..."
  ./gradlew :composeApp:clean "${common_gradle_args[@]}"
fi

echo "==> Building desktop distribution (shared)..."
./gradlew :composeApp:packageReleaseDistributionForCurrentOS "${common_gradle_args[@]}"
echo

# ── Build .deb ───────────────────────────────────────────────────────
deb_ok=false
if [[ "$build_deb" == true ]]; then
  echo "============================================"
  echo "  Building .deb package"
  echo "============================================"
  if ./scripts/build-linux-deb.sh; then
    deb_ok=true
    echo
  else
    echo "ERROR: .deb build failed" >&2
  fi
fi

# ── Build AppImage ───────────────────────────────────────────────────
appimage_ok=false
if [[ "$build_appimage" == true ]]; then
  echo "============================================"
  echo "  Building AppImage"
  echo "============================================"
  if ./scripts/build-linux-appimage.sh; then
    appimage_ok=true
    echo
  else
    echo "ERROR: AppImage build failed" >&2
  fi
fi

# ── Summary ──────────────────────────────────────────────────────────
echo "============================================"
echo "  Build Summary"
echo "============================================"
echo

if [[ "$build_deb" == true ]]; then
  if [[ "$deb_ok" == true ]]; then
    deb_file="release-assets/linux/Nuvio-${version_name}_${arch}.deb"
    echo "  .deb:      OK"
    [[ -f "$deb_file" ]] && echo "             $(ls -lh "$deb_file" | awk '{print $5}')  ${deb_file}"
  else
    echo "  .deb:      FAILED"
  fi
fi

if [[ "$build_appimage" == true ]]; then
  if [[ "$appimage_ok" == true ]]; then
    ai_file="release-assets/linux/Nuvio-${arch}.AppImage"
    echo "  AppImage:  OK"
    [[ -f "$ai_file" ]] && echo "             $(ls -lh "$ai_file" | awk '{print $5}')  ${ai_file}"
  else
    echo "  AppImage:  FAILED"
  fi
fi

echo
echo "============================================"

# Exit with error if any build failed
if [[ ("$build_deb" == true && "$deb_ok" == false) || ("$build_appimage" == true && "$appimage_ok" == false) ]]; then
  exit 1
fi

echo "==> All builds completed successfully!"
