//! OOXML package structure — relationships, content types and path normalisation.
//!
//! Port of the non-IO half of `OoxmlPackage.kt`. Unzipping stays in Kotlin (`java.util.zip`): a
//! Rust zip crate would pull in `flate2`/`miniz_oxide`, and decompression is IO-shaped work, so
//! Kotlin hands over the already-extracted parts.
//!
//! A package is a flat map of part path → text. Parts reference each other by relationship id
//! (`rId7`) resolved through a sibling `_rels` part, and those targets are usually *relative*, so
//! [`normalize`] is what turns `../media/image1.png` inside `word/document.xml` into
//! `word/media/image1.png`. Getting that wrong silently loses images rather than erroring.

use std::collections::HashMap;

/// One relationship: its id, resolved absolute target, type URI, and whether it points outside
/// the package (in which case the target is a URL and must not be normalised).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rel {
    pub id: String,
    pub target: String,
    pub type_uri: Option<String>,
    pub external: bool,
}

#[derive(Debug, Default, Clone)]
pub struct ContentTypes {
    /// Lowercased extension → content type.
    pub defaults: HashMap<String, String>,
    /// Absolute part name (with leading `/`) → content type.
    pub overrides: HashMap<String, String>,
}

pub struct Package {
    entries: HashMap<String, String>,
    rels_cache: HashMap<String, HashMap<String, Rel>>,
    content_types: Option<ContentTypes>,
}

impl Package {
    /// `entries` maps part path (as stored in the zip, no leading slash) to its text.
    pub fn new(entries: HashMap<String, String>) -> Self {
        Self { entries, rels_cache: HashMap::new(), content_types: None }
    }

    pub fn part(&self, path: &str) -> Option<&str> {
        self.entries.get(path).map(String::as_str)
    }

    /// Relationships declared for `part_path`, keyed by rId. Empty when the part has no `_rels`.
    pub fn rels_for(&mut self, part_path: &str) -> &HashMap<String, Rel> {
        if !self.rels_cache.contains_key(part_path) {
            let rels_path = rels_path_for(part_path);
            let parsed = match self.entries.get(&rels_path) {
                Some(xml) => parse_rels(xml, part_path),
                None => HashMap::new(),
            };
            self.rels_cache.insert(part_path.to_string(), parsed);
        }
        &self.rels_cache[part_path]
    }

    /// Resolves an rId against `part_path` to an absolute package path (or an external URL).
    pub fn resolve(&mut self, part_path: &str, r_id: Option<&str>) -> Option<String> {
        let r_id = r_id?;
        self.rels_for(part_path).get(r_id).map(|r| r.target.clone())
    }

    pub fn content_types(&mut self) -> &ContentTypes {
        if self.content_types.is_none() {
            let parsed = self
                .entries
                .get("[Content_Types].xml")
                .map(|xml| parse_content_types(xml))
                .unwrap_or_default();
            self.content_types = Some(parsed);
        }
        self.content_types.as_ref().unwrap()
    }

    /// Parts whose declared content type contains `type_contains`, without the leading slash.
    pub fn parts_of_type(&mut self, type_contains: &str) -> Vec<String> {
        let needle = type_contains.to_ascii_lowercase();
        let mut out: Vec<String> = self
            .content_types()
            .overrides
            .iter()
            .filter(|(_, ct)| ct.to_ascii_lowercase().contains(&needle))
            .map(|(part, _)| part.strip_prefix('/').unwrap_or(part).to_string())
            .collect();
        // HashMap iteration order is arbitrary; callers index into this list, so make it stable.
        out.sort();
        out
    }
}

/// `word/document.xml` → `word/_rels/document.xml.rels`.
pub fn rels_path_for(part_path: &str) -> String {
    match part_path.rfind('/') {
        Some(slash) => format!("{}/_rels/{}.rels", &part_path[..slash], &part_path[slash + 1..]),
        None => format!("_rels/{part_path}.rels"),
    }
}

