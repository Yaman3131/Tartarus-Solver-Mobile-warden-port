package dev.warden.android.native

import java.nio.ByteBuffer

/**
 * The Rust core, through the JNI layer (`src/jni.rs`, bound by
 * RegisterNatives in JNI_OnLoad). The signatures match `table()` there
 * exactly; the parameter names here are free.
 *
 * Everything that can fail returns JSON: `{"error": "..."}` or
 * `{"ok": true, ...}`. Handles are 0 on error. Errors and panics also go to
 * logcat (`adb logcat -s warden`). Session handles are mutexed on the Rust
 * side — the capture thread can read a frame while the UI thread polls — so
 * no locking is needed here.
 */
object WardenNative {
    init {
        // Loads libwarden.so; JNI_OnLoad then binds the methods below.
        System.loadLibrary("warden")
    }

    /** Sets the app dir (settings, run log) under it; `{"ok":true,"version":...}`. */
    external fun wardenInit(filesDir: String): String

    external fun wardenVersion(): String

    /** `{"ok":true,"settings":{...}}` — partial JSON, defaults filled. */
    external fun wardenSettingsLoad(): String

    external fun wardenSettingsSave(settingsJson: String): String

    /** Installs the OCR backend (ML Kit); `{"ok":true}` or `{"error":...}`. */
    external fun wardenSetOcrSink(sink: OcrSink): String

    external fun wardenOcrLanguage(): String

    /** A one-off read of one frame, outside a session: `{"ok":true,"model":...}`. */
    external fun wardenReadModel(frame: ByteBuffer, w: Int, h: Int, toolsJson: String): String

    /**
     * A solver session. `theme`/`days`: -1 = not set. The handle is 0 when
     * the session could not be made; drop it with [wardenSessionDrop].
     */
    external fun wardenSessionNew(rollouts: Int, toolsJson: String, theme: Int, days: Int): Long

    external fun wardenSessionDrop(h: Long)

    external fun wardenSessionSetRollouts(h: Long, rollouts: Int)

    /** Held instruments as a JSON array of keys, `["coin","chains"]`. */
    external fun wardenSessionSetTools(h: Long, toolsJson: String): String

    /**
     * A board built by hand (the editor's): installed as the reading on
     * show. `modelJson` is the analyzer's Model JSON, camelCase, missing
     * fields defaulted — `{"day":5,"step":"manage","cells":[...]}` is enough.
     */
    external fun wardenSessionSetBoard(h: Long, modelJson: String): String

    /** A theme id of the game data (301..304); -1 = no theme. */
    external fun wardenSessionSetTheme(h: Long, theme: Int)

    external fun wardenSessionSetRunLog(h: Long, on: Boolean)

    /** A frame narrower than 16:9 is allowed to be read (mobile layout). */
    external fun wardenSessionSetNarrowOk(h: Long, ok: Boolean)

    /**
     * Reads a frame (direct ByteBuffer, RGBA_8888 byte order, `w*h*4`
     * capacity) in the background. `live`: the game is on show, not a
     * screenshot dialog.
     */
    external fun wardenSessionReadFrame(h: Long, frame: ByteBuffer, w: Int, height: Int, live: Boolean)

    /** Takes the worker's latest results; true when something changed. */
    external fun wardenSessionPoll(h: Long): Boolean

    /** The session's state as one JSON object (see `src/app/snapshot.rs`). */
    external fun wardenSessionSnapshot(h: Long): String

    /** True once after a read was held: the UI reads the game again. */
    external fun wardenSessionWantsReread(h: Long): Boolean

    /**
     * Reads a PNG file as a live frame — a saved screenshot, for testing the
     * reader without the game.
     */
    external fun wardenSessionReadPng(h: Long, path: String): String

    /**
     * A cell correction: `{"kind":"locked"}`, `{"kind":"empty"}` or
     * `{"kind":"inmate","beast":"lion","tier":2}`.
     */
    external fun wardenSessionEditCell(h: Long, cell: Int, editJson: String): String

    /** An incident marker on a cell, for the solver's memory. */
    external fun wardenSessionSetMarker(h: Long, cell: Int, on: Boolean): String

    external fun wardenWatchNew(): Long

    external fun wardenWatchDrop(h: Long)

    /** A screen fingerprint sample; true when the watch changed its mind. */
    external fun wardenWatchSample(h: Long, print: ByteArray): Boolean

    external fun wardenWatchMarkRead(h: Long, print: ByteArray)

    external fun wardenWatchAdoptNext(h: Long)

    external fun wardenWatchReadNext(h: Long)

    /** The frame's fingerprint, for the watch's samples. */
    external fun wardenFingerprint(frame: ByteBuffer, w: Int, h: Int): ByteArray
}
