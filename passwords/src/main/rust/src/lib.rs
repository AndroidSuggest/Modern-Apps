//! KDBX (KeePass) read/write for the `passwords` app, backed by the Rust
//! `keepass` crate and exposed to Kotlin over JNI. Replaces keepassjava2 +
//! Bouncy Castle while keeping existing `.kdbx` vaults readable.
//!
//! The data model crossing JNI is deliberately dumb: a vault is a flat list of
//! entries, each entry a `{ fieldName: value }` string map. All KeePass field
//! semantics (standard fields like `Title`/`UserName`/`Password`/`URL` plus
//! arbitrary custom string fields) collapse into that one map, so every bit of
//! app-specific logic (passkey vs. password classification, TOTP parsing, the
//! `KPEX_PASSKEY_*` and `_Type` conventions) stays in Kotlin, unchanged.

use keepass::db::{fields, Database, Value};
use keepass::DatabaseKey;
use std::collections::BTreeMap;

/// A single KeePass entry as a flat field map (both standard and custom fields).
type EntryMap = BTreeMap<String, String>;

/// Open a KDBX vault and return every entry (from every group, recursively) as
/// a JSON array of `{ field: value }` objects. Returns `None` on wrong password
/// or malformed input.
pub fn import_kdbx(password: &str, data: &[u8]) -> Option<String> {
    let key = DatabaseKey::new().with_password(password);
    let db = Database::parse(data, key).ok()?;

    let mut out: Vec<EntryMap> = Vec::new();
    collect_group(&db, db.root().id(), &mut out);
    serde_json::to_string(&out).ok()
}

fn collect_group(db: &Database, group_id: keepass::db::GroupId, out: &mut Vec<EntryMap>) {
    let group = match db.group(group_id) {
        Some(g) => g,
        None => return,
    };
    for entry in group.entries() {
        let mut map = EntryMap::new();
        for (k, v) in &entry.fields {
            map.insert(k.clone(), v.get().clone());
        }
        out.push(map);
    }
    // Collect child group ids first to avoid holding the GroupRef borrow across recursion.
    let child_ids: Vec<_> = group.group_ids().collect();
    for child in child_ids {
        collect_group(db, child, out);
    }
}

/// Build a KDBX4 vault from a JSON array of `{ field: value }` objects and
/// return the encrypted bytes. Every entry is added to the root group. The
/// `Password` field is stored protected (KeePass default MemoryProtection);
/// all other fields are stored unprotected. Returns `None` on bad JSON or an
/// encryption failure.
pub fn export_kdbx(password: &str, entries_json: &str) -> Option<Vec<u8>> {
    let entries: Vec<EntryMap> = serde_json::from_str(entries_json).ok()?;

    let mut db = Database::new();
    for entry_map in entries {
        let mut root = db.root_mut();
        let mut entry = root.add_entry();
        for (k, val) in entry_map {
            if k == fields::PASSWORD {
                entry.set(k, Value::protected(val));
            } else {
                entry.set(k, Value::unprotected(val));
            }
        }
    }

    let key = DatabaseKey::new().with_password(password);
    let mut buf = Vec::new();
    db.save(&mut buf, key).ok()?;
    Some(buf)
}

mod jni_bridge;

#[cfg(test)]
mod tests;
