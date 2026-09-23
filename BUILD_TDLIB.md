# Building TDLib for Android (required, one-time)

This project needs TDLib's native Telegram library. It isn't published to
Maven Central, so every Android Telegram client — including the real
GramStream — compiles it from source once. This takes 30-60 minutes on a
decent PC and only needs to be done once; the output gets copied into this
project and never touched again.

## Prerequisites (on your PC, not the phone)

- Android Studio installed (comes with the SDK)
- Android NDK — install via Android Studio: Settings → Languages & Frameworks
  → Android SDK → SDK Tools tab → check "NDK (Side by side)" → Apply
- CMake — same SDK Tools tab, check "CMake"
- git

## Steps

1. Clone TDLib's source:
   ```
   git clone https://github.com/tdlib/td.git
   cd td
   ```

2. Follow the official Android build script in the repo:
   ```
   example/android/build-tdlib.sh
   ```
   (Open that file first — it expects `ANDROID_NDK` and `ANDROID_HOME`
   environment variables pointing at your SDK/NDK install paths. Set those
   first, e.g.:)
   ```
   export ANDROID_HOME=$HOME/Android/Sdk
   export ANDROID_NDK=$ANDROID_HOME/ndk/<version>
   ```

3. This produces, per architecture (arm64-v8a, armeabi-v7a, x86_64):
   - `libtdjni.so`
   - Generated Java sources: `Client.java`, `TdApi.java`

4. Copy the results into this project:
   - `.so` files → `app/src/main/jniLibs/<abi>/libtdjni.so`
   - `Client.java` / `TdApi.java` →
     `app/src/main/java/org/drinkless/tdlib/`
     (confirmed from decompiling the real GramStream APK — recent TDLib
     versions generate under `org.drinkless.tdlib`, not the older
     `org.drinkless.td.libcore.telegram` path some older guides show)

5. Open this project in Android Studio, let Gradle sync, then Build → Build APK.

## Official reference

TDLib's own Android build docs (most reliable if their script changes):
https://github.com/tdlib/td/tree/master/example/android

## Before you build

In `TdLibManager.kt`, fill in your own:
```kotlin
private const val API_ID = 0       // from my.telegram.org
private const val API_HASH = ""    // from my.telegram.org
```
Same values you already used in `config.env` for the server version.
