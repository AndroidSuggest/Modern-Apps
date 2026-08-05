//! Host-side round-trip + interop tests for the KDBX read/write core.
//!
//! `cargo test` on the host proves the pure-Rust logic (no JNI) is correct
//! before it is ever cross-compiled for Android. No serde_json macro – now std-only JSON.

use super::*;
use keepass::db::{fields, Database};
use keepass::DatabaseKey;

const PW: &str = "correct horse battery staple";

fn entries_of(json_str: &str) -> Vec<EntryMap> {
    from_json_std(json_str).expect("parse json")
}

fn find_entry<'a>(entries: &'a [EntryMap], title: &str) -> &'a EntryMap {
    entries
        .iter()
        .find(|e| e.get("Title").map(|s| s.as_str()) == Some(title))
        .unwrap_or_else(|| panic!("no entry titled {title}"))
}

fn json_array_of(maps: &[EntryMap]) -> String {
    to_json_std(maps).expect("to json")
}

#[test]
fn export_then_import_roundtrips_all_fields() {
    let mut m1 = EntryMap::new();
    m1.insert("Title".into(), "GitHub".into());
    m1.insert("UserName".into(), "octocat".into());
    m1.insert("Password".into(), "s3cr3t!".into());
    m1.insert("URL".into(), "https://github.com".into());
    m1.insert("Websites".into(), "https://github.com\nhttps://gist.github.com".into());
    m1.insert("otp".into(), "otpauth://totp/?secret=JBSWY3DPEHPK3PXP".into());
    m1.insert("_Type".into(), "password".into());
    let mut m2 = EntryMap::new();
    m2.insert("Title".into(), "Acme Passkey".into());
    m2.insert("UserName".into(), "alice".into());
    m2.insert("URL".into(), "acme.example".into());
    m2.insert("_Type".into(), "passkey".into());
    m2.insert("KPEX_PASSKEY_USERNAME".into(), "alice".into());
    m2.insert("KPEX_PASSKEY_PRIVATE_KEY_PEM".into(), "TUlHSEFnRUE=".into());
    m2.insert("KPEX_PASSKEY_CREDENTIAL_ID".into(), "cred-123".into());
    m2.insert("KPEX_PASSKEY_USER_HANDLE".into(), "user-abc".into());
    m2.insert("KPEX_PASSKEY_RELYING_PARTY".into(), "acme.example".into());
    let json_str = json_array_of(&[m1, m2]);

    let bytes = export_kdbx(PW, &json_str).expect("export");
    let out = import_kdbx(PW, &bytes).expect("import");
    let parsed = entries_of(&out);

    assert_eq!(parsed.len(), 2, "entry count preserved");

    let gh = find_entry(&parsed, "GitHub");
    assert_eq!(gh.get("UserName").map(|s| s.as_str()), Some("octocat"));
    assert_eq!(gh.get("Password").map(|s| s.as_str()), Some("s3cr3t!"));
    assert_eq!(gh.get("URL").map(|s| s.as_str()), Some("https://github.com"));
    assert_eq!(gh.get("Websites").map(|s| s.as_str()), Some("https://github.com\nhttps://gist.github.com"));
    assert_eq!(gh.get("otp").map(|s| s.as_str()), Some("otpauth://totp/?secret=JBSWY3DPEHPK3PXP"));
    assert_eq!(gh.get("_Type").map(|s| s.as_str()), Some("password"));

    let pk = find_entry(&parsed, "Acme Passkey");
    assert_eq!(pk.get("KPEX_PASSKEY_PRIVATE_KEY_PEM").map(|s| s.as_str()), Some("TUlHSEFnRUE="));
    assert_eq!(pk.get("KPEX_PASSKEY_CREDENTIAL_ID").map(|s| s.as_str()), Some("cred-123"));
    assert_eq!(pk.get("KPEX_PASSKEY_RELYING_PARTY").map(|s| s.as_str()), Some("acme.example"));
}

#[test]
fn sync_metadata_fields_survive_roundtrip() {
    let mut m = EntryMap::new();
    m.insert("Title".into(), "Synced".into());
    m.insert("Password".into(), "p".into());
    m.insert("_Type".into(), "password".into());
    m.insert("_SyncId".into(), "0123456789abcdef0123456789abcdef".into());
    m.insert("_Modified".into(), "1785954985000".into());
    let json_str = json_array_of(&[m]);

    let bytes = export_kdbx(PW, &json_str).expect("export");
    let parsed = entries_of(&import_kdbx(PW, &bytes).expect("import"));

    let e = find_entry(&parsed, "Synced");
    assert_eq!(
        e.get("_SyncId").map(|s| s.as_str()),
        Some("0123456789abcdef0123456789abcdef"),
    );
    assert_eq!(e.get("_Modified").map(|s| s.as_str()), Some("1785954985000"));
}

