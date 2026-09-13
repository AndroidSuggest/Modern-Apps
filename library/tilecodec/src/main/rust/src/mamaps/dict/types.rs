//! The interned dictionary type and its cheap accessors; the wire codec lives in
//! `serialize`/`parse`, the constant tables in `tables`.
//!
//! Pure moves out of the former single-file dict module; nothing here changed.

use super::tables::{DETAILS, KINDS, LAYERS, NONE};

/// The interned tables as the archive carries them.
///
/// Parsed from the file rather than assumed, so a reader can tell a mismatched archive from
/// a mismatched reader.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Dictionary {
    pub layers: Vec<String>,
    pub kinds: Vec<String>,
    pub details: Vec<String>,
}

impl Dictionary {
    /// The compiled-in schema, which is what a writer always emits.
    pub fn schema() -> Dictionary {
        let owned = |table: &[&str]| table.iter().map(|s| s.to_string()).collect();
        Dictionary { layers: owned(LAYERS), kinds: owned(KINDS), details: owned(DETAILS) }
    }

    pub fn layer_name(&self, id: u8) -> Option<&str> {
        self.layers.get(id as usize).map(String::as_str)
    }

    /// The name of a `kind` id, or `None` for [`NONE`] and for anything past the table.
    pub fn kind_name(&self, id: u16) -> Option<&str> {
        (id != NONE).then(|| self.kinds.get(id as usize - 1).map(String::as_str)).flatten()
    }

    pub fn detail_name(&self, id: u16) -> Option<&str> {
        (id != NONE).then(|| self.details.get(id as usize - 1).map(String::as_str)).flatten()
    }
}
