//! Native engine for the Translate app: the JNI boundary for on-device
//! translation, language detection and (offline) speech availability.
//!
//! # Why this is a stub
//!
//! The user's goal is *fully on-device* translation (ideally ncnn) and *offline*
//! speech-to-text (whisper/Vosk). Both require:
//!   1. Model weights (hundreds of MB) which are **not bundled** in this repo, and
//!   2. A native inference runtime that must be **built on-device / per-ABI**,
//!      which cannot be compiled or verified in this environment.
//!
//! Rather than fabricate translations, this crate exposes the real JNI surface
//! the Kotlin side expects and returns honest "not available" values:
//!   * [`load_model`] returns `0` (no model) because no weights are present.
//!   * [`translate`] / [`detect_language`] return `None` (→ null jstring), so the
//!     Kotlin `NativeTranslator` reports `isAvailable() == false` and the UI shows
//!     "Translation model not installed".
//!   * [`speech_available`] returns `false`, so Kotlin falls back to the platform
//!     `android.speech.SpeechRecognizer`.
//!
//! # Where a real backend plugs in
//!
//! `load_model` would `ncnn::Net::load_param/load_model` (or memory-map a
//! flatbuffer NMT model) from `model_dir`, box it, and return the pointer as an
//! opaque `jlong` handle. `translate` would tokenize `text`, run the encoder /
//! decoder (with a language tag derived from `from`/`to`), detokenize, and return
//! the string. `free_model` would drop the boxed net. `detect_language` would run
//! a small fastText-style classifier. None of that changes the JNI signatures
//! below, so the drop-in is isolated to this file.

mod jni_bindings;

/// Opaque model handle. `0` means "no model loaded". A real backend would return
/// `Box::into_raw(Box::new(net)) as i64`.
pub type ModelHandle = i64;

/// Load a translation model bundle from `model_dir`. Stubbed to always report
/// "no model" (`0`) because no weights are shipped. The path is where a real
/// downloader would place `*.param` / `*.bin` files.
pub fn load_model(_model_dir: &str) -> ModelHandle {
    0
}

/// Free the model behind `handle`. No-op for the stub (nothing was allocated).
/// A real backend would reconstruct the `Box` from the raw pointer and drop it.
pub fn free_model(handle: ModelHandle) {
    if handle == 0 {
        return;
    }
    // Real backend:
    //   unsafe { drop(Box::from_raw(handle as usize as *mut Net)); }
}

/// Best-effort language detection. Stubbed: no classifier bundled → `None`.
pub fn detect_language(_handle: ModelHandle, _text: &str) -> Option<String> {
    None
}

/// Translate `text` from `from` (None = auto-detect) to `to`. Stubbed: returns
/// `None` whenever no model is loaded, which is always in this build. This is
/// deliberate — we never invent a translation.
pub fn translate(handle: ModelHandle, _text: &str, _from: Option<&str>, _to: &str) -> Option<String> {
    if handle == 0 {
        return None;
    }
    // Real backend runs inference here and returns Some(translated).
    None
}

/// Whether an offline speech-to-text model is present. Stubbed to `false` (no
/// bundled whisper/Vosk weights) so Kotlin uses the platform recognizer.
pub fn speech_available() -> bool {
    false
}
