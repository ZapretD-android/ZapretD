#!/usr/bin/env bash
set -euo pipefail

ZAPRET_VERSION="v72.13"
BYEDPI_VERSION="v0.17.3"
BYEDPI_NAME="17.3"
BYEDPI_APP_VERSION="v1.2.0"

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch() {
    curl -fsSL -o "$2" "$1"
}

echo "nfqws (zapret $ZAPRET_VERSION)"
fetch "https://github.com/bol-van/zapret/releases/download/$ZAPRET_VERSION/zapret-$ZAPRET_VERSION.zip" "$work/zapret.zip"
unzip -q -o "$work/zapret.zip" "zapret-$ZAPRET_VERSION/binaries/android-arm64/nfqws" "zapret-$ZAPRET_VERSION/binaries/android-arm/nfqws" -d "$work"
mkdir -p "$root/lib/arm64-v8a" "$root/lib/armeabi-v7a"
cp "$work/zapret-$ZAPRET_VERSION/binaries/android-arm64/nfqws" "$root/lib/arm64-v8a/libnfqws.so"
cp "$work/zapret-$ZAPRET_VERSION/binaries/android-arm/nfqws" "$root/lib/armeabi-v7a/libnfqws.so"

echo "ciadpi (ByeDPI $BYEDPI_VERSION)"
fetch "https://github.com/hufrea/byedpi/releases/download/$BYEDPI_VERSION/byedpi-$BYEDPI_NAME-aarch64.tar.gz" "$work/bye64.tgz"
fetch "https://github.com/hufrea/byedpi/releases/download/$BYEDPI_VERSION/byedpi-$BYEDPI_NAME-armv7l.tar.gz" "$work/bye32.tgz"
mkdir -p "$work/b64" "$work/b32"
tar xzf "$work/bye64.tgz" -C "$work/b64"
tar xzf "$work/bye32.tgz" -C "$work/b32"
cp "$work/b64/ciadpi-aarch64" "$root/lib/arm64-v8a/libciadpi.so"
cp "$work/b32/ciadpi-armv7l" "$root/lib/armeabi-v7a/libciadpi.so"

echo "hev-socks5-tunnel (из ByeDPIAndroid $BYEDPI_APP_VERSION)"
fetch "https://github.com/dovecoteescapee/ByeDPIAndroid/releases/download/$BYEDPI_APP_VERSION/byedpi-1.2.0.apk" "$work/app.apk"
unzip -q -o "$work/app.apk" "lib/arm64-v8a/libhev-socks5-tunnel.so" "lib/armeabi-v7a/libhev-socks5-tunnel.so" -d "$work/app"
cp "$work/app/lib/arm64-v8a/libhev-socks5-tunnel.so" "$root/lib/arm64-v8a/"
cp "$work/app/lib/armeabi-v7a/libhev-socks5-tunnel.so" "$root/lib/armeabi-v7a/"

chmod 755 "$root"/lib/*/*.so
echo "Готово: $root/lib"
