//! The Kotlin client's entry points into the library, registered with
//! `RegisterNatives` when Android loads libwarden.so (see [`JNI_OnLoad`]).
//! The other side is `dev.warden.android.native.WardenNative` (the `android/`
//! Gradle project): the class name, the method names and the signatures in
//! [`table`] are that contract, together with the OCR upcall to
//! `dev.warden.android.native.OcrSink` — ML Kit behind
//! `recognize(byte[] rgba, int w, int h)`, which gives a region's lines back
//! as JSON `[[text, x, y, w, h], ...]` in that image's own pixels, the shape
//! [`crate::reader::ocr::Backend`] takes.
//!
//! Conventions:
//!
//! - a `jlong` handle is a `Box::into_raw` pointer (a session is a
//!   `Mutex<Session>`: the capture thread reads frames while the UI thread
//!   polls); 0 is the null handle;
//! - what can fail gives JSON — `{"ok": true, ...}` or `{"error": "..."}` —
//!   and handles give 0 and booleans false when they cannot; errors and
//!   panics also go to logcat (`adb logcat -s warden`);
//! - every entry point catches panics (the `android-release` profile
//!   unwinds): one never crosses into Java.

use crate::app::runlog::RunLog;
use crate::app::session::{self, CellEdit, Session, Watch};
use crate::board::Model;
use crate::app::settings::{self, Settings};
use crate::app::snapshot;
use crate::engine::data::{Beast, Tool};
use crate::reader::layout::lay_out_scaled;
use crate::reader::model::{RUN_DAYS, to_model};
use crate::reader::ocr;
use crate::reader::panels::read_panels_titled;
use crate::reader::scene::read_scene;
use crate::reader::Rgba;
use jni::objects::{GlobalRef, JByteBuffer, JObject, JString, JValue};
use jni::sys::{self, jlong};
use jni::{JNIEnv, JavaVM, NativeMethod};
use serde_json::{Value, json};
use std::ffi::{c_char, c_void, CString};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, OnceLock};

/// The class the entry points are registered on: WardenNative.kt.
const CLASS: &str = "dev/warden/android/native/WardenNative";

/// The crate's version, as `wardenInit` reports it.
const VERSION: &str = env!("CARGO_PKG_VERSION");

/// The VM, set in [`JNI_OnLoad`] before any entry point runs.
static VM: OnceLock<JavaVM> = OnceLock::new();

/// logcat, through liblog (linked in build.rs).
mod logcat {
    use super::{c_char, CString};

    const INFO: i32 = 4;
    const ERROR: i32 = 6;

    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
    }

    fn write(prio: i32, msg: &str) {
        if let (Ok(tag), Ok(text)) = (CString::new("warden"), CString::new(msg)) {
            unsafe { __android_log_write(prio, tag.as_ptr(), text.as_ptr()) };
        }
    }

    pub fn info(msg: &str) {
        write(INFO, msg);
    }

    pub fn error(msg: &str) {
        write(ERROR, msg);
    }
}

/// Registers the entry points on [`CLASS`] when Android loads the library.
/// The client calls `System.loadLibrary("warden")` from `WardenNative`, so
/// the class is on the thread's class loader and findable here.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: *mut sys::JavaVM, _reserved: *mut c_void) -> sys::jint {
    let vm = match unsafe { JavaVM::from_raw(vm) } {
        Ok(vm) => vm,
        Err(e) => {
            logcat::error(&format!("JNI_OnLoad: no VM: {e}"));
            return sys::JNI_ERR;
        }
    };
    let mut env = match vm.get_env() {
        Ok(env) => env,
        Err(e) => {
            logcat::error(&format!("JNI_OnLoad: no env: {e}"));
            return sys::JNI_ERR;
        }
    };
    let class = match env.find_class(CLASS) {
        Ok(class) => class,
        Err(e) => {
            logcat::error(&format!("JNI_OnLoad: no class {CLASS}: {e}"));
            return sys::JNI_ERR;
        }
    };
    if let Err(e) = env.register_native_methods(class, &table()) {
        logcat::error(&format!("JNI_OnLoad: cannot register on {CLASS}: {e}"));
        return sys::JNI_ERR;
    }
    let _ = VM.set(vm);
    logcat::info(&format!("warden {VERSION} up"));
    sys::JNI_VERSION_1_6
}

