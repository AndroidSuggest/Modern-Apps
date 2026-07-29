use std::{borrow::Cow, ffi::CStr, io, os::raw::c_void, ptr};

use thiserror::Error;

use crate::{
    sys::{JavaVMInitArgs, JavaVMOption},
    JNIVersion,
};

mod char_encoding_generic;

#[derive(Debug, Error)]
#[non_exhaustive]
pub enum JvmError {
    #[error("internal null in option: {0}")]
    NullOptString(String),
    #[error("option is too long: {opt_string}")]
    #[non_exhaustive]
    OptStringTooLong { opt_string: String },
    #[error("option {opt_string:?} is not representable")]
    #[non_exhaustive]
    OptStringNotRepresentable { opt_string: String },
    #[error("couldn't convert option {opt_string:?}: {error}")]
    #[non_exhaustive]
    OptStringTranscodeFailure { opt_string: String, #[source] error: io::Error },
}

impl JvmError {
    pub fn opt_string(&self) -> Option<&str> {
        match self {
            Self::NullOptString(o) => Some(o),
            Self::OptStringTooLong { opt_string } => Some(opt_string),
            Self::OptStringNotRepresentable { opt_string } => Some(opt_string),
            Self::OptStringTranscodeFailure { opt_string, .. } => Some(opt_string),
        }
        .map(String::as_str)
    }
}

const SPECIAL_OPTIONS: &[&str] = &["vfprintf", "abort", "exit"];
const SPECIAL_OPTIONS_C: &[&CStr] = unsafe {
    &[
        CStr::from_bytes_with_nul_unchecked(b"vfprintf\0"),
        CStr::from_bytes_with_nul_unchecked(b"abort\0"),
        CStr::from_bytes_with_nul_unchecked(b"exit\0"),
    ]
};

#[derive(Debug)]
pub struct InitArgsBuilder<'a> {
    opts: Result<Vec<Cow<'a, CStr>>, JvmError>,
    ignore_unrecognized: bool,
    version: JNIVersion,
}

impl<'a> Default for InitArgsBuilder<'a> {
    fn default() -> Self {
        InitArgsBuilder { opts: Ok(vec![]), ignore_unrecognized: false, version: JNIVersion::V8 }
    }
}

impl<'a> InitArgsBuilder<'a> {
    pub fn new() -> Self { Default::default() }
    pub fn option(mut self, opt_string: impl AsRef<str> + Into<Cow<'a, str>>) -> Self {
        if let Err(error) = self.try_option(opt_string) { self.opts = Err(error); }
        self
    }
    pub fn try_option(&mut self, opt_string: impl Into<Cow<'a, str>>) -> Result<(), JvmError> {
        let opt_string = opt_string.into();
        let opts = match &mut self.opts { Ok(ok) => ok, Err(_) => return Ok(()) };
        // Empty check – required for win32 WideCharToMultiByte safety; kept for compatibility
        if matches!(opt_string.as_ref(), "" | "\0") {
            opts.push(Cow::Borrowed(unsafe { CStr::from_bytes_with_nul_unchecked(b"\0") }));
            return Ok(());
        }
        if SPECIAL_OPTIONS.contains(&&*opt_string) { return Ok(()); }
        // armv8-only: no windows-sys path – UTF-8 on all targets
        let encoded = char_encoding_generic::utf8_to_cstr(opt_string)?;
        opts.push(encoded);
        Ok(())
    }
    pub fn option_encoded(mut self, opt_string: impl Into<Cow<'a, CStr>>) -> Self {
        let opt_string = opt_string.into();
        let opts = match &mut self.opts { Ok(ok) => ok, Err(_) => return self };
        if SPECIAL_OPTIONS_C.contains(&&*opt_string) { return self; }
        opts.push(opt_string);
        self
    }
    pub fn version(self, version: JNIVersion) -> Self {
        let mut s = self;
        s.version = version;
        s
    }
    pub fn ignore_unrecognized(self, ignore: bool) -> Self {
        let mut s = self;
        s.ignore_unrecognized = ignore;
        s
    }
    pub fn build(self) -> Result<InitArgs<'a>, JvmError> {
        let opt_strings = self.opts?;
        let opts: Vec<JavaVMOption> = opt_strings
            .iter()
            .map(|o| JavaVMOption { optionString: o.as_ptr() as _, extraInfo: ptr::null_mut() })
            .collect();
        Ok(InitArgs {
            inner: JavaVMInitArgs {
                version: self.version.into(),
                ignoreUnrecognized: self.ignore_unrecognized as _,
                options: opts.as_ptr() as _,
                nOptions: opts.len() as _,
            },
            _opts: opts,
            _opt_strings: opt_strings,
        })
    }
    pub fn options(&self) -> Result<&[Cow<'a, CStr>], &JvmError> {
        self.opts.as_ref().map(Vec::as_slice)
    }
}

pub struct InitArgs<'a> {
    inner: JavaVMInitArgs,
    _opts: Vec<JavaVMOption>,
    _opt_strings: Vec<Cow<'a, CStr>>,
}
impl<'a> InitArgs<'a> { pub(crate) fn inner_ptr(&self) -> *mut c_void { &self.inner as *const _ as _ } }
