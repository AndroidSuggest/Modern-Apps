//! Dictionary parsing and schema validation: [`Dictionary::parse`] plus
//! `check_matches_schema`.
//!
//! Pure moves out of the former single-file dict module; nothing here changed.

use crate::proto::{err, Result};

use super::types::Dictionary;

impl Dictionary {
    pub fn parse(buf: &[u8]) -> Result<Dictionary> {
        let mut at = 0usize;
        let mut table = |what: &str, limit: usize| -> Result<Vec<String>> {
            if at + 2 > buf.len() {
                return err(format!("a .mamaps dictionary ends before its {what} count"));
            }
            let count = u16::from_le_bytes([buf[at], buf[at + 1]]) as usize;
            at += 2;
            if count > limit {
                return err(format!(
                    "a .mamaps dictionary declares {count} {what}, past the {limit} this format allows"
                ));
            }
            let mut names = Vec::with_capacity(count);
            for _ in 0..count {
                if at >= buf.len() {
                    return err(format!("a .mamaps dictionary ends inside its {what}"));
                }
                let len = buf[at] as usize;
                at += 1;
                if at + len > buf.len() {
                    return err(format!("a .mamaps {what} name runs past the dictionary"));
                }
                let name = std::str::from_utf8(&buf[at..at + len])
                    .map_err(|_| crate::proto::Error(format!("a .mamaps {what} name is not UTF-8")))?;
                names.push(name.to_string());
                at += len;
            }
            Ok(names)
        };
        // A layer id is a `u8` and a kind id a `u16`, so those are the real ceilings; naming them
        // here is what stops a corrupt count from asking for a gigabyte of `String`s.
        let layers = table("layer", u8::MAX as usize)?;
        let kinds = table("kind", u16::MAX as usize - 1)?;
        let details = table("kind_detail", u16::MAX as usize - 1)?;
        Ok(Dictionary { layers, kinds, details })
    }

    /// Refuse an archive whose tables are not the ones this build was compiled against.
    ///
    /// A **whole-table** comparison rather than a length check: the point of a constant table is
    /// that id 17 means `national_park` in every archive ever written, so an archive that
    /// disagrees anywhere would render some layer in the wrong colour with no other symptom.
    ///
    /// Strict by deliberate scope decision (no backward-compat requirement: nobody is using the
    /// app): the dict is frozen, every archive is built from this tree, and an archive built
    /// from anything else is refused rather than reinterpreted. An older archive — including
    /// prod planet.mamaps with its v1 dict — does NOT open under this reader; rebuild it from
    /// the frozen tree instead.
    pub fn check_matches_schema(&self) -> Result<()> {
        let schema = Dictionary::schema();
        for (what, ours, theirs) in [
            ("layer", &schema.layers, &self.layers),
            ("kind", &schema.kinds, &self.kinds),
            ("kind_detail", &schema.details, &self.details),
        ] {
            if ours == theirs {
                continue;
            }
            let first = ours
                .iter()
                .zip(theirs.iter())
                .position(|(a, b)| a != b)
                .unwrap_or(ours.len().min(theirs.len()));
            return err(format!(
                "a .mamaps {what} table disagrees with this build's schema from id {first} \
                 ({} entries here, {} in the archive)",
                ours.len(),
                theirs.len(),
            ));
        }
        Ok(())
    }
}
