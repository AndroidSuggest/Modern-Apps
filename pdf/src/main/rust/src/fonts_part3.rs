/// Minimal TrueType `cmap` parser: recovers a character-code → Unicode map by
/// composing a code→glyph subtable (Mac 1,0 or Symbol 3,0) with the reverse of
/// a Unicode subtable (3,1 / 0,3 / 3,10). All reads are bounds-checked so
/// malformed font data can never panic.