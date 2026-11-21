# Android client

Objective: native Android app (Kotlin + Jetpack Compose + Media3 ExoPlayer) over the shared Rust core through UniFFI, like `ios/`.
Branch: `feat/android-client`. Delivery: ask-on-risk. Test runner: `cargo test`, Gradle `assembleDebug`, emulator smoke run.

## Specs

- S1: "Haz una version para android, itera hasta que este funcional" — an installable Android app that browses, searches, shows details and plays streams.
- S2: Reuse `MobileClient` (`src/ffi.rs`, `mobile` feature); no provider logic in Kotlin.
- S3: Terminal build and iOS build stay unchanged (no new default dependencies, `crate-type` unchanged).
- S4: Screens: Home tabs (Home/Movies/TV Shows/Anime, ids `1`/`2`/`5`/`8`), Search, Details with seasons/episodes, Sources, Player (request headers, subtitles, resume, progress), Library (favorites, history).
- S5: Reproducible build: `android/scripts/build-rust.sh` (NDK `.so` + Kotlin bindings) and `./gradlew assembleDebug`.

## Tasks

- [x] T1 (S2,S3,S5) Rust cross-build for Android ABIs + Kotlin bindings — inline
- [x] T2 (S1,S4,S5) Gradle project, Compose screens, ExoPlayer — inline
- [x] T3 (S1) Emulator smoke run: home loads, search, details, play — inline
- [x] T4 (S5) Docs (`docs/android-client.md`) — inline

## Log

- L1 (2026-11-21): "Haz una version para android, itera hasta que este funcional"
- L2: sdkmanager (android-cli) hung installing `ndk/30.0.16248370`; installed NDK r30 from the official `android-ndk-r30-darwin.zip` instead.
- L3: Emulator iteration fixes:
  - Core logs go to logcat (`moviebox-core`), and host failures print the full error chain (`logging::error_chain`).
  - On Android the core uses `getaddrinfo` instead of hickory: the public-DNS UDP fallback hit the 3 s connect timeout ("All hosts exhausted").
  - Movies/TV Shows tabs borrow Home titles when thin.
  - Paging stops on repeated pages.
  - The first page is deduped, which fixes a duplicate-key crash in Search.
  - On a decoding error the player retries with software decoders first.
  - Tab colors fixed.
- L4: Verified on emulator (API 37, arm64):
  - All four home tabs load; Movies and TV Shows show full grids.
  - Search "breaking bad" works.
  - Rick and Morty details show seasons and episodes, and the sources sheet lists 2 releases.
  - 720p HEVC plays (software decoder) with Spanish subtitles.
  - Progress is saved to Continue watching (1:18 / 22:07).
  - Host: `cargo test` all pass; `cargo clippy --all-targets --features mobile -D warnings` clean; `cargo fmt --check` diffs only in pre-existing untouched code.
  - Not verified: favorites toggle, deep link, Android TV/D-pad, a physical device.
