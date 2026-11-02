# Tartarus-Solver-Mobile-warden-port

Android port of [arcasti/tli-warden](https://github.com/arcasti/tli-warden) — a live
advisor for the Tartarus prison board in *Torchlight: Infinite*.

The game runs on the phone. The app captures the screen with MediaProjection, reads
the board, runs the Rust solver core, and draws the recommended move as a
touch-through overlay above the game.

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