/// Resolves a relationship target against the directory of `part_path`.
///
/// Handles `/absolute`, `./here` and `../up` forms. A target starting with `/` is package-absolute
/// and only loses its slash.
pub fn normalize(part_path: &str, target: &str) -> String {
    if let Some(absolute) = target.strip_prefix('/') {
        return absolute.to_string();
    }
    let base_dir = match part_path.rfind('/') {
        Some(slash) => &part_path[..slash],
        None => "",
    };
    let mut stack: Vec<&str> = Vec::new();
    if !base_dir.is_empty() {
        stack.extend(base_dir.split('/'));
    }
    for seg in target.split('/') {
        match seg {
            "" | "." => {}
            ".." => {
                stack.pop();
            }
            other => stack.push(other),
        }
    }
    stack.join("/")
}

/// Parses a `.rels` part. Targets are normalised unless `TargetMode="External"`.
pub fn parse_rels(xml: &str, part_path: &str) -> HashMap<String, Rel> {
    use crate::xml::{Event, XmlParser};

    let mut out = HashMap::new();
    let mut p = XmlParser::new(xml);
    loop {
        match p.next() {
            Event::EndDocument => break,
            Event::StartTag if p.name() == "Relationship" => {
                let id = p.attr("Id").map(str::to_string);
                let target = p.attr("Target").map(str::to_string);
                let type_uri = p.attr("Type").map(str::to_string);
                let external = p.attr("TargetMode").is_some_and(|m| m.eq_ignore_ascii_case("External"));
                if let (Some(id), Some(target)) = (id, target) {
                    let resolved =
                        if external { target } else { normalize(part_path, &target) };
                    out.insert(
                        id.clone(),
                        Rel { id, target: resolved, type_uri, external },
                    );
                }
            }
            _ => {}
        }
    }
    out
}

