static TIMES_BOLD_ITALIC: &[(&str, u16)] = &[
    ("Euro", 500),
    ("space", 250), ("exclam", 389), ("quotedbl", 555), ("numbersign", 500),
    ("dollar", 500), ("percent", 833), ("ampersand", 778), ("quotesingle", 278),
    ("quoteright", 333), ("parenleft", 333), ("parenright", 333), ("asterisk", 500),
    ("plus", 570), ("comma", 250), ("hyphen", 333), ("period", 250), ("slash", 278),
    ("zero", 500), ("one", 500), ("two", 500), ("three", 500), ("four", 500),
    ("five", 500), ("six", 500), ("seven", 500), ("eight", 500), ("nine", 500),
    ("colon", 333), ("semicolon", 333), ("less", 570), ("equal", 570),
    ("greater", 570), ("question", 500), ("at", 832),
    ("A", 667), ("B", 667), ("C", 667), ("D", 722), ("E", 667), ("F", 667),
    ("G", 722), ("H", 778), ("I", 389), ("J", 500), ("K", 667), ("L", 611),
    ("M", 889), ("N", 722), ("O", 722), ("P", 611), ("Q", 722), ("R", 667),
    ("S", 556), ("T", 611), ("U", 722), ("V", 667), ("W", 889), ("X", 667),
    ("Y", 611), ("Z", 611),
    ("bracketleft", 333), ("backslash", 278), ("bracketright", 333),
    ("asciicircum", 570), ("underscore", 500), ("grave", 333), ("quoteleft", 333),
    ("a", 500), ("b", 500), ("c", 444), ("d", 500), ("e", 444), ("f", 333),
    ("g", 500), ("h", 556), ("i", 278), ("j", 278), ("k", 500), ("l", 278),
    ("m", 778), ("n", 556), ("o", 500), ("p", 500), ("q", 500), ("r", 389),
    ("s", 389), ("t", 278), ("u", 556), ("v", 444), ("w", 667), ("x", 500),
    ("y", 444), ("z", 389),
    ("braceleft", 348), ("bar", 220), ("braceright", 348), ("asciitilde", 570),
    ("exclamdown", 389), ("cent", 500), ("sterling", 500), ("fraction", 167),
    ("yen", 500), ("florin", 500), ("section", 500), ("currency", 500),
    ("quotedblleft", 500), ("guillemotleft", 500), ("guilsinglleft", 333),
    ("guilsinglright", 333), ("fi", 556), ("fl", 556), ("endash", 500),
    ("dagger", 500), ("daggerdbl", 500), ("periodcentered", 250), ("paragraph", 500),
    ("bullet", 350), ("quotesinglbase", 333), ("quotedblbase", 500),
    ("quotedblright", 500), ("guillemotright", 500), ("ellipsis", 1000),
    ("perthousand", 1000), ("questiondown", 500), ("acute", 333), ("circumflex", 333),
    ("tilde", 333), ("macron", 333), ("breve", 333), ("dotaccent", 333),
    ("dieresis", 333), ("ring", 333), ("cedilla", 333), ("hungarumlaut", 333),
    ("ogonek", 333), ("caron", 333), ("emdash", 1000), ("AE", 944),
    ("ordfeminine", 266), ("Lslash", 611), ("Oslash", 722), ("OE", 944),
    ("ordmasculine", 300), ("ae", 722), ("dotlessi", 278), ("lslash", 278),
    ("oslash", 500), ("oe", 722), ("germandbls", 500),
    ("Aacute", 667), ("Acircumflex", 667), ("Adieresis", 667), ("Agrave", 667),
    ("Aring", 667), ("Atilde", 667), ("Ccedilla", 667), ("Eacute", 667),
    ("Ecircumflex", 667), ("Edieresis", 667), ("Egrave", 667), ("Iacute", 389),
    ("Icircumflex", 389), ("Idieresis", 389), ("Igrave", 389), ("Ntilde", 722),
    ("Oacute", 722), ("Ocircumflex", 722), ("Odieresis", 722), ("Ograve", 722),
    ("Otilde", 722), ("Scaron", 556), ("Uacute", 722), ("Ucircumflex", 722),
    ("Udieresis", 722), ("Ugrave", 722), ("Yacute", 611), ("Ydieresis", 611),
    ("Zcaron", 611), ("Thorn", 611), ("Eth", 722),
    ("aacute", 500), ("acircumflex", 500), ("adieresis", 500), ("agrave", 500),
    ("aring", 500), ("atilde", 500), ("ccedilla", 444), ("eacute", 444),
    ("ecircumflex", 444), ("edieresis", 444), ("egrave", 444), ("iacute", 278),
    ("icircumflex", 278), ("idieresis", 278), ("igrave", 278), ("ntilde", 556),
    ("oacute", 500), ("ocircumflex", 500), ("odieresis", 500), ("ograve", 500),
    ("otilde", 500), ("scaron", 389), ("uacute", 556), ("ucircumflex", 556),
    ("udieresis", 556), ("ugrave", 556), ("yacute", 444), ("ydieresis", 444),
    ("zcaron", 389), ("thorn", 500), ("eth", 500), ("mu", 576), ("degree", 400),
    ("plusminus", 570), ("twosuperior", 300), ("threesuperior", 300),
    ("onesuperior", 300), ("onehalf", 750), ("onequarter", 750),
    ("threequarters", 750), ("multiply", 570), ("divide", 570), ("brokenbar", 220),
    ("logicalnot", 606), ("registered", 747), ("copyright", 747),
    ("trademark", 1000), ("minus", 570),
];

