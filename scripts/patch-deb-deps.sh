#!/usr/bin/env bash
set -e

DEB_PATH="$1"

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

cd "$TMPDIR"

ar x "$DEB_PATH"

# Extract control tar
mkdir ctrl
cd ctrl
tar xf "$TMPDIR/control.tar.xz"

# Verify
ls -la
cat control

# Patch Depends
sed -i '/^Depends:/d' control
sed -i '/^Description:/i\Depends: libmpv2 (>= 0.30.0) | libmpv1, libasound2 | libasound2t64, libpulse0 | libpulse-tmd0, libpipewire-0.3-0 | libpipewire-0.3-0t64, libva2 | libva-drm2 | libva-drm2t64, libvulkan1, libx11-6, libxkbcommon0 | libxkbcommon0t64, libwayland-client0, libegl1 | libegl-mesa0, libfontconfig1 | libfontconfig1t64, libfreetype6 | libfreetype6t64, libharfbuzz0v5 | libharfbuzz0t64, libfribidi0 | libfribidi0t64, libass9 | libass5 | libass0, libdrm2 | libdrm2t64, libgbm1 | libgbm1t64, libgl1 | libglx0 | libglx-mesa0, libsdl2-2.0-0 | libsdl2-2.0-0t64, zlib1g | zlib1g-t64' control

echo "--- After patch ---"
cat control

# Rebuild control.tar.xz
tar cf - --owner=root --group=root --numeric-owner . | xz > "$TMPDIR/control.tar.xz"

cd "$TMPDIR"

# Rebuild the .deb (ar format: !<arch>\ndebian-binary\ncontrol.tar.xz\ndata.tar.xz\n)
rm -f "$DEB_PATH"
ar rc "$DEB_PATH" debian-binary control.tar.xz data.tar.xz

echo "OK: $DEB_PATH patched"
