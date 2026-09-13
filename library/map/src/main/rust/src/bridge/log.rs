//! Logcat logging for the bridge.
//!
//! Pure move out of `bridge.rs`; no logic changes.

/// Logcat, at error level.
///
/// A renderer that fails silently is the thing this whole layer must not do: an empty map
/// looks exactly like a working map over the sea.
pub(crate) fn log(message: &str) {
    write_log(6, message);
}

/// Logcat, at info level, for the periodic frame report.
pub(crate) fn log_info(message: &str) {
    write_log(4, message);
}

fn write_log(priority: i32, message: &str) {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: i32, tag: *const u8, text: *const u8) -> i32;
    }
    let tag = b"MapRenderer\0";
    let mut text: Vec<u8> = message.as_bytes().to_vec();
    text.push(0);
    unsafe {
        __android_log_write(priority, tag.as_ptr(), text.as_ptr());
    }
}