/// The entry points, as `RegisterNatives` takes them: name, JNI signature,
/// function. The signatures must match WardenNative.kt exactly or the call
/// throws `UnsatisfiedLinkError`.
fn table() -> Vec<NativeMethod> {
    let f: &[(&str, &str, *mut c_void)] = &[
        ("wardenInit", "(Ljava/lang/String;)Ljava/lang/String;", init as *mut c_void),
        ("wardenVersion", "()Ljava/lang/String;", version as *mut c_void),
        ("wardenSettingsLoad", "()Ljava/lang/String;", settings_load as *mut c_void),
        ("wardenSettingsSave", "(Ljava/lang/String;)Ljava/lang/String;", settings_save as *mut c_void),
        ("wardenSetOcrSink", "(Ldev/warden/android/native/OcrSink;)Ljava/lang/String;", set_ocr_sink as *mut c_void),
        ("wardenOcrLanguage", "()Ljava/lang/String;", ocr_language as *mut c_void),
        ("wardenReadModel", "(Ljava/nio/ByteBuffer;IILjava/lang/String;)Ljava/lang/String;", read_model as *mut c_void),
        ("wardenSessionNew", "(ILjava/lang/String;II)J", session_new as *mut c_void),
        ("wardenSessionDrop", "(J)V", session_drop as *mut c_void),
        ("wardenSessionSetRollouts", "(JI)V", session_set_rollouts as *mut c_void),
        ("wardenSessionSetTools", "(JLjava/lang/String;)Ljava/lang/String;", session_set_tools as *mut c_void),
        ("wardenSessionSetBoard", "(JLjava/lang/String;)Ljava/lang/String;", session_set_board as *mut c_void),
        ("wardenSessionSetTheme", "(JI)V", session_set_theme as *mut c_void),
        ("wardenSessionSetRunLog", "(JZ)V", session_set_run_log as *mut c_void),
        ("wardenSessionSetNarrowOk", "(JZ)V", session_set_narrow_ok as *mut c_void),
        ("wardenSessionReadFrame", "(JLjava/nio/ByteBuffer;IIZ)V", session_read_frame as *mut c_void),
        ("wardenSessionPoll", "(J)Z", session_poll as *mut c_void),
        ("wardenSessionSnapshot", "(J)Ljava/lang/String;", session_snapshot as *mut c_void),
        ("wardenSessionWantsReread", "(J)Z", session_wants_reread as *mut c_void),
        ("wardenSessionReadPng", "(JLjava/lang/String;)Ljava/lang/String;", session_read_png as *mut c_void),
        ("wardenSessionEditCell", "(JILjava/lang/String;)Ljava/lang/String;", session_edit_cell as *mut c_void),
        ("wardenSessionSetMarker", "(JIZ)Ljava/lang/String;", session_set_marker as *mut c_void),
        ("wardenWatchNew", "()J", watch_new as *mut c_void),
        ("wardenWatchDrop", "(J)V", watch_drop as *mut c_void),
        ("wardenWatchSample", "(J[B)Z", watch_sample as *mut c_void),
        ("wardenWatchMarkRead", "(J[B)V", watch_mark_read as *mut c_void),
        ("wardenWatchAdoptNext", "(J)V", watch_adopt_next as *mut c_void),
        ("wardenWatchReadNext", "(J)V", watch_read_next as *mut c_void),
        ("wardenFingerprint", "(Ljava/nio/ByteBuffer;II)[B", fingerprint as *mut c_void),
    ];
    f.iter().map(|&(name, sig, fn_ptr)| NativeMethod { name: name.into(), sig: sig.into(), fn_ptr }).collect()
}

/// A panic's text.
fn panic_text(p: Box<dyn std::any::Any + Send>) -> String {
    let msg = p.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| p.downcast_ref::<String>().cloned());
    format!("panicked: {}", msg.unwrap_or_else(|| "unknown panic".to_string()))
}

/// Runs `body` and gives its JSON back, with errors and panics as
/// `{"error": "..."}` (and on logcat).
fn json(mut env: JNIEnv, body: impl FnOnce(&mut JNIEnv) -> Result<Value, String>) -> sys::jstring {
    let out = catch_unwind(AssertUnwindSafe(|| body(&mut env))).unwrap_or_else(|p| Err(panic_text(p)));
    let value = out.unwrap_or_else(|e| {
        logcat::error(&e);
        let _ = env.exception_clear();
        json!({ "error": e })
    });
    string(env, &value.to_string())
}

