# Building & installing the dev build

How to build the debug APK and push it to an Android phone over USB.

## Prerequisites (one-time)
- **JDK 17+**, the **Android SDK** (`ANDROID_HOME` / `sdk.dir` in `local.properties`), platform
  **android-35**, build-tools **35.0.0**.
- **NDK r27** (`27.0.12077973`) and **CMake 3.22.1** — needed to compile the native Opus codec:
  ```sh
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;27.0.12077973" "cmake;3.22.1"
  ```
- **libopus source** (vendored, not committed) — fetch once:
  ```sh
  git clone --depth 1 --branch v1.5.2 https://github.com/xiph/opus.git core-audio/src/main/cpp/opus
  rm -rf core-audio/src/main/cpp/opus/.git
  ```
- On the **phone**: enable Developer Options → **USB debugging**, plug in over USB, and accept the
  "Allow USB debugging?" prompt.

## Build the APK
```sh
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```
Use the Gradle **wrapper** (`./gradlew`), not a system Gradle — the wrapper is pinned to a version
compatible with the build plugins.

## Release build (signed)
Release APKs are signed with the project's release key (`~/keys/grumbla-release.jks`, alias
`grumbla`, `CN=user`). The key is **not** in the repo; Gradle reads its location and passwords from a
gitignored `keystore.properties` at the repo root:
```properties
storeFile=/absolute/path/to/grumbla-release.jks
storePassword=...
keyAlias=grumbla
keyPassword=...
```
```sh
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```
Without `keystore.properties` the release build is produced **unsigned** (debug builds are
unaffected). Verify a signed APK came from the real key:
```sh
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# SHA-256 digest must be 6fda439f1d91b4b628397d5784ec0ecc1dbb5f310154d8feababd87613797245
```
- **Losing the key means installs can never be updated in place** — keep an off-machine backup of
  the `.jks` and its password.
- Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every release; Android
  rejects an update whose `versionCode` isn't higher than the installed one.
- Debug and release builds are signed with different keys, so switching a phone from one to the
  other needs an uninstall (export the identity certificate in-app first — uninstall wipes it).

## Install on a phone
```sh
adb devices                                              # confirm the phone shows as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk # -r = replace/keep data
```

### One-liner: build + install
```sh
./gradlew :app:assembleDebug && \
  adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Multiple phones connected
`adb` needs a target when more than one device is attached. Get the serial from `adb devices`, then:
```sh
adb devices                          # e.g. ABC123 and DEF456
adb -s ABC123 install -r app/build/outputs/apk/debug/app-debug.apk
```
(Or set `ANDROID_SERIAL=ABC123` to make every `adb` command target that phone.)

## Launch / inspect after install
```sh
adb shell am start -n app.notmumla/.MainActivity     # launch
adb shell pidof app.notmumla                         # non-empty = running
adb logcat -d | grep -iE "FATAL|notmumla"            # check for crashes
```

## Connecting the phone to a local test server
Run a Mumble server on the dev machine and forward the port over USB so the phone can reach it at
`127.0.0.1`:
```sh
docker run -d --name mumble -p 64738:64738 -p 64738:64738/udp \
  -e MUMBLE_CONFIG_autobanAttempts=0 mumblevoip/mumble-server:latest
adb reverse tcp:64738 tcp:64738
# in the app, connect to host 127.0.0.1 port 64738
```

## Notes
- **Unihertz Titan 2** (and similar aggressive OEMs): for the connection to survive backgrounding,
  set the app to **Battery → Unrestricted**, allow autostart/background in the device's app-launch
  manager, and lock it in Recents. The app also prompts for battery-optimization exemption on connect.
- The first native build is slow (compiles libopus for arm64-v8a + x86_64); later builds are cached.