/// Parses `[Content_Types].xml` into its default (by extension) and override (by part) tables.
pub fn parse_content_types(xml: &str) -> ContentTypes {
    use crate::xml::{Event, XmlParser};

    let mut types = ContentTypes::default();
    let mut p = XmlParser::new(xml);
    loop {
        match p.next() {
            Event::EndDocument => break,
            Event::StartTag => match p.name() {
                "Default" => {
                    if let (Some(ext), Some(ct)) = (p.attr("Extension"), p.attr("ContentType")) {
                        types.defaults.insert(ext.to_ascii_lowercase(), ct.to_string());
                    }
                }
                "Override" => {
                    if let (Some(part), Some(ct)) = (p.attr("PartName"), p.attr("ContentType")) {
                        types.overrides.insert(part.to_string(), ct.to_string());
                    }
                }
                _ => {}
            },
            _ => {}
        }
    }
    types
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rels_paths() {
        assert_eq!(rels_path_for("word/document.xml"), "word/_rels/document.xml.rels");
        assert_eq!(rels_path_for("xl/workbook.xml"), "xl/_rels/workbook.xml.rels");
        // The package root part has no directory component.
        assert_eq!(rels_path_for(""), "_rels/.rels");
    }

    #[test]
    fn relative_targets_resolve_against_the_part_directory() {
        assert_eq!(normalize("word/document.xml", "media/image1.png"), "word/media/image1.png");
        assert_eq!(normalize("word/document.xml", "./styles.xml"), "word/styles.xml");
        // The `../` form is how xlsx references shared strings from a worksheet.
        assert_eq!(normalize("xl/worksheets/sheet1.xml", "../sharedStrings.xml"), "xl/sharedStrings.xml");
        assert_eq!(normalize("a/b/c/d.xml", "../../e.xml"), "a/e.xml");
    }

    #[test]
    fn absolute_targets_only_lose_the_slash() {
        assert_eq!(normalize("word/document.xml", "/word/media/x.png"), "word/media/x.png");
        assert_eq!(normalize("deep/nested/part.xml", "/root.xml"), "root.xml");
    }

    #[test]
    fn traversal_past_the_root_clamps() {
        // Malformed packages exist; walking above the root must not panic or produce "..".
        assert_eq!(normalize("a.xml", "../../../b.xml"), "b.xml");
        assert_eq!(normalize("x/y.xml", "../../../z.png"), "z.png");
    }

    #[test]
    fn rels_are_parsed_and_normalised() {
        let xml = r#"<?xml version="1.0"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://x/styles" Target="styles.xml"/>
          <Relationship Id="rId2" Type="http://x/image" Target="media/i.png"/>
          <Relationship Id="rId3" Type="http://x/hyperlink" Target="https://example.com" TargetMode="External"/>
        </Relationships>"#;
        let rels = parse_rels(xml, "word/document.xml");
        assert_eq!(rels.len(), 3);
        assert_eq!(rels["rId1"].target, "word/styles.xml");
        assert_eq!(rels["rId2"].target, "word/media/i.png");
        // External targets are URLs and must survive untouched.
        assert_eq!(rels["rId3"].target, "https://example.com");
        assert!(rels["rId3"].external);
        assert!(!rels["rId1"].external);
    }

    #[test]
    fn rels_without_id_or_target_are_dropped() {
        let xml = r#"<Relationships>
            <Relationship Id="rId1"/>
            <Relationship Target="x.xml"/>
            <Relationship Id="rId2" Target="ok.xml"/>
        </Relationships>"#;
        let rels = parse_rels(xml, "a/b.xml");
        assert_eq!(rels.len(), 1);
        assert!(rels.contains_key("rId2"));
    }

    #[test]
    fn content_types_split_into_defaults_and_overrides() {
        let xml = r#"<Types>
            <Default Extension="PNG" ContentType="image/png"/>
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>"#;
        let ct = parse_content_types(xml);
        assert_eq!(ct.defaults.get("png").map(String::as_str), Some("image/png"), "extensions are lowercased");
        assert_eq!(ct.defaults.len(), 2);
        assert!(ct.overrides.contains_key("/xl/worksheets/sheet1.xml"));
    }

    #[test]
    fn package_resolves_through_rels_and_caches() {
        let mut entries = HashMap::new();
        entries.insert(
            "word/_rels/document.xml.rels".to_string(),
            r#"<Relationships><Relationship Id="rId5" Target="media/pic.png"/></Relationships>"#.to_string(),
        );
        entries.insert("word/document.xml".to_string(), "<w:document/>".to_string());
        let mut pkg = Package::new(entries);

        assert_eq!(pkg.resolve("word/document.xml", Some("rId5")).as_deref(), Some("word/media/pic.png"));
        // Second call comes from the cache and must agree.
        assert_eq!(pkg.resolve("word/document.xml", Some("rId5")).as_deref(), Some("word/media/pic.png"));
        assert_eq!(pkg.resolve("word/document.xml", Some("rId404")), None);
        assert_eq!(pkg.resolve("word/document.xml", None), None);
    }

    #[test]
    fn a_part_with_no_rels_yields_an_empty_map_not_an_error() {
        let mut pkg = Package::new(HashMap::new());
        assert!(pkg.rels_for("word/document.xml").is_empty());
    }

    #[test]
    fn parts_of_type_is_filtered_and_stable() {
        let mut entries = HashMap::new();
        entries.insert(
            "[Content_Types].xml".to_string(),
            r#"<Types>
                <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/x.worksheet+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/x.worksheet+xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/x.sheet.main+xml"/>
            </Types>"#.to_string(),
        );
        let mut pkg = Package::new(entries);
        let sheets = pkg.parts_of_type("worksheet");
        assert_eq!(sheets, vec!["xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"]);
        assert_eq!(pkg.parts_of_type("sheet.main"), vec!["xl/workbook.xml"]);
        assert!(pkg.parts_of_type("nothing").is_empty());
    }

    #[test]
    fn malformed_rels_xml_does_not_panic() {
        for xml in ["", "<Relationships", "not xml at all", "<a><b></a>"] {
            let _ = parse_rels(xml, "x.xml");
            let _ = parse_content_types(xml);
        }
    }
}