/// `text` as a Java string, or a null reference when it cannot be made.
fn string(env: JNIEnv, text: &str) -> sys::jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => JObject::null().into_raw() as _,
    }
}

/// Runs `body` for a plain value, reporting errors and panics to logcat and
/// clearing any exception a failed JNI call left pending; `fallback` when it
/// fails.
fn plain<T>(mut env: JNIEnv, fallback: T, body: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(&mut env))) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            logcat::error(&e);
            let _ = env.exception_clear();
            fallback
        }
        Err(p) => {
            logcat::error(&panic_text(p));
            let _ = env.exception_clear();
            fallback
        }
    }
}

/// A Java string argument's text.
fn text(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    Ok(env.get_string(s).map_err(|e| format!("a string argument: {e}"))?.into())
}

/// The frame in the direct `ByteBuffer` the client keeps: `w` x `h` RGBA
/// bytes (MediaProjection's RGBA_8888), copied out.
fn frame(env: &mut JNIEnv, buf: &JByteBuffer, w: i32, h: i32) -> Result<Rgba, String> {
    let size = |n: i32| usize::try_from(n).map_err(|_| format!("a negative frame size: {n}"));
    let (w, h) = (size(w)?, size(h)?);
    let n = w.checked_mul(h).and_then(|n| n.checked_mul(4)).ok_or("the frame is too large")?;
    let cap = env.get_direct_buffer_capacity(buf).map_err(|e| format!("not a direct buffer: {e}"))?;
    if cap < n {
        return Err(format!("the buffer holds {cap} bytes; the frame needs {n}"));
    }
    let addr = env.get_direct_buffer_address(buf).map_err(|e| format!("no buffer address: {e}"))?;
    // Safety: `addr` points at the buffer's `n` bytes, valid while the native
    // method runs; the copy leaves the buffer alone right after.
    let data = unsafe { std::slice::from_raw_parts(addr, n) }.to_vec();
    Ok(Rgba { w, h, data })
}

/// A byte array argument's bytes.
fn bytes(env: &JNIEnv, a: sys::jbyteArray) -> Result<Vec<u8>, String> {
    // Safety: the array is a local reference that outlives the call.
    let array = unsafe { jni::objects::JByteArray::from_raw(a) };
    env.convert_byte_array(&array).map_err(|e| format!("a byte array argument: {e}"))
}

/// The held instruments in a JSON array of keys, `["coin", "chains"]`;
/// unknown keys are dropped.
fn tools(text: &str) -> Result<Vec<Tool>, String> {
    let keys: Vec<String> = serde_json::from_str(text).map_err(|e| format!("tools: {e}"))?;
    Ok(keys.iter().filter_map(|k| Tool::from_key(k)).collect())
}

/// A cell correction as JSON: `{"kind": "locked"}`, `{"kind": "empty"}` or
/// `{"kind": "inmate", "beast": "lion", "tier": 2}`.
fn edit(text: &str) -> Result<CellEdit, String> {
    let v: Value = serde_json::from_str(text).map_err(|e| format!("edit: {e}"))?;
    let field = |k: &str| v.get(k).and_then(Value::as_str).map(|s| s.to_string());
    match field("kind").as_deref() {
        Some("locked") => Ok(CellEdit::Locked),
        Some("empty") => Ok(CellEdit::Empty),
        Some("inmate") => {
            let beast = serde_json::from_value::<Beast>(Value::String(field("beast").ok_or("edit: no beast")?))
                .map_err(|e| format!("edit: {e}"))?;
            let tier = v.get("tier").and_then(Value::as_i64).ok_or("edit: no tier")?;
            Ok(CellEdit::Inmate { beast, tier })
        }
        _ => Err("edit: the kind must be locked, empty or inmate".to_string()),
    }
}

/// An optional count as Kotlin passes it: -1 for none.
fn opt(n: i32) -> Option<u32> {
    (n >= 0).then_some(n as u32)
}

