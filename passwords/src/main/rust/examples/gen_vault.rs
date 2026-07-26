// Dev-only helper: write a KDBX vault with the Rust exporter so it can be
// re-opened by the old keepassjava2 library (reverse-direction interop check).
// Not shipped; excluded from the cdylib. Run: cargo run --example gen_vault -- <pw> <out>
use std::io::Write;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pw = &args[1];
    let out = &args[2];
    let entries = r#"[
        {"Title":"GitHub","UserName":"octocat","Password":"s3cr3t!","URL":"https://github.com","Websites":"https://github.com\nhttps://gist.github.com","otp":"otpauth://totp/?secret=JBSWY3DPEHPK3PXP","_Type":"password"},
        {"Title":"Acme Passkey","UserName":"alice","URL":"acme.example","_Type":"passkey","KPEX_PASSKEY_USERNAME":"alice","KPEX_PASSKEY_PRIVATE_KEY_PEM":"TUlHSEFnRUE=","KPEX_PASSKEY_CREDENTIAL_ID":"cred-123","KPEX_PASSKEY_USER_HANDLE":"user-abc","KPEX_PASSKEY_RELYING_PARTY":"acme.example"}
    ]"#;
    let bytes = passwords_kdbx::export_kdbx(pw, entries).expect("export");
    std::fs::File::create(out).unwrap().write_all(&bytes).unwrap();
    println!("wrote {} ({} bytes)", out, bytes.len());
}
