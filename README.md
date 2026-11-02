# Tartarus-Solver-Mobile-warden-port

Android port of [arcasti/tli-warden](https://github.com/arcasti/tli-warden) — a live
advisor for the Tartarus prison board in *Torchlight: Infinite*.

The game runs on the phone. The app captures the screen with MediaProjection, reads
the board, runs the Rust solver core, and draws the recommended move as a
touch-through overlay above the game.

<img width="1280" height="841" alt="photo_2026-11-03 00 16 46" src="https://github.com/user-attachments/assets/e55ecc29-315b-4037-94c0-7f5a16e64ee6" />
<img width="1280" height="841" alt="photo_2026-11-03 00 17 00" src="https://github.com/user-attachments/assets/0f0bee46-81ae-46f8-9070-1e4dce9bee57" />
<img width="1280" height="841" alt="photo_2026-11-03 00 16 57" src="https://github.com/user-attachments/assets/11e0e231-358f-4320-b240-ce9d9ae697d0" />
<img width="1280" height="841" alt="photo_2026-11-03 00 16 54" src="https://github.com/user-attachments/assets/12bf27d3-1a9b-4429-9928-b8ffc209c02e" />
<img width="1280" height="841" alt="photo_2026-11-03 00 16 51" src="https://github.com/user-attachments/assets/1a94571e-83f9-4c1a-8edb-e0cf22edce58" />









## Layout

| Path | What it is |
| --- | --- |
| `src/` | Rust core (reader, solver, app state), plus `src/jni.rs` for Android |
| `android/` | Gradle project; Kotlin UI, capture service, overlay |
| `assets/`, `data/`, `docs/` | Board data, assets, notes |
| `tests/`, `examples/` | Tests and developer tools |

## Build

The Kotlin app loads `libwarden.so`, which is cross-compiled with cargo-ndk and
placed in `android/app/src/main/jniLibs/`.

```sh
# Rust core for the phone (arm64-v8a)
cargo ndk -t arm64-v8a -P 26 -o build/android build --lib --profile android-release

# APK
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  gradle -p android assembleDebug
```

`ANDROID_NDK_HOME` must point at an installed NDK. A `buildRust` Gradle task runs
the cargo-ndk step automatically as part of `preBuild`.

A release build reads signing material from `keystore/signing.properties`; that file
and the keystore are intentionally not in this repository. Without them, use
`assembleDebug` — `assembleRelease` has no signing material to work with.

## Status

P0–P5 of the port are implemented and were verified on a physical device against
the live game: capture, OCR, board reconstruction, solver advice, and the marker
overlay.

## License

GPL-3.0-only, inherited from the upstream project. See `LICENSE`.