/// The session behind handle `h`, locked for the call.
fn with_session<T>(h: jlong, body: impl FnOnce(&mut Session) -> Result<T, String>) -> Result<T, String> {
    if h == 0 {
        return Err("no session: the handle is 0".to_string());
    }
    // Safety: the handle came from `wardenSessionNew` and `wardenSessionDrop`
    // has not taken it back; the lock guards every use.
    let mut session = unsafe { (*(h as *mut Mutex<Session>)).lock() }.unwrap_or_else(|e| e.into_inner());
    body(&mut session)
}

/// The watch behind handle `h`: the capture loop's own object.
fn with_watch<T>(h: jlong, body: impl FnOnce(&mut Watch) -> Result<T, String>) -> Result<T, String> {
    if h == 0 {
        return Err("no watch: the handle is 0".to_string());
    }
    // Safety: as `with_session`; the capture thread is the only caller.
    body(unsafe { &mut *(h as *mut Watch) })
}

/// The OCR upcall as an injected backend: every input image goes to Kotlin's
/// `OcrSink.recognize`, on whatever thread the reader runs on (attached for
/// the call, detached after), its JSON back as lines with boxes.
fn ocr_backend(sink: GlobalRef) -> ocr::Backend {
    Arc::new(move |inputs: Vec<Option<Rgba>>| match ocr_call_all(&sink, &inputs) {
        Ok(lines) => lines,
        Err(e) => {
            // One bad batch is not a failed read: the panels it covered just
            // read empty, as they do off the board.
            logcat::error(&format!("ocr: {e}"));
            inputs.iter().map(|_| Vec::new()).collect()
        }
    })
}

/// All the regions of one scan through the sink in a single upcall, so the
/// client can run them in parallel; it returns one line list per input, the
/// empty ones for `None` inputs.
fn ocr_call_all(sink: &GlobalRef, inputs: &[Option<Rgba>]) -> Result<Vec<Vec<(String, [f32; 4])>>, String> {
    let vm = VM.get().ok_or("the VM is not up")?;
    let mut guard = vm.attach_current_thread().map_err(|e| format!("attach: {e}"))?;
    let env = &mut *guard;
    let n = inputs.len();
    let images = env
        .new_object_array(n as i32, "[B", JObject::null())
        .map_err(|e| format!("ocr: {e}"))?;
    let mut widths = vec![0i32; n];
    let mut heights = vec![0i32; n];
    for (i, input) in inputs.iter().enumerate() {
        let Some(img) = input else { continue };
        let bytes = env.byte_array_from_slice(&img.data).map_err(|e| format!("ocr: {e}"))?;
        env.set_object_array_element(&images, i as i32, &bytes).map_err(|e| format!("ocr: {e}"))?;
        widths[i] = img.w as i32;
        heights[i] = img.h as i32;
    }
    let ws = env.new_int_array(n as i32).map_err(|e| format!("ocr: {e}"))?;
    env.set_int_array_region(&ws, 0, &widths).map_err(|e| format!("ocr: {e}"))?;
    let hs = env.new_int_array(n as i32).map_err(|e| format!("ocr: {e}"))?;
    env.set_int_array_region(&hs, 0, &heights).map_err(|e| format!("ocr: {e}"))?;
    let back = env
        .call_method(
            sink.as_obj(),
            "recognizeBatch",
            "([[B[I[I)Ljava/lang/String;",
            &[JValue::Object(&images), JValue::Object(&ws), JValue::Object(&hs)],
        )
        .map_err(|e| format!("OcrSink.recognizeBatch: {e}"))?;
    let text: String = env
        .get_string(&back.l().map_err(|e| format!("ocr: {e}"))?.into())
        .map_err(|e| format!("ocr: {e}"))?
        .into();
    serde_json::from_str(&text).map_err(|e| format!("OcrSink returned bad JSON: {e}"))
}

// The entry points. Each matches its row in `table`.

/// `wardenInit(filesDir)`: points the settings and the run log at the app's
/// files directory (the first call wins) and reports the version.
extern "system" fn init(env: JNIEnv, _class: JObject, files_dir: JString) -> sys::jstring {
    json(env, |env| {
        let dir: String = text(env, &files_dir)?;
        settings::set_app_dir(dir.into());
        Ok(json!({ "ok": true, "version": VERSION }))
    })
}

/// `wardenVersion()`.
extern "system" fn version(env: JNIEnv, _class: JObject) -> sys::jstring {
    string(env, &json!({ "ok": true, "version": VERSION }).to_string())
}

/// `wardenSettingsLoad()`: the saved settings (the defaults when there are
/// none or they don't parse).
extern "system" fn settings_load(env: JNIEnv, _class: JObject) -> sys::jstring {
    json(env, |_| Ok(json!({ "ok": true, "settings": Settings::load() })))
}

/// `wardenSettingsSave(settingsJson)`: the whole settings object, as
/// `wardenSettingsLoad` gave it, with the client's changes in.
extern "system" fn settings_save(env: JNIEnv, _class: JObject, settings_json: JString) -> sys::jstring {
    json(env, |env| {
        let s: Settings = serde_json::from_str(&text(env, &settings_json)?).map_err(|e| format!("settings: {e}"))?;
        s.save().map_err(|e| format!("save: {e}"))?;
        Ok(json!({ "ok": true }))
    })
}

/// `wardenSetOcrSink(sink)`: installs the ML Kit engine for the rest of the
/// process, in place of the platform's (none on Android).
extern "system" fn set_ocr_sink(env: JNIEnv, _class: JObject, sink: JObject) -> sys::jstring {
    json(env, |env| {
        let global = env.new_global_ref(&sink).map_err(|e| format!("the sink: {e}"))?;
        ocr::set_backend(ocr_backend(global));
        Ok(json!({ "ok": true }))
    })
}

/// `wardenOcrLanguage()`: what the engine in use reads ("en"), or null.
extern "system" fn ocr_language(env: JNIEnv, _class: JObject) -> sys::jstring {
    json(env, |_| Ok(json!({ "ok": true, "language": ocr::language() })))
}

/// `wardenReadModel(frame, w, h, toolsJson)`: the frame read statelessly, as
/// `warden-cli read` reads a PNG — no session, no corrections, the board
/// under nothing. A calibration and diagnostics aid.
extern "system" fn read_model(env: JNIEnv, _class: JObject, buf: JByteBuffer, w: i32, h: i32, tools_json: JString) -> sys::jstring {
    json(env, |env| {
        let held = tools(&text(env, &tools_json)?)?;
        let frame = frame(env, &buf, w, h)?;
        // The same read a session makes of a frame (see `app::session::scan`).
        let (board, title, u) = lay_out_scaled(&frame, None);
        let scene = read_scene(&board);
        let panels = read_panels_titled(&board, title.as_ref().unwrap_or(&board));
        let (model, warnings) = to_model(&scene, &panels, &held, None, RUN_DAYS);
        Ok(json!({
            "ok": true,
            "uiScale": u,
            "screen": panels.screen.label(),
            "days": panels.days,
            "theme": panels.theme,
            "held": panels.held,
            "model": model,
            "warnings": warnings.iter().map(|w| w.text.as_str()).collect::<Vec<_>>(),
        }))
    })
}

/// `wardenSessionNew(rollouts, toolsJson, theme, days)`: a session over a
/// run's readings (see [`Session::new`]); 0 when it cannot.
extern "system" fn session_new(env: JNIEnv, _class: JObject, rollouts: i32, tools_json: JString, theme: i32, days: i32) -> sys::jlong {
    plain(env, 0, |env| {
        let held = tools(&text(env, &tools_json)?)?;
        let session = Session::new(rollouts.max(1) as usize, held, opt(theme), opt(days));
        Ok(Box::into_raw(Box::new(Mutex::new(session))) as sys::jlong)
    })
}

/// `wardenSessionDrop(h)`: drops the session; its reader and solver threads
/// stop at their next send.
extern "system" fn session_drop(_env: JNIEnv, _class: JObject, h: sys::jlong) {
    if h != 0 {
        // Safety: the client's only handle to it, per the contract above.
        drop(unsafe { Box::from_raw(h as *mut Mutex<Session>) });
    }
}

/// `wardenSessionSetRollouts(h, rollouts)`: playouts per option for the next
/// solve.
extern "system" fn session_set_rollouts(env: JNIEnv, _class: JObject, h: sys::jlong, rollouts: i32) {
    plain(env, (), |_| {
        with_session(h, |s| {
            s.rollouts = rollouts.max(1) as usize;
            Ok(())
        })
    });
}

