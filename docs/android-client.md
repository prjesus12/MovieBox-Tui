# Android Client

`android/` contains a native Android app (Kotlin, Jetpack Compose, Media3 ExoPlayer)
for phones, tablets and Android TV. Like the [iOS client](ios-client.md), it never
talks to MovieBox directly: it drives the same Rust provider engine through the
UniFFI bindings generated from this crate's `mobile` feature.

## Layout

| Path | Contents |
| --- | --- |
| `android/scripts/build-rust.sh` | Cross-compiles the Rust core per ABI and regenerates the Kotlin bindings. |
| `android/app/src/main/java/uniffi/moviebox_tui/` | Generated Kotlin bindings (never edit by hand). |
| `android/app/src/main/jniLibs/<abi>/` | `libmoviebox_tui.so`, produced by the script (git-ignored). |
| `android/app/src/main/java/com/moviebox/android/` | App code: `Bridge`, `AppViewModel`, `PlayerActivity`, Compose screens under `ui/`. |

## Requirements

- Rust (the script adds the `aarch64-linux-android` / `x86_64-linux-android` targets).
- Android SDK with platform 37 and an NDK (`sdkmanager "ndk/<version>"`).
- JDK 17+ (Android Studio's bundled JBR works: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`).

## Build

```bash
./android/scripts/build-rust.sh
```

```bash
cd android && ./gradlew assembleDebug
```

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`build-rust.sh` builds a `cdylib` with `cargo rustc --crate-type cdylib`, so
`Cargo.toml` keeps `crate-type = ["rlib", "staticlib"]` and neither the terminal nor
the iOS build changes. Override the ABIs with `ANDROID_ABIS="arm64-v8a"` and the NDK
with `ANDROID_NDK_HOME`. `ANDROID_ABIS` must stay in sync with `abiFilters` in
`android/app/build.gradle.kts`.

## How it works

- `Bridge` owns one `MobileClient` per process, rooted in the app's private
  `files/` and `cache/` directories. Every client call blocks on the Rust Tokio
  runtime, so `Bridge.call` runs it on a bounded IO dispatcher, never the main thread.
- Screens: Home (Home/Movies/TV Shows/Anime tabs, ids `1`/`2`/`5`/`8`, infinite
  scroll), Search (with suggestions on empty results), Details (seasons and
  episodes, favorite toggle), a Sources sheet and Library (continue watching,
  favorites).
- `PlayerActivity` uses ExoPlayer, which opens direct files, HLS and DASH and sends
  the release's request headers on every request. Unlike the iOS AVPlayer, manifest
  releases are therefore playable. When a source fails, the player walks up to
  two alternatives at the same position.
- Subtitles: provider captions plus online subtitles appear under the CC button;
  picking one re-prepares the stream at the current position. Embedded text tracks
  are available from the player's own subtitle button.
- Progress is saved every 15 seconds and when the player stops; past 92% the entry
  is marked watched.
- Deep link: `moviebox://play/<subjectId>?season=1&episode=2` opens the source sheet.

## Platform notes

- DNS: Android has no `/etc/resolv.conf`, so the core skips its hickory resolver
  there and uses the platform's `getaddrinfo` (reqwest's default). Raw UDP queries
  to public DNS were slow or blocked often enough to exhaust the MovieBox host
  pool's 3-second connect timeout.
- Catalogue tabs: the Movies and TV Shows pages are mostly category tiles, and the
  home endpoint ignores `page`. With fewer than eight titles, those tabs borrow
  Home's movies or series (as the Roku channel does), and paging stops once a page
  adds nothing new. Anime keeps what the API returns: catalogue items carry no genre.
- Decoders: after a decoding error the player retries the same source at the same
  position with software decoders first (the emulator's `c2.goldfish.hevc.decoder`
  rejects HEVC streams that `c2.android.hevc.decoder` plays), then walks the
  alternatives.
- Logs: the core writes to logcat under the tag `moviebox-core`
  (`adb logcat -s moviebox-core MovieBoxPlayer`).
- Emulator: on recent images an `adb install -r` can briefly close the app while
  System UI handles the package update; relaunch it after a few seconds.