// Symbol uses its own encoding; widths are keyed by the AFM glyph names.
//
// KNOWN GAP: this stops at code 0x7E ("similar"). Every Symbol code >= 0xA0 —
// the math operators, arrows, card suits and the bracket/integral build-up
// pieces — has no width here and falls to `default_width` (0.5 em) in
// `fonts.rs`. The real values are far from uniform (build-up pieces ~274-384,
// several operators 700+), so a line of Symbol math drifts both ways. Left
// unfilled rather than guessed: these numbers cannot be verified against
// Adobe's Symbol.afm from this tree, and a plausible-but-wrong advance for
// every math glyph is worse than one uniform one. Needs `/Widths` to be absent
// entirely to matter at all, which is legacy TeX/dvips-era files. The Greek
// alphabet is unaffected — Symbol puts Alpha..Omega and alpha..omega in
// 0x41-0x7A, inside the covered range.
//
// BEFORE ADDING ROWS, read this. `fonts.rs`'s code -> name chain ends in a
// last-resort guess against `type1::STANDARD_ENCODING`, and that step runs for
// every face, Symbol included, even though the two encodings are unrelated.
// Today it is harmless precisely BECAUSE this table stops at 0x7E: above it the
// Standard-guessed name ("section" for 0xA7, where Symbol has `club`) finds no
// row, so the lookup falls through to the code -> Unicode -> width path that
// does know about Symbol. Add a row whose name StandardEncoding also uses at a
// DIFFERENT code and the guess starts winning, silently charging that glyph the
// other encoding's width. The two encodings share exactly four names above
// 0xA0 — `fraction` (164), `florin` (166), `bullet` (183) and `ellipsis` (188)
// — and all four sit at the SAME code in both, so those are safe. Any name
// outside that set must be checked against `STANDARD_ENCODING` first.
//
// That check is ENFORCED, not just documented: `fonts.rs`'s
// `blind_reaudit_r5_width_tests::the_standard_encoding_guess_never_contradicts_symbols_own_encoding`
// walks every `STANDARD_ENCODING` entry and asserts that wherever the Standard
// guess and Symbol's own encoding both produce a width for a code, they agree.
// A colliding row added here fails that test with the code and both widths.
static SYMBOL: &[(&str, u16)] = &[
    ("space", 250), ("exclam", 333), ("universal", 713), ("numbersign", 500),
    ("existential", 549), ("percent", 833), ("ampersand", 778), ("suchthat", 439),
    ("parenleft", 333), ("parenright", 333), ("asteriskmath", 500), ("plus", 549),
    ("comma", 250), ("minus", 549), ("period", 250), ("slash", 278),
    ("zero", 500), ("one", 500), ("two", 500), ("three", 500), ("four", 500),
    ("five", 500), ("six", 500), ("seven", 500), ("eight", 500), ("nine", 500),
    ("colon", 278), ("semicolon", 278), ("less", 549), ("equal", 549),
    ("greater", 549), ("question", 444), ("congruent", 549),
    ("Alpha", 722), ("Beta", 667), ("Chi", 722), ("Delta", 612), ("Epsilon", 611),
    ("Phi", 763), ("Gamma", 603), ("Eta", 722), ("Iota", 333), ("theta1", 631),
    ("Kappa", 722), ("Lambda", 686), ("Mu", 889), ("Nu", 722), ("Omicron", 722),
    ("Pi", 768), ("Theta", 741), ("Rho", 556), ("Sigma", 592), ("Tau", 611),
    ("Upsilon", 690), ("sigma1", 439), ("Omega", 768), ("Xi", 645), ("Psi", 795),
    ("Zeta", 611), ("bracketleft", 333), ("therefore", 863), ("bracketright", 333),
    ("perpendicular", 658), ("underscore", 500), ("radicalex", 500),
    ("alpha", 631), ("beta", 549), ("chi", 549), ("delta", 494), ("epsilon", 439),
    ("phi", 521), ("gamma", 411), ("eta", 603), ("iota", 329), ("phi1", 603),
    ("kappa", 549), ("lambda", 549), ("mu", 576), ("nu", 521), ("omicron", 549),
    ("pi", 549), ("theta", 521), ("rho", 549), ("sigma", 603), ("tau", 439),
    ("upsilon", 576), ("omega1", 713), ("omega", 686), ("xi", 493), ("psi", 686),
    ("zeta", 494), ("braceleft", 480), ("bar", 200), ("braceright", 480),
    ("similar", 549),
];