/// `wardenSessionSetTools(h, toolsJson)`: the held instruments, and a solve.
extern "system" fn session_set_tools(env: JNIEnv, _class: JObject, h: sys::jlong, tools_json: JString) -> sys::jstring {
    json(env, |env| {
        let held = tools(&text(env, &tools_json)?)?;
        with_session(h, |s| Ok(s.set_tools(held)))?;
        Ok(json!({ "ok": true }))
    })
}

/// `wardenSessionSetTheme(h, theme)`: the run's theme (-1 none), and a solve.
extern "system" fn session_set_theme(env: JNIEnv, _class: JObject, h: sys::jlong, theme: i32) {
    plain(env, (), |_| with_session(h, |s| Ok(s.set_theme(opt(theme)))));
}

/// `wardenSessionSetBoard(h, modelJson)`: a board built by hand, as the
/// reading on show — the editor's model, what it leaves out taken from the
/// blank board (see [`Session::install_board`]).
extern "system" fn session_set_board(env: JNIEnv, _class: JObject, h: sys::jlong, model_json: JString) -> sys::jstring {
    json(env, |env| {
        let model: Model = serde_json::from_str(&text(env, &model_json)?).map_err(|e| format!("model: {e}"))?;
        with_session(h, |s| Ok(s.install_board(model)))?;
        Ok(json!({ "ok": true }))
    })
}

/// `wardenSessionSetRunLog(h, on)`: keep a log of the run's reads, advice and
/// corrections, with the frames read, under filesDir/runs.
extern "system" fn session_set_run_log(env: JNIEnv, _class: JObject, h: sys::jlong, on: sys::jboolean) {
    plain(env, (), |_| {
        with_session(h, |s| {
            s.log = (on != 0).then(RunLog::in_appdata).flatten();
            Ok(())
        })
    });
}

/// `wardenSessionSetNarrowOk(h, on)`: read frames narrower than 16:9, by
/// width placement — the mobile client's layout.
extern "system" fn session_set_narrow_ok(env: JNIEnv, _class: JObject, h: sys::jlong, on: sys::jboolean) {
    plain(env, (), |_| {
        with_session(h, |s| Ok(s.set_narrow_ok(on != 0)))
    });
}

/// `wardenSessionReadPng(h, path)`: reads a PNG from the device's files as
/// a live frame — a saved screenshot, for testing the reader without the
/// game; the JNI layer's counterpart of the CLI's `read` on a PNG.
extern "system" fn session_read_png(env: JNIEnv, _class: JObject, h: sys::jlong, path: JString) -> sys::jstring {
    json(env, |env| {
        let path = text(env, &path)?;
        let bytes = std::fs::read(&path).map_err(|e| format!("{path}: {e}"))?;
        let frame = crate::reader::decode_png_raw(&bytes)?;
        with_session(h, |s| {
            s.read_live(frame);
            Ok(json!({ "ok": true }))
        })
    })
}

/// `wardenSessionReadFrame(h, frame, w, h, live)`: reads the frame off the
/// UI thread; `wardenSessionPoll` takes the result. A frame that arrives
/// while a read runs waits for it; only the newest waiting one is read next.
/// `live` frames come from the game itself (a changed board read is held for
/// one more read).
extern "system" fn session_read_frame(env: JNIEnv, _class: JObject, h: sys::jlong, buf: JByteBuffer, w: i32, ih: i32, live: sys::jboolean) {
    plain(env, (), |env| {
        let frame = frame(env, &buf, w, ih)?;
        with_session(h, |s| {
            if live != 0 {
                s.read_live(frame);
            } else {
                s.read_in_background(frame);
            }
            Ok(())
        })
    });
}

/// `wardenSessionPoll(h)`: takes the reader's and the solver's latest
/// results; true when something changed.
extern "system" fn session_poll(env: JNIEnv, _class: JObject, h: sys::jlong) -> sys::jboolean {
    plain(env, 0, |_| with_session(h, |s| Ok(u8::from(s.poll()))))
}