#[test]
fn password_field_is_stored_protected() {
    let mut m = EntryMap::new();
    m.insert("Title".into(), "x".into());
    m.insert("Password".into(), "hunter2".into());
    m.insert("UserName".into(), "u".into());
    let json_str = json_array_of(&[m]);
    let bytes = export_kdbx(PW, &json_str).unwrap();

    let db = Database::parse(&bytes, DatabaseKey::new().with_password(PW)).unwrap();
    let root = db.root();
    let entry = root.entries().next().unwrap();
    assert!(entry.fields[fields::PASSWORD].is_protected());
    assert!(!entry.fields[fields::USERNAME].is_protected());
    assert_eq!(entry.get(fields::PASSWORD), Some("hunter2"));
}

#[test]
fn wrong_password_fails_to_import() {
    let mut m = EntryMap::new();
    m.insert("Title".into(), "x".into());
    m.insert("Password".into(), "p".into());
    let json_str = json_array_of(&[m]);
    let bytes = export_kdbx(PW, &json_str).unwrap();
    assert!(import_kdbx("not the password", &bytes).is_none());
}

#[test]
fn entries_in_nested_groups_are_flattened() {
    let mut db = Database::new();
    db.root_mut().add_entry().set_unprotected(fields::TITLE, "TopLevel");
    let sub_id = db.root_mut().add_group().edit(|g| g.name = "Sub".into()).id();
    db.group_mut(sub_id).unwrap().add_entry().set_unprotected(fields::TITLE, "Nested");

    let mut bytes = Vec::new();
    db.save(&mut bytes, DatabaseKey::new().with_password(PW)).unwrap();

    let out = import_kdbx(PW, &bytes).expect("import");
    let parsed = entries_of(&out);
    let titles: Vec<String> = parsed.iter().filter_map(|e| e.get("Title").cloned()).collect();
    assert!(titles.contains(&"TopLevel".to_string()));
    assert!(titles.contains(&"Nested".to_string()));
}

#[test]
fn malformed_input_returns_none() {
    assert!(import_kdbx(PW, b"not a kdbx file at all").is_none());
    assert!(export_kdbx(PW, "{ not json ]").is_none());
}

const KEEPASSJAVA2_VAULT: &[u8] = include_bytes!("../testvectors/keepassjava2_vault.kdbx");

#[test]
fn opens_vault_written_by_old_keepassjava2_library() {
    let out = import_kdbx(PW, KEEPASSJAVA2_VAULT).expect("must open keepassjava2-authored vault");
    let parsed = entries_of(&out);
    assert_eq!(parsed.len(), 2);

    let gh = find_entry(&parsed, "GitHub");
    assert_eq!(gh.get("UserName").map(|s| s.as_str()), Some("octocat"));
    assert_eq!(gh.get("Password").map(|s| s.as_str()), Some("s3cr3t!"));
    assert_eq!(gh.get("URL").map(|s| s.as_str()), Some("https://github.com"));
    assert_eq!(gh.get("Websites").map(|s| s.as_str()), Some("https://github.com\nhttps://gist.github.com"));
    assert_eq!(gh.get("otp").map(|s| s.as_str()), Some("otpauth://totp/?secret=JBSWY3DPEHPK3PXP"));
    assert_eq!(gh.get("_Type").map(|s| s.as_str()), Some("password"));

    let pk = find_entry(&parsed, "Acme Passkey");
    assert_eq!(pk.get("_Type").map(|s| s.as_str()), Some("passkey"));
    assert_eq!(pk.get("KPEX_PASSKEY_USERNAME").map(|s| s.as_str()), Some("alice"));
    assert_eq!(pk.get("KPEX_PASSKEY_PRIVATE_KEY_PEM").map(|s| s.as_str()), Some("TUlHSEFnRUE="));
    assert_eq!(pk.get("KPEX_PASSKEY_CREDENTIAL_ID").map(|s| s.as_str()), Some("cred-123"));
    assert_eq!(pk.get("KPEX_PASSKEY_USER_HANDLE").map(|s| s.as_str()), Some("user-abc"));
    assert_eq!(pk.get("KPEX_PASSKEY_RELYING_PARTY").map(|s| s.as_str()), Some("acme.example"));
}

#[test]
fn std_json_escape_handles_newlines() {
    let mut m = EntryMap::new();
    m.insert("Websites".into(), "a\nb".into());
    let js = json_array_of(&[m]);
    assert!(js.contains("\\n"));
    let parsed = entries_of(&js);
    assert_eq!(parsed[0]["Websites"], "a\nb");
}
