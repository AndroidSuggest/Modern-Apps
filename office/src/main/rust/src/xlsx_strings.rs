//! The xlsx shared-string table.
//!
//! Port of `OoxmlXlsx.parseSharedStrings`. Every text cell in a workbook stores an index into
//! `xl/sharedStrings.xml` rather than its own text, so this table is read once and indexed by
//! every sheet — which makes it both hot and a hard prerequisite for the worksheet parser.
//!
//! A `<si>` entry is either a bare `<t>` or a sequence of `<r>` runs, each with its own `<t>`,
//! which concatenate. The one subtlety is `<rPh>`: phonetic guides (Japanese furigana) carry
//! their own `<t>` elements that are *not* part of the cell's text. Including them silently
//! duplicates readings into every affected string.

use crate::xml::{Event, XmlParser};

/// Reads `xl/sharedStrings.xml` into the indexed table cells refer to.
pub fn parse_shared_strings(xml: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut p = XmlParser::new(xml);

    loop {
        match p.next() {
            Event::EndDocument => break,
            Event::StartTag if p.name() == "si" => out.push(read_shared_string(&mut p)),
            _ => {}
        }
    }
    out
}

/// Reads one `<si>`, with the parser positioned on its start tag.
fn read_shared_string(p: &mut XmlParser<'_>) -> String {
    let depth = p.depth();
    let mut text = String::new();
    // Depth of an enclosing <rPh>, or -1 when not inside one.
    let mut phonetic_depth = -1i32;

    let mut event = p.next();
    while !(event == Event::EndTag && p.depth() == depth && p.name() == "si") {
        if event == Event::EndDocument {
            break;
        }
        match event {
            Event::StartTag if p.name() == "rPh" => phonetic_depth = p.depth(),
            Event::EndTag if p.name() == "rPh" => phonetic_depth = -1,
            Event::StartTag if p.name() == "t" && phonetic_depth < 0 => {
                let run = p.read_element_text("t");
                text.push_str(&run);
            }
            _ => {}
        }
        event = p.next();
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_strings() {
        let xml = r#"<sst count="2"><si><t>Hello</t></si><si><t>World</t></si></sst>"#;
        assert_eq!(parse_shared_strings(xml), vec!["Hello", "World"]);
    }

    #[test]
    fn rich_text_runs_concatenate() {
        // Formatting splits one logical string across runs; the cell shows them joined.
        let xml = r#"<sst><si>
            <r><rPr><b/></rPr><t>Bold</t></r>
            <r><t> and </t></r>
            <r><rPr><i/></rPr><t>italic</t></r>
        </si></sst>"#;
        assert_eq!(parse_shared_strings(xml), vec!["Bold and italic"]);
    }

    #[test]
    fn phonetic_guides_are_excluded() {
        // <rPh> holds furigana for the base text; it must not appear in the cell.
        let xml = r#"<sst><si>
            <t>東京</t>
            <rPh sb="0" eb="2"><t>とうきょう</t></rPh>
            <phoneticPr fontId="1"/>
        </si></sst>"#;
        assert_eq!(parse_shared_strings(xml), vec!["東京"], "furigana is not cell text");
    }

    #[test]
    fn phonetic_guides_between_runs_do_not_suppress_later_text() {
        // Getting the rPh depth tracking wrong here swallows every run after the first guide.
        let xml = r#"<sst><si>
            <r><t>A</t></r>
            <rPh><t>skip</t></rPh>
            <r><t>B</t></r>
        </si></sst>"#;
        assert_eq!(parse_shared_strings(xml), vec!["AB"]);
    }

    #[test]
    fn whitespace_is_preserved() {
        // xml:space="preserve" cells are how spreadsheets store padded labels.
        let xml = r#"<sst><si><t xml:space="preserve">  padded  </t></si></sst>"#;
        assert_eq!(parse_shared_strings(xml), vec!["  padded  "]);
    }

    #[test]
    fn entities_are_decoded() {
        let xml = r#"<sst><si><t>a &amp; b &lt; c</t></si></sst>"#;
        assert_eq!(parse_shared_strings(xml), vec!["a & b < c"]);
    }

    #[test]
    fn empty_entries_keep_their_index() {
        // Indices are positional: dropping an empty <si> shifts every later cell's text.
        let xml = r#"<sst><si><t></t></si><si><t>second</t></si><si/></sst>"#;
        assert_eq!(parse_shared_strings(xml), vec!["", "second", ""]);
    }

    #[test]
    fn missing_or_malformed_tables_yield_no_strings() {
        assert!(parse_shared_strings("").is_empty());
        assert!(parse_shared_strings("<sst/>").is_empty());
        assert!(parse_shared_strings("<sst><si><t>x").len() <= 1, "truncated input must terminate");
    }
}
