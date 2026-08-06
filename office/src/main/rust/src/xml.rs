//! Namespace-aware XML pull parser for the OOXML/ODF importers.
//!
//! Port of `OoxmlXml.kt`. Deliberately mirrors the `XmlPullParser` surface those importers were
//! written against — `next()`, `depth()`, `name()`, `attr()` — because the ~5.5k lines of parser
//! logic still to move all navigate by comparing `parser.depth` against a saved depth. Matching
//! the model keeps those ports mechanical instead of a redesign.
//!
//! Two behaviours are load-bearing and easy to get wrong:
//!
//! * **Depth.** A `StartTag` reports the depth *including* itself (root is 1) and the matching
//!   `EndTag` reports the same number, which is what makes `depth == saved && name == tag`
//!   terminate a subtree correctly.
//! * **Empty elements.** `<a/>` must surface as `StartTag` then `EndTag`, as XmlPullParser does.
//!   quick-xml reports one `Empty` event; collapsing them would strand every `skip_element` and
//!   `for_each_child` loop that expects a close.
//!
//! Elements and attributes are matched on *local* name: OOXML mixes the w/a/r/wp namespaces
//! freely and the importers only ever care about the local part. [`XmlParser::attr_ns`] is there
//! for the few genuinely ambiguous cases (`r:id` vs `w:id`).

use quick_xml::events::attributes::Attribute;
use quick_xml::events::Event as QEvent;
use quick_xml::name::ResolveResult;
use quick_xml::NsReader;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Event {
    StartTag,
    EndTag,
    Text,
    EndDocument,
}

struct Attr {
    local: String,
    namespace: Option<String>,
    value: String,
}

pub struct XmlParser<'a> {
    reader: NsReader<&'a [u8]>,
    buf: Vec<u8>,
    depth: i32,
    event: Event,
    name: String,
    text: String,
    attrs: Vec<Attr>,
    /// Set when an `Empty` element has produced its `StartTag` and still owes an `EndTag`.
    pending_end: Option<String>,
    /// An `EndTag` reports its own element's depth, so the decrement is deferred to the next
    /// event. Without this, `depth()` after an `EndTag` would report the parent's depth and every
    /// `depth == saved && name == tag` subtree check would fail to terminate.
    pending_depth_decrement: bool,
}

impl<'a> XmlParser<'a> {
    pub fn new(xml: &'a str) -> Self {
        let mut reader = NsReader::from_reader(xml.as_bytes());
        // OOXML in the wild is not always strictly closed; be forgiving like XmlPullParser.
        reader.config_mut().check_end_names = false;
        reader.config_mut().trim_text(false);
        Self {
            reader,
            buf: Vec::new(),
            depth: 0,
            event: Event::StartTag,
            name: String::new(),
            text: String::new(),
            attrs: Vec::new(),
            pending_end: None,
            pending_depth_decrement: false,
        }
    }

    pub fn event_type(&self) -> Event {
        self.event
    }

    /// Local name of the current element. Empty for text events.
    pub fn name(&self) -> &str {
        &self.name
    }

    /// Depth of the current element; see the module docs.
    pub fn depth(&self) -> i32 {
        self.depth
    }

    /// Character data of the current `Text` event, entities already decoded.
    pub fn text(&self) -> &str {
        &self.text
    }

    /// Value of the attribute with this local name, ignoring namespace.
    pub fn attr(&self, local_name: &str) -> Option<&str> {
        self.attrs.iter().find(|a| a.local == local_name).map(|a| a.value.as_str())
    }

    /// Value of the attribute with this local name *and* namespace URI, for cases like `r:id`
    /// versus `w:id` where the local name alone is ambiguous.
    pub fn attr_ns(&self, ns: &str, local_name: &str) -> Option<&str> {
        self.attrs
            .iter()
            .find(|a| a.local == local_name && a.namespace.as_deref() == Some(ns))
            .map(|a| a.value.as_str())
    }

