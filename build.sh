#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

out="${1:-build/zapretD.apk}"
keystore="${KEYSTORE:-debug.keystore}"
keystore_pass="${KEYSTORE_PASS:-android}"
key_alias="${KEY_ALIAS:-debug}"

fail() {
    echo "Ошибка: $1" >&2
    exit 1
}

need() {
    command -v "$1" >/dev/null 2>&1 || fail "не найден '$1'. $2"
}

if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    sdk="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    if [ -d "$sdk/build-tools" ]; then
        latest_tools="$(ls -1 "$sdk/build-tools" | sort -V | tail -n 1)"
        export PATH="$sdk/build-tools/$latest_tools:$PATH"
    fi
fi

need javac "Поставь JDK 11 или новее"
need jar "Поставь JDK 11 или новее"
need keytool "Поставь JDK 11 или новее"
need kotlinc "Поставь компилятор Kotlin: https://kotlinlang.org/docs/command-line.html"
need aapt "Debian/Ubuntu: sudo apt install aapt. Или поставь Android SDK build-tools и задай ANDROID_HOME"
need zipalign "Debian/Ubuntu: sudo apt install zipalign. Или Android SDK build-tools"
need apksigner "Debian/Ubuntu: sudo apt install apksigner. Или Android SDK build-tools"

android_jar="${ANDROID_JAR:-}"
if [ -z "$android_jar" ] && [ -n "${sdk:-}" ]; then
    for platform in $(ls -1 "$sdk/platforms" 2>/dev/null | sort -V -r); do
        if [ -f "$sdk/platforms/$platform/android.jar" ]; then
            android_jar="$sdk/platforms/$platform/android.jar"
            break
        fi
    done
fi
if [ -z "$android_jar" ]; then
    for candidate in /usr/lib/android-sdk/platforms/android-*/android.jar; do
        [ -f "$candidate" ] && android_jar="$candidate"
    done
fi
[ -n "$android_jar" ] && [ -f "$android_jar" ] || fail "не найден android.jar. Задай ANDROID_JAR или ANDROID_HOME (нужна платформа API 23 или новее, лучше API 28). Debian/Ubuntu: sudo apt install libandroid-23-java"

stdlib="${KOTLIN_STDLIB:-}"
if [ -z "$stdlib" ]; then
    kotlinc_path="$(readlink -f "$(command -v kotlinc)")"
    for candidate in "$(dirname "$kotlinc_path")/../lib/kotlin-stdlib.jar" /usr/share/java/kotlin-stdlib.jar; do
        if [ -f "$candidate" ]; then
            stdlib="$candidate"
            break
        fi
    done
fi
[ -n "$stdlib" ] && [ -f "$stdlib" ] || fail "не найден kotlin-stdlib.jar. Задай KOTLIN_STDLIB"

for binary in lib/arm64-v8a/libnfqws.so lib/arm64-v8a/libciadpi.so lib/arm64-v8a/libhev-socks5-tunnel.so; do
    [ -f "$binary" ] || fail "нет $binary. Запусти tools/fetch_binaries.sh"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/gen" "$work/obj"

echo "[1/6] ресурсы и R.java"
aapt package -f -m -J "$work/gen" -M AndroidManifest.xml -I "$android_jar" -S res -A assets -F "$work/base.apk"

echo "[2/6] компиляция R.java"
javac -nowarn -source 8 -target 8 -cp "$android_jar" -d "$work/obj" "$work/gen/com/zapretd/R.java" 2>/dev/null

echo "[3/6] компиляция Kotlin"
kotlinc -no-reflect -cp "$android_jar:$stdlib:$work/obj" -d "$work/obj" src

echo "[4/6] dex"
if command -v d8 >/dev/null 2>&1; then
    jar cf "$work/classes.jar" -C "$work/obj" .
    mkdir -p "$work/dex"
    d8 --release --min-api 28 --lib "$android_jar" --output "$work/dex" "$work/classes.jar" "$stdlib"
    cp "$work/dex/classes.dex" "$work/classes.dex"
elif command -v dalvik-exchange >/dev/null 2>&1; then
    dalvik-exchange --dex --output="$work/classes.dex" "$work/obj" "$stdlib"
else
    fail "не найден ни d8 (Android SDK build-tools), ни dalvik-exchange (Debian/Ubuntu: sudo apt install dalvik-exchange)"
fi

echo "[5/6] упаковка"
(cd "$work" && aapt add base.apk classes.dex >/dev/null)
aapt add "$work/base.apk" $(find lib -type f -name '*.so' | sort) >/dev/null
zipalign -f 4 "$work/base.apk" "$work/aligned.apk"

if [ ! -f "$keystore" ]; then
    echo "Создаю отладочный ключ $keystore"
    keytool -genkeypair -keystore "$keystore" -storepass "$keystore_pass" -keypass "$keystore_pass" \
        -alias "$key_alias" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=zapretD" >/dev/null 2>&1
fi

echo "[6/6] подпись"
mkdir -p "$(dirname "$out")"
apksigner sign --ks "$keystore" --ks-pass "pass:$keystore_pass" --key-pass "pass:$keystore_pass" \
    --ks-key-alias "$key_alias" --out "$out" "$work/aligned.apk"
apksigner verify "$out"

rm -f "$out.idsig"
echo "Готово: $out ($(du -h "$out" | cut -f1))"