// ZapfDingbats decorative face; widths keyed by the AFM `aNNN` glyph names.
static ZAPF_DINGBATS: &[(&str, u16)] = &[
    ("space", 278),
    ("a1", 974), ("a2", 961), ("a202", 974), ("a3", 980), ("a4", 719),
    ("a5", 789), ("a119", 790), ("a118", 791), ("a117", 690), ("a11", 960),
    ("a12", 939), ("a13", 549), ("a14", 855), ("a15", 911), ("a16", 933),
    ("a105", 911), ("a17", 945), ("a18", 974), ("a19", 755), ("a20", 846),
    ("a21", 762), ("a22", 761), ("a23", 571), ("a24", 677), ("a25", 763),
    ("a26", 760), ("a27", 759), ("a28", 754), ("a6", 494), ("a7", 552),
    ("a8", 537), ("a9", 577), ("a10", 692), ("a29", 786), ("a30", 788),
    ("a31", 788), ("a32", 790), ("a33", 793), ("a34", 794), ("a35", 816),
    ("a36", 823), ("a37", 789), ("a38", 841), ("a39", 823), ("a40", 833),
    ("a41", 816), ("a42", 831), ("a43", 923), ("a44", 744), ("a45", 723),
    ("a46", 749), ("a47", 790), ("a48", 792), ("a49", 695), ("a50", 776),
    ("a51", 768), ("a52", 792), ("a53", 759), ("a54", 707), ("a55", 708),
    ("a56", 682), ("a57", 701), ("a58", 826), ("a59", 815), ("a60", 789),
    ("a61", 789), ("a62", 707), ("a63", 687), ("a64", 696), ("a65", 689),
    ("a66", 786), ("a67", 787), ("a68", 713), ("a69", 791), ("a70", 785),
    ("a71", 791), ("a72", 873), ("a73", 761), ("a74", 762), ("a203", 762),
    ("a75", 759), ("a204", 759), ("a76", 892), ("a77", 892), ("a78", 788),
    ("a79", 784), ("a81", 438), ("a82", 138), ("a83", 277), ("a84", 415),
    ("a97", 392), ("a98", 392), ("a99", 668), ("a100", 668), ("a89", 390),
    ("a90", 390), ("a93", 317), ("a94", 317), ("a91", 276), ("a92", 276),
    ("a205", 509), ("a85", 509), ("a206", 410), ("a86", 410), ("a87", 234),
    ("a88", 234), ("a95", 334), ("a96", 334), ("a101", 732), ("a102", 544),
    ("a103", 544), ("a104", 910), ("a106", 667), ("a107", 760), ("a108", 760),
    ("a112", 776), ("a111", 595), ("a110", 694), ("a109", 626), ("a120", 788),
    ("a121", 788), ("a122", 788), ("a123", 788), ("a124", 788), ("a125", 788),
    ("a126", 788), ("a127", 788), ("a128", 788), ("a129", 788), ("a130", 788),
    ("a131", 788), ("a132", 788), ("a133", 788), ("a134", 788), ("a135", 788),
    ("a136", 788), ("a137", 788), ("a138", 788), ("a139", 788), ("a140", 788),
    ("a141", 788), ("a142", 788), ("a143", 788), ("a144", 788), ("a145", 788),
    ("a146", 788), ("a147", 788), ("a148", 788), ("a149", 788), ("a150", 788),
    ("a151", 788), ("a152", 788), ("a153", 788), ("a154", 788), ("a155", 788),
    ("a156", 788), ("a157", 788), ("a158", 788), ("a159", 788), ("a160", 894),
    ("a161", 838), ("a163", 1016), ("a164", 458), ("a196", 748), ("a165", 924),
    ("a192", 748), ("a166", 918), ("a167", 927), ("a168", 928), ("a169", 928),
    ("a170", 834), ("a171", 873), ("a172", 828), ("a173", 924), ("a162", 924),
    ("a174", 917), ("a175", 930), ("a176", 931), ("a177", 463), ("a178", 883),
    ("a179", 836), ("a193", 836), ("a180", 867), ("a199", 867), ("a181", 696),
    ("a200", 696), ("a182", 874), ("a201", 760), ("a183", 946), ("a184", 771),
    ("a197", 865), ("a185", 771), ("a194", 888), ("a198", 967), ("a186", 888),
    ("a195", 831), ("a187", 873), ("a188", 927), ("a189", 970), ("a190", 918),
    ("a191", 748),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn helvetica_metrics() {
        let m = standard_14_widths("Helvetica").unwrap();
        assert_eq!(m["space"], 0.278);
        assert_eq!(m["W"], 0.944);
    }

    #[test]
    fn times_metrics() {
        let m = standard_14_widths("Times-Roman").unwrap();
        assert_eq!(m["space"], 0.25);
    }

    #[test]
    fn courier_is_monospaced() {
        let m = standard_14_widths("Courier").unwrap();
        assert_eq!(m["m"], 0.6);
        assert_eq!(m["i"], 0.6);
    }

    #[test]
    fn tolerates_subset_prefix_and_aliases() {
        assert!(standard_14_widths("ABCDEF+Helvetica-BoldOblique").is_some());
        assert!(standard_14_widths("Arial").is_some());
        assert!(standard_14_widths("Arial-BoldMT").is_some());
        assert!(standard_14_widths("TimesNewRoman").is_some());
        assert!(standard_14_widths("Symbol").is_some());
        assert!(standard_14_widths("ZapfDingbats").is_some());
        assert!(standard_14_widths("SomeRandomFont").is_none());
    }

    #[test]
    fn foundry_and_family_names_do_not_hijack_a_face() {
        // "Monotype Corsiva" is a script face; it contains "mono" but is not
        // monospaced, and Courier's flat 600 would mis-space every glyph.
        let corsiva = standard_14_widths("MonotypeCorsiva");
        assert!(corsiva.is_none() || corsiva.as_ref().unwrap()["i"] != 0.6);
        // "sans-serif" contains "serif"; the serif test runs first, so without the
        // exclusion a sans face was given Times metrics.
        let sans = standard_14_widths("Some-Sans-Serif").expect("resolves to Helvetica");
        assert_eq!(sans["space"], 0.278, "sans-serif must not take Times' 250");
        // A real serif name still resolves to Times.
        assert_eq!(standard_14_widths("NimbusSerif").unwrap()["space"], 0.25);
    }
}