    pub fn next_event(&mut self) -> Event {
        if self.pending_depth_decrement {
            self.depth -= 1;
            self.pending_depth_decrement = false;
        }

        // Finish an `<a/>` that has already reported its StartTag.
        if let Some(name) = self.pending_end.take() {
            self.name = name;
            self.attrs.clear();
            self.event = Event::EndTag;
            // Depth stays put: an empty element's EndTag matches its StartTag.
            self.pending_depth_decrement = true;
            return Event::EndTag;
        }

        self.buf.clear();
        let event = {
            let (resolved, ev) = match self.reader.read_resolved_event_into(&mut self.buf) {
                Ok(pair) => pair,
                // Malformed XML ends the document rather than propagating; the importers treat a
                // truncated part as "nothing more to read", which is what XmlPullParser did.
                Err(_) => {
                    self.event = Event::EndDocument;
                    return Event::EndDocument;
                }
            };
            let _ = resolved;
            ev.into_owned()
        };

        match event {
            QEvent::Start(e) => {
                self.depth += 1;
                self.name = local_name_of(e.name().as_ref());
                self.load_attrs(e.attributes());
                self.event = Event::StartTag;
            }
            QEvent::Empty(e) => {
                self.depth += 1;
                self.name = local_name_of(e.name().as_ref());
                self.load_attrs(e.attributes());
                self.pending_end = Some(self.name.clone());
                self.event = Event::StartTag;
            }
            QEvent::End(e) => {
                self.name = local_name_of(e.name().as_ref());
                self.attrs.clear();
                self.event = Event::EndTag;
                self.pending_depth_decrement = true;
            }
            QEvent::Text(e) => {
                self.text = e.unescape().map(|c| c.into_owned()).unwrap_or_default();
                self.name.clear();
                self.event = Event::Text;
            }
            QEvent::CData(e) => {
                self.text = String::from_utf8_lossy(e.as_ref()).into_owned();
                self.name.clear();
                self.event = Event::Text;
            }
            QEvent::Eof => self.event = Event::EndDocument,
            // Comments, declarations, doctypes and PIs are not content; skip to the next event.
            _ => return self.next_event(),
        }
        self.event
    }

    fn load_attrs(&mut self, attributes: quick_xml::events::attributes::Attributes<'_>) {
        self.attrs.clear();
        for attr in attributes.flatten() {
            let Attribute { key, .. } = attr;
            // xmlns declarations are not content attributes.
            if key.as_ref() == b"xmlns" || key.as_ref().starts_with(b"xmlns:") {
                continue;
            }
            let namespace = match self.reader.resolve_attribute(key) {
                (ResolveResult::Bound(ns), _) => {
                    Some(String::from_utf8_lossy(ns.as_ref()).into_owned())
                }
                _ => None,
            };
            let value = attr
                .unescape_value()
                .map(|c| c.into_owned())
                .unwrap_or_else(|_| String::from_utf8_lossy(&attr.value).into_owned());
            self.attrs.push(Attr {
                local: local_name_of(key.as_ref()),
                namespace,
                value,
            });
        }
    }

    /// Concatenated text up to the `end_tag` closing at the current depth.
    ///
    /// Must be called with the parser positioned on that element's `StartTag`.
    pub fn read_element_text(&mut self, end_tag: &str) -> String {
        let depth = self.depth;
        let mut out = String::new();
        let mut e = self.next_event();
        while !(e == Event::EndTag && self.depth == depth && self.name == end_tag) {
            if e == Event::Text {
                out.push_str(&self.text);
            }
            if e == Event::EndDocument {
                break;
            }
            e = self.next_event();
        }
        out
    }

    /// Consumes the current element and everything inside it.
    pub fn skip_element(&mut self) {
        let depth = self.depth;
        let name = self.name.clone();
        let mut e = self.next_event();
        while !(e == Event::EndTag && self.depth == depth && self.name == name) {
            if e == Event::EndDocument {
                break;
            }
            e = self.next_event();
        }
    }

