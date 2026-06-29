#!/usr/bin/env bash
set -eo pipefail

usage() {
  cat <<'USAGE'
Build a Linux AppImage for Nuvio with bundled video player libraries.

Usage:
  ./scripts/build-linux-appimage.sh [options]

Options:
  --clean          Clean composeApp before building.
  --release        Build a release AppImage (default).
  -o, --output     Output file path (default: Nuvio-x86_64.AppImage).
  -h, --help       Show this help.

The script uses the Gradle task :composeApp:buildAppImage which:
  1. Builds the desktop distribution with JRE bundled via Gradle
  2. Creates AppRun entrypoint (required by AppImage runtime)
  3. Runs appimagetool to produce the AppImage

Requirements:
  - JDK 17+
  - appimagetool (downloaded automatically if missing)
USAGE
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

clean=false
release=true
output_file=""
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
    -o|--output)
      shift
      output_file="$1"
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

# ── Read version ─────────────────────────────────────────────────────
version_file="composeApp/Configuration/DesktopVersion.properties"
if [[ ! -f "$version_file" ]]; then
  echo "Version config not found: $version_file" >&2
  exit 1
fi
version_name=$(grep '^VERSION_NAME=' "$version_file" | cut -d= -f2)
echo "Building Nuvio AppImage v${version_name}"

if [[ -z "$output_file" ]]; then
  output_file="Nuvio-x86_64.AppImage"
fi

# ── Ensure appimagetool is available ─────────────────────────────────
echo "==> Checking for appimagetool..."
if command -v appimagetool &>/dev/null; then
  echo "    appimagetool found in PATH"
elif [[ -f "$HOME/.local/bin/appimagetool" ]]; then
  echo "    appimagetool found at ~/.local/bin/appimagetool"
  export PATH="$HOME/.local/bin:$PATH"
else
  echo "    Downloading appimagetool..."
  mkdir -p "$HOME/.local/bin"
  appimagetool_url="https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
  if command -v wget &>/dev/null; then
    wget -q "$appimagetool_url" -O "$HOME/.local/bin/appimagetool"
  elif command -v curl &>/dev/null; then
    curl -fsSL "$appimagetool_url" -o "$HOME/.local/bin/appimagetool"
  else
    echo "ERROR: Neither wget nor curl found. Install appimagetool manually." >&2
    exit 1
  fi
  chmod +x "$HOME/.local/bin/appimagetool"
  export PATH="$HOME/.local/bin:$PATH"
  echo "    appimagetool downloaded to ~/.local/bin/appimagetool"
fi

# ── Common Gradle args ────────────────────────────────────────────────
common_gradle_args=(
  "-Pcompose.desktop.packaging.checkJdkVendor=false"
  "--no-daemon"
)

if [[ "$release" == false ]]; then
  echo "ERROR: Only release builds are supported for AppImage" >&2
  exit 1
fi

# ── Build AppImage via Gradle ────────────────────────────────────────
if [[ "$clean" == true ]]; then
  echo
  echo "==> Cleaning composeApp..."
  ./gradlew :composeApp:clean "${common_gradle_args[@]}"
fi

echo
echo "==> Building AppImage via Gradle task buildAppImage..."
echo "    This will:"
echo "      1. Build desktop distribution with JRE bundled"
echo "      2. Generate AppRun entrypoint"
echo "      3. Package into AppImage with appimagetool"
echo
./gradlew :composeApp:buildAppImage "${common_gradle_args[@]}" "${extra_gradle_args[@]}"

# ── Locate AppImage and copy to output ───────────────────────────────
echo
echo "==> Locating AppImage output..."
appimage_src=$(ls -1 release-assets/linux/Nuvio-x86_64.AppImage 2>/dev/null | head -1)

if [[ -z "$appimage_src" ]]; then
  echo "ERROR: AppImage not found in release-assets/linux/" >&2
  echo "       The Gradle task should have created it there." >&2
  exit 1
fi

echo
echo "==> AppImage ready:"
ls -lh "$appimage_src"

echo
echo "==> Run:"
echo "    chmod +x ${output_file}"
echo "    ./${output_file}"
echo
echo "==> Done!"
