    /// same defaults.
    #[test]
    fn a_widget_resolved_by_dictionary_still_gets_the_acroform_defaults() {
        let w = dictionary! {
            "FT" => name_obj("Tx"),
            "V" => Object::string_literal("Ab"),
        };
        let doc = form_doc(dictionary! {
            "DA" => Object::string_literal("/Helv 9 Tf 1 0 0 rg"),
            "Q" => 2,
        });
        let c = appearance_of(&doc, &w);
        assert!(c.contains("9.000 Tf"), "AcroForm /DA size: {c}");
        assert!(c.contains("1.000 0.000 0.000 rg"), "AcroForm /DA colour: {c}");
        // Right-aligned: "Ab" at ~4.5pt per glyph sits near the right edge.
        assert!(c.contains("1 0 0 1 89.00"), "AcroForm /Q right alignment: {c}");
    }
}

// ---------------------------------------------------------------------------
// Full-text search
// ---------------------------------------------------------------------------