    /// Visits each `StartTag` strictly inside `end_tag`, passing its local name.
    ///
    /// The callback may consume the child (via [`skip_element`](Self::skip_element) or
    /// [`read_element_text`](Self::read_element_text)); iteration resumes from wherever it left
    /// the parser, exactly as the Kotlin `forEachChild` does.
    pub fn for_each_child<F: FnMut(&mut Self, &str)>(&mut self, end_tag: &str, mut on_start: F) {
        let depth = self.depth;
        let mut e = self.next_event();
        while !(e == Event::EndTag && self.depth == depth && self.name == end_tag) {
            if e == Event::EndDocument {
                break;
            }
            if e == Event::StartTag {
                let name = self.name.clone();
                on_start(self, &name);
            }
            e = self.next_event();
        }
    }
}

fn local_name_of(qname: &[u8]) -> String {
    let name = String::from_utf8_lossy(qname);
    match name.rsplit_once(':') {
        Some((_, local)) => local.to_string(),
        None => name.into_owned(),
    }
}

/// OOXML toggle attribute: absent means on; otherwise `1`/`true`/`on`.
pub fn bool_attr(value: Option<&str>) -> bool {
    match value {
        None => true,
        Some(v) => v == "1" || v == "true" || v == "on",
    }
}

/// 0-based column from an A1 reference (`AB12` → 27).
pub fn col_index(cell_ref: &str) -> i32 {
    let mut n = 0i32;
    for c in cell_ref.chars() {
        if c.is_ascii_alphabetic() {
            n = n * 26 + (c.to_ascii_uppercase() as i32 - 'A' as i32 + 1);
        } else {
            break;
        }
    }
    (n - 1).max(0)
}

