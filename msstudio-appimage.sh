#!/bin/bash
# Build MSStudio AppImage from a Linux tar.gz produced by msstudio-installers.cmd.
# Usage:
#   ./msstudio-appimage.sh [path/to/MSStudio-*.tar.gz]
# Without an argument the newest MSStudio-*.tar.gz under out/ is used.
set -eu -o pipefail

root="$(cd "$(dirname "$0")"; pwd)"

if [ $# -ge 1 ]; then
  targz="$1"
else
  targz="$(ls -t "$root"/out/msstudio/artifacts/MSStudio-*.tar.gz "$root"/out/dist/all/MSStudio-*.tar.gz "$root"/out/MSStudio-*.tar.gz 2>/dev/null | head -n 1 || true)"
fi

if [ -z "${targz:-}" ] || [ ! -f "$targz" ]; then
  echo "Linux tar.gz not found." >&2
  echo "Build it first:" >&2
  echo "  ./msstudio-installers.cmd -Dintellij.build.target.os=linux" >&2
  echo "Then run:" >&2
  echo "  $0 <path-to-tar.gz>" >&2
  exit 1
fi

work="$root/out/appimage-work"
rm -rf "$work"
mkdir -p "$work"
tar -xzf "$targz" -C "$work"

# The tar contains a single top directory named MSStudio.
dist="$(find "$work" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
# The product launcher is bin/msstudio.sh.
# Do not take the first *.sh alphabetically: that is format.sh.
if [ -x "$dist/bin/msstudio.sh" ]; then
  launcher="$dist/bin/msstudio.sh"
elif [ -x "$dist/bin/idea.sh" ]; then
  launcher="$dist/bin/idea.sh"
else
  launcher="$(ls "$dist"/bin/*.sh | grep -v -e format.sh -e inspect.sh -e ltedit.sh | head -n 1)"
fi
launcher_name="$(basename "$launcher" .sh)"
icon_src="$(ls "$dist"/bin/*.png 2>/dev/null | head -n 1 || true)"

appdir="$work/MSStudio.AppDir"
mkdir -p "$appdir"

# Copy the whole IDE into AppDir/usr. AppRun points there.
mkdir -p "$appdir/usr"
cp -a "$dist"/. "$appdir/usr/"

if [ -n "$icon_src" ]; then
  cp -f "$icon_src" "$appdir/msstudio.png"
fi

cat > "$appdir/msstudio.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=MSStudio
Comment=Minecraft servers IDE
Exec=msstudio
Icon=msstudio
Categories=Development;IDE;
StartupWMClass=MSStudio
EOF

cat > "$appdir/AppRun" <<EOF
#!/bin/bash
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/usr/bin/${launcher_name}.sh" "\$@"
EOF
chmod +x "$appdir/AppRun"

# appimagetool: cache outside the work dir, because the work dir is wiped on every run.
tool="$root/out/appimagetool"
if [ ! -x "$tool" ]; then
  uname -m | grep -q aarch64 && arch="aarch64" || arch="x86_64"
  curl -L -o "$tool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${arch}.AppImage"
  chmod +x "$tool"
fi

out_image="$root/out/MSStudio-$(date +%Y.%m.%d)-$(uname -m).AppImage"
ARCH="$(uname -m)" "$tool" "$appdir" "$out_image"
echo "AppImage: $out_image"
