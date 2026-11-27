@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "OUT=%~1"
if "%OUT%"=="" set "OUT=build\zapretD.apk"
if "%KEYSTORE%"=="" set "KEYSTORE=debug.keystore"
if "%KEYSTORE_PASS%"=="" set "KEYSTORE_PASS=android"
if "%KEY_ALIAS%"=="" set "KEY_ALIAS=debug"

if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if "%ANDROID_HOME%"=="" (
    echo Ошибка: задай переменную ANDROID_HOME, путь к Android SDK.
    echo В SDK нужны: build-tools и platforms, платформа Android 9 ^(API 28^) или новее.
    exit /b 1
)

set "BT="
for /f "delims=" %%i in ('dir /b /ad /o-n "%ANDROID_HOME%\build-tools" 2^>nul') do if not defined BT set "BT=%ANDROID_HOME%\build-tools\%%i"
if not defined BT (
    echo Ошибка: в "%ANDROID_HOME%\build-tools" ничего не установлено.
    exit /b 1
)

if "%ANDROID_JAR%"=="" (
    for /f "delims=" %%i in ('dir /b /ad /o-n "%ANDROID_HOME%\platforms" 2^>nul') do if not defined ANDROID_JAR if exist "%ANDROID_HOME%\platforms\%%i\android.jar" set "ANDROID_JAR=%ANDROID_HOME%\platforms\%%i\android.jar"
)
if not exist "%ANDROID_JAR%" (
    echo Ошибка: не найден android.jar. Поставь платформу Android через SDK Manager или задай ANDROID_JAR.
    exit /b 1
)

where javac >nul 2>nul || (echo Ошибка: не найден javac. Поставь JDK 11 или новее. & exit /b 1)
where jar >nul 2>nul || (echo Ошибка: не найден jar. Поставь JDK 11 или новее. & exit /b 1)
where keytool >nul 2>nul || (echo Ошибка: не найден keytool. Поставь JDK 11 или новее. & exit /b 1)
where kotlinc.bat >nul 2>nul || (echo Ошибка: не найден kotlinc. Поставь компилятор Kotlin и добавь его bin в PATH. & exit /b 1)

if "%KOTLIN_STDLIB%"=="" (
    set "KOTLINC_BAT="
    for /f "delims=" %%i in ('where kotlinc.bat') do if not defined KOTLINC_BAT set "KOTLINC_BAT=%%i"
    for %%i in ("!KOTLINC_BAT!") do set "KOTLIN_STDLIB=%%~dpi..\lib\kotlin-stdlib.jar"
)
if not exist "%KOTLIN_STDLIB%" (
    echo Ошибка: не найден kotlin-stdlib.jar. Задай KOTLIN_STDLIB.
    exit /b 1
)

for %%f in (lib\arm64-v8a\libnfqws.so lib\arm64-v8a\libciadpi.so lib\arm64-v8a\libhev-socks5-tunnel.so) do (
    if not exist "%%f" (
        echo Ошибка: нет %%f. Запусти tools\fetch_binaries.sh из Git Bash или WSL.
        exit /b 1
    )
)

set "W=%TEMP%\zapretd-build"
if exist "%W%" rmdir /s /q "%W%"
mkdir "%W%\gen" "%W%\obj" "%W%\dex"

echo [1/6] ресурсы и R.java
"%BT%\aapt.exe" package -f -m -J "%W%\gen" -M AndroidManifest.xml -I "%ANDROID_JAR%" -S res -A assets -F "%W%\base.apk" || exit /b 1

echo [2/6] компиляция R.java
javac -nowarn -source 8 -target 8 -cp "%ANDROID_JAR%" -d "%W%\obj" "%W%\gen\com\zapretd\R.java" 2>nul || exit /b 1

echo [3/6] компиляция Kotlin
call kotlinc.bat -no-reflect -cp "%ANDROID_JAR%;%KOTLIN_STDLIB%;%W%\obj" -d "%W%\obj" src || exit /b 1

echo [4/6] dex
jar cf "%W%\classes.jar" -C "%W%\obj" . || exit /b 1
call "%BT%\d8.bat" --release --min-api 28 --lib "%ANDROID_JAR%" --output "%W%\dex" "%W%\classes.jar" "%KOTLIN_STDLIB%" || exit /b 1
copy /y "%W%\dex\classes.dex" "%W%\classes.dex" >nul

echo [5/6] упаковка
pushd "%W%"
"%BT%\aapt.exe" add base.apk classes.dex >nul || (popd & exit /b 1)
popd
"%BT%\aapt.exe" add "%W%\base.apk" lib/arm64-v8a/libciadpi.so lib/arm64-v8a/libhev-socks5-tunnel.so lib/arm64-v8a/libnfqws.so lib/armeabi-v7a/libciadpi.so lib/armeabi-v7a/libhev-socks5-tunnel.so lib/armeabi-v7a/libnfqws.so >nul || exit /b 1
"%BT%\zipalign.exe" -f 4 "%W%\base.apk" "%W%\aligned.apk" || exit /b 1

if not exist "%KEYSTORE%" (
    echo Создаю отладочный ключ %KEYSTORE%
    keytool -genkeypair -keystore "%KEYSTORE%" -storepass %KEYSTORE_PASS% -keypass %KEYSTORE_PASS% -alias %KEY_ALIAS% -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=zapretD" >nul 2>nul || exit /b 1
)

echo [6/6] подпись
for %%i in ("%OUT%") do if not exist "%%~dpi" mkdir "%%~dpi"
call "%BT%\apksigner.bat" sign --ks "%KEYSTORE%" --ks-pass pass:%KEYSTORE_PASS% --key-pass pass:%KEYSTORE_PASS% --ks-key-alias %KEY_ALIAS% --out "%OUT%" "%W%\aligned.apk" || exit /b 1
call "%BT%\apksigner.bat" verify "%OUT%" || exit /b 1

if exist "%OUT%.idsig" del /q "%OUT%.idsig"
rmdir /s /q "%W%"
echo Готово: %OUT%
endlocal