/// 0-based row from an A1 reference (`AB12` → 11), or -1 when absent.
pub fn row_index(cell_ref: &str) -> i32 {
    let digits: String = cell_ref.chars().skip_while(|c| c.is_ascii_alphabetic()).collect();
    digits.parse::<i32>().unwrap_or(0) - 1
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Drains the document into (event, name, depth) triples.
    fn trace(xml: &str) -> Vec<(Event, String, i32)> {
        let mut p = XmlParser::new(xml);
        let mut out = Vec::new();
        loop {
            let e = p.next_event();
            if e == Event::EndDocument {
                break;
            }
            if e != Event::Text || !p.text().trim().is_empty() {
                out.push((e, p.name().to_string(), p.depth()));
            }
        }
        out
    }

    #[test]
    fn depth_matches_xmlpullparser() {
        let t = trace("<a><b><c/></b></a>");
        assert_eq!(
            t,
            vec![
                (Event::StartTag, "a".into(), 1),
                (Event::StartTag, "b".into(), 2),
                (Event::StartTag, "c".into(), 3),
                (Event::EndTag, "c".into(), 3),
                (Event::EndTag, "b".into(), 2),
                (Event::EndTag, "a".into(), 1),
            ],
            "a start and its end must report the same depth"
        );
    }

    #[test]
    fn empty_elements_produce_both_events() {
        // The importers' skip/for-each loops hang without the synthetic EndTag.
        let t = trace("<r><x/></r>");
        assert_eq!(t.len(), 4);
        assert_eq!(t[1], (Event::StartTag, "x".into(), 2));
        assert_eq!(t[2], (Event::EndTag, "x".into(), 2));
    }

    #[test]
    fn local_names_ignore_prefixes() {
        let mut p = XmlParser::new(
            r#"<w:p xmlns:w="urn:w" xmlns:r="urn:r" w:id="1" r:id="rId7" val="x"/>"#,
        );
        assert_eq!(p.next_event(), Event::StartTag);
        assert_eq!(p.name(), "p");
        assert_eq!(p.attr("val"), Some("x"));
        // Local name alone is ambiguous here; namespace disambiguates.
        assert_eq!(p.attr_ns("urn:r", "id"), Some("rId7"));
        assert_eq!(p.attr_ns("urn:w", "id"), Some("1"));
        assert_eq!(p.attr_ns("urn:other", "id"), None);
    }

    #[test]
    fn xmlns_declarations_are_not_attributes() {
        let mut p = XmlParser::new(r#"<a xmlns="urn:d" xmlns:w="urn:w" real="1"/>"#);
        p.next_event();
        assert_eq!(p.attr("xmlns"), None);
        assert_eq!(p.attr("w"), None);
        assert_eq!(p.attr("real"), Some("1"));
    }

    #[test]
    fn entities_are_decoded_in_text_and_attributes() {
        let mut p = XmlParser::new(r#"<t v="a &amp; b">x &lt; y &amp;&#38; z</t>"#);
        p.next_event();
        assert_eq!(p.attr("v"), Some("a & b"));
        assert_eq!(p.read_element_text("t"), "x < y && z");
    }

    #[test]
    fn cdata_is_text() {
        let mut p = XmlParser::new("<t><![CDATA[raw <not markup>]]></t>");
        p.next_event();
        assert_eq!(p.read_element_text("t"), "raw <not markup>");
    }

    #[test]
    fn comments_and_declarations_are_skipped() {
        let t = trace("<?xml version=\"1.0\"?><!-- hi --><a><!--x--><b/></a>");
        assert_eq!(t[0], (Event::StartTag, "a".into(), 1));
        assert_eq!(t.len(), 4, "only a and b, open and close");
    }

    #[test]
    fn read_element_text_gathers_nested_runs() {
        let mut p = XmlParser::new("<si><r><t>Hello </t></r><r><t>world</t></r></si>");
        p.next_event();
        assert_eq!(p.read_element_text("si"), "Hello world");
    }

    #[test]
    fn skip_element_lands_on_the_matching_close() {
        let mut p = XmlParser::new("<root><skip><deep><skip/></deep></skip><after/></root>");
        p.next_event();
        assert_eq!(p.name(), "root");
        p.next_event();
        assert_eq!(p.name(), "skip");
        p.skip_element();
        // A nested element of the same name must not end the skip early.
        assert_eq!(p.depth(), 2);
        assert_eq!(p.next_event(), Event::StartTag);
        assert_eq!(p.name(), "after");
    }

    #[test]
    fn for_each_child_visits_direct_and_nested_starts() {
        let mut p = XmlParser::new("<row><c r=\"A1\"/><c r=\"B1\"><v>5</v></c></row>");
        p.next_event();
        let mut seen = Vec::new();
        p.for_each_child("row", |parser, name| {
            seen.push(format!("{name}:{}", parser.attr("r").unwrap_or("")));
        });
        assert_eq!(seen, vec!["c:A1", "c:B1", "v:"]);
    }

    #[test]
    fn for_each_child_lets_the_callback_consume_a_subtree() {
        let mut p = XmlParser::new("<row><c><v>5</v></c><c><v>6</v></c></row>");
        p.next_event();
        let mut values = Vec::new();
        p.for_each_child("row", |parser, name| {
            if name == "c" {
                values.push(parser.read_element_text("c"));
            }
        });
        assert_eq!(values, vec!["5", "6"], "consuming children must not desync the loop");
    }

    #[test]
    fn truncated_xml_terminates_instead_of_looping() {
        let t = trace("<a><b>text");
        assert!(t.iter().any(|(e, n, _)| *e == Event::StartTag && n == "b"));
        // The point is that trace() returned at all.
    }

    #[test]
    fn ooxml_toggle_attributes() {
        assert!(bool_attr(None), "an absent toggle is on");
        assert!(bool_attr(Some("1")));
        assert!(bool_attr(Some("true")));
        assert!(bool_attr(Some("on")));
        assert!(!bool_attr(Some("0")));
        assert!(!bool_attr(Some("false")));
    }

    #[test]
    fn a1_references() {
        assert_eq!(col_index("A1"), 0);
        assert_eq!(col_index("Z9"), 25);
        assert_eq!(col_index("AA1"), 26);
        assert_eq!(col_index("AB12"), 27);
        assert_eq!(col_index(""), 0);
        assert_eq!(row_index("AB12"), 11);
        assert_eq!(row_index("A1"), 0);
        assert_eq!(row_index("A"), -1, "no row part");
    }
}
