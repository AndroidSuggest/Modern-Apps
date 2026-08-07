# Highlight queries

`libcode_ts` reuses each grammar crate's **bundled** `highlights` query (exposed as a
`HIGHLIGHTS_QUERY` / `HIGHLIGHT_QUERY` constant), so there are no checked-in `.scm` files here — the
queries travel with the pinned grammar crates in `../Cargo.toml` (and are frozen by `Cargo.lock`).

If a grammar you add does not ship a highlights query, drop a `<lang>.scm` in this directory and
`include_str!` it from `src/lib.rs` instead of the crate constant. Capture names are mapped to the
editor's colour kinds by `kind_for` in `src/lib.rs`.