/// `wardenSessionSnapshot(h)`: everything the UI shows, as one JSON object
/// (see [`snapshot::take`]).
extern "system" fn session_snapshot(env: JNIEnv, _class: JObject, h: sys::jlong) -> sys::jstring {
    json(env, |_| with_session(h, |s| Ok(json!({ "ok": true, "snapshot": snapshot::take(s) }))))
}

/// `wardenSessionWantsReread(h)`: true once after a read was held: read the
/// game again.
extern "system" fn session_wants_reread(env: JNIEnv, _class: JObject, h: sys::jlong) -> sys::jboolean {
    plain(env, 0, |_| with_session(h, |s| Ok(u8::from(s.wants_reread()))))
}

/// `wardenSessionEditCell(h, cell, editJson)`: corrects screen cell `cell`
/// (2-16) and re-solves.
extern "system" fn session_edit_cell(env: JNIEnv, _class: JObject, h: sys::jlong, cell: i32, edit_json: JString) -> sys::jstring {
    json(env, |env| {
        let cell = u8::try_from(cell).map_err(|_| format!("cell {cell} can't be edited"))?;
        let e = edit(&text(env, &edit_json)?)?;
        with_session(h, |s| s.edit_cell(cell, e).map_err(|e| e.to_string()))?;
        Ok(json!({ "ok": true }))
    })
}

/// `wardenSessionSetMarker(h, cell, on)`: adds or removes the incident
/// marker on screen cell `cell` (2-16) and re-solves.
extern "system" fn session_set_marker(env: JNIEnv, _class: JObject, h: sys::jlong, cell: i32, on: sys::jboolean) -> sys::jstring {
    json(env, |_| {
        let cell = u8::try_from(cell).map_err(|_| format!("cell {cell} can't be edited"))?;
        with_session(h, |s| s.set_marker(cell, on != 0).map_err(|e| e.to_string()))?;
        Ok(json!({ "ok": true }))
    })
}

/// `wardenWatchNew()`: an auto-read watcher (see [`Watch`]).
extern "system" fn watch_new() -> sys::jlong {
    Box::into_raw(Box::new(Watch::default())) as sys::jlong
}

/// `wardenWatchDrop(h)`.
extern "system" fn watch_drop(_env: JNIEnv, _class: JObject, h: sys::jlong) {
    if h != 0 {
        // Safety: the capture loop's only handle to it.
        drop(unsafe { Box::from_raw(h as *mut Watch) });
    }
}

/// `wardenWatchSample(h, print)`: takes a sample's fingerprint (see
/// `wardenFingerprint`); true when the screen should be read, which records
/// it as read.
extern "system" fn watch_sample(env: JNIEnv, _class: JObject, h: sys::jlong, print: sys::jbyteArray) -> sys::jboolean {
    plain(env, 0, |env| with_watch(h, |w| Ok(u8::from(w.sample(bytes(env, print)?)))))
}

/// `wardenWatchMarkRead(h, print)`: records a screen read some other way.
extern "system" fn watch_mark_read(env: JNIEnv, _class: JObject, h: sys::jlong, print: sys::jbyteArray) {
    plain(env, (), |env| with_watch(h, |w| Ok(w.mark_read(bytes(env, print)?))));
}

/// `wardenWatchAdoptNext(h)`: counts the next sample as read, so an opened
/// screenshot stays on show until the game's screen changes.
extern "system" fn watch_adopt_next(env: JNIEnv, _class: JObject, h: sys::jlong) {
    plain(env, (), |_| with_watch(h, |w| Ok(w.adopt_next())));
}

/// `wardenWatchReadNext(h)`: reads the next sample whatever it shows.
extern "system" fn watch_read_next(env: JNIEnv, _class: JObject, h: sys::jlong) {
    plain(env, (), |_| with_watch(h, |w| Ok(w.read_next())));
}

/// `wardenFingerprint(frame, w, h)`: the frame's mean gray per block of a
/// 64 x 36 grid (see [`session::fingerprint`]), for `wardenWatchSample`.
extern "system" fn fingerprint(env: JNIEnv, _class: JObject, buf: JByteBuffer, w: i32, h: i32) -> sys::jbyteArray {
    plain(env, std::ptr::null_mut(), |env| {
        let frame = frame(env, &buf, w, h)?;
        let print = session::fingerprint(&frame);
        Ok(env.byte_array_from_slice(&print).map_err(|e| e.to_string())?.into_raw())
    })
}
