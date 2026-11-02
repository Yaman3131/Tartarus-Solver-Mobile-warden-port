//! Builds the icon into warden.exe, for Explorer, the Start menu and Apps and features.

fn main() {
    println!("cargo:rerun-if-changed=assets/warden.rc");
    println!("cargo:rerun-if-changed=assets/warden.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        // The Android library logs to logcat through liblog (see src/jni.rs);
        // the icon resource is a Windows thing.
        println!("cargo:rustc-link-lib=log");
        return;
    }
    embed_resource::compile_for("assets/warden.rc", ["warden"], embed_resource::NONE)
        .manifest_required()
        .expect("assets/warden.rc: the icon resource did not compile");
}
