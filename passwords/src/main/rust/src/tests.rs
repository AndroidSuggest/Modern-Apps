//! Host-side round-trip + interop tests for the KDBX read/write core.
//!
//! `cargo test` on the host proves the pure-Rust logic (no JNI) is correct
//! before it is ever cross-compiled for Android. We exercise the exact field
//! conventions the `passwords` app relies on (standard fields + custom
//! `Websites` / `otp` / `_Type` / `KPEX_PASSKEY_*` strings) and confirm a vault
//! written by this code re-opens with identical data.

use super::*;
use keepass::db::{fields, Database};
use keepass::DatabaseKey;
use serde_json::{json, Value as Json};

const PW: &str = "correct horse battery staple";

fn entries_of(json_str: &str) -> Vec<Json> {
    serde_json::from_str(json_str).unwrap()
}

fn find_entry<'a>(entries: &'a [Json], title: &str) -> &'a Json {
    entries
        .iter()
        .find(|e| e.get("Title").and_then(|t| t.as_str()) == Some(title))
        .unwrap_or_else(|| panic!("no entry titled {title}"))
}

#[test]
fn export_then_import_roundtrips_all_fields() {
    let entries = json!([
        {
            "Title": "GitHub",
            "UserName": "octocat",
            "Password": "s3cr3t!",
            "URL": "https://github.com",
            "Websites": "https://github.com\nhttps://gist.github.com",
            "otp": "otpauth://totp/?secret=JBSWY3DPEHPK3PXP",
            "_Type": "password"
        },
        {
            "Title": "Acme Passkey",
            "UserName": "alice",
            "URL": "acme.example",
            "_Type": "passkey",
            "KPEX_PASSKEY_USERNAME": "alice",
            "KPEX_PASSKEY_PRIVATE_KEY_PEM": "TUlHSEFnRUE=",
            "KPEX_PASSKEY_CREDENTIAL_ID": "cred-123",
            "KPEX_PASSKEY_USER_HANDLE": "user-abc",
            "KPEX_PASSKEY_RELYING_PARTY": "acme.example"
        }
    ])
    .to_string();

    let bytes = export_kdbx(PW, &entries).expect("export");
    let out = import_kdbx(PW, &bytes).expect("import");
    let parsed = entries_of(&out);

    assert_eq!(parsed.len(), 2, "entry count preserved");

    let gh = find_entry(&parsed, "GitHub");
    assert_eq!(gh["UserName"], "octocat");
    assert_eq!(gh["Password"], "s3cr3t!");
    assert_eq!(gh["URL"], "https://github.com");
    assert_eq!(gh["Websites"], "https://github.com\nhttps://gist.github.com");
    assert_eq!(gh["otp"], "otpauth://totp/?secret=JBSWY3DPEHPK3PXP");
    assert_eq!(gh["_Type"], "password");

    let pk = find_entry(&parsed, "Acme Passkey");
    assert_eq!(pk["KPEX_PASSKEY_PRIVATE_KEY_PEM"], "TUlHSEFnRUE=");
    assert_eq!(pk["KPEX_PASSKEY_CREDENTIAL_ID"], "cred-123");
    assert_eq!(pk["KPEX_PASSKEY_RELYING_PARTY"], "acme.example");
}

#[test]
fn password_field_is_stored_protected() {
    // Default KeePass MemoryProtection protects the Password field; verify our
    // writer marks it protected (matching the previous keepassjava2 behavior)
    // while leaving other fields unprotected.
    let entries = json!([{ "Title": "x", "Password": "hunter2", "UserName": "u" }]).to_string();
    let bytes = export_kdbx(PW, &entries).unwrap();

    let db = Database::parse(&bytes, DatabaseKey::new().with_password(PW)).unwrap();
    let root = db.root();
    let entry = root.entries().next().unwrap();
    assert!(entry.fields[fields::PASSWORD].is_protected());
    assert!(!entry.fields[fields::USERNAME].is_protected());
    // Value still round-trips in the clear.
    assert_eq!(entry.get(fields::PASSWORD), Some("hunter2"));
}

#[test]
fn wrong_password_fails_to_import() {
    let entries = json!([{ "Title": "x", "Password": "p" }]).to_string();
    let bytes = export_kdbx(PW, &entries).unwrap();
    assert!(import_kdbx("not the password", &bytes).is_none());
}

#[test]
fn entries_in_nested_groups_are_flattened() {
    // A vault authored elsewhere may nest entries in sub-groups; import must
    // surface every entry regardless of group (matching the old recursion).
    let mut db = Database::new();
    db.root_mut()
        .add_entry()
        .set_unprotected(fields::TITLE, "TopLevel");
    let sub_id = db.root_mut().add_group().edit(|g| g.name = "Sub".into()).id();
    db.group_mut(sub_id)
        .unwrap()
        .add_entry()
        .set_unprotected(fields::TITLE, "Nested");

    let mut bytes = Vec::new();
    db.save(&mut bytes, DatabaseKey::new().with_password(PW)).unwrap();

    let out = import_kdbx(PW, &bytes).expect("import");
    let parsed = entries_of(&out);
    let titles: Vec<&str> = parsed
        .iter()
        .filter_map(|e| e.get("Title").and_then(|t| t.as_str()))
        .collect();
    assert!(titles.contains(&"TopLevel"));
    assert!(titles.contains(&"Nested"));
}

#[test]
fn malformed_input_returns_none() {
    assert!(import_kdbx(PW, b"not a kdbx file at all").is_none());
    assert!(export_kdbx(PW, "{ not json ]").is_none());
}

/// THE interop test: this vault was produced by the OLD keepassjava2 library
/// (KeePassJava2-dom 2.2.4 + Bouncy Castle 1.85) on the host JVM, writing the
/// exact fields the passwords app writes. Proving the Rust importer reads it is
/// what guarantees existing user `.kdbx` files keep opening after the swap.
/// Regenerate: see /tmp/kpinterop/InteropGen.java (documented in the diff).
const KEEPASSJAVA2_VAULT: &[u8] = include_bytes!("../testvectors/keepassjava2_vault.kdbx");

#[test]
fn opens_vault_written_by_old_keepassjava2_library() {
    let out = import_kdbx(PW, KEEPASSJAVA2_VAULT).expect("must open keepassjava2-authored vault");
    let parsed = entries_of(&out);
    assert_eq!(parsed.len(), 2);

    let gh = find_entry(&parsed, "GitHub");
    assert_eq!(gh["UserName"], "octocat");
    assert_eq!(gh["Password"], "s3cr3t!");
    assert_eq!(gh["URL"], "https://github.com");
    assert_eq!(gh["Websites"], "https://github.com\nhttps://gist.github.com");
    assert_eq!(gh["otp"], "otpauth://totp/?secret=JBSWY3DPEHPK3PXP");
    assert_eq!(gh["_Type"], "password");

    let pk = find_entry(&parsed, "Acme Passkey");
    assert_eq!(pk["_Type"], "passkey");
    assert_eq!(pk["KPEX_PASSKEY_USERNAME"], "alice");
    assert_eq!(pk["KPEX_PASSKEY_PRIVATE_KEY_PEM"], "TUlHSEFnRUE=");
    assert_eq!(pk["KPEX_PASSKEY_CREDENTIAL_ID"], "cred-123");
    assert_eq!(pk["KPEX_PASSKEY_USER_HANDLE"], "user-abc");
    assert_eq!(pk["KPEX_PASSKEY_RELYING_PARTY"], "acme.example");
}
