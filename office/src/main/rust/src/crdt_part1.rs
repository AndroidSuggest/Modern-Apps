enum Item {
    El {
        tag: String,
        self_closing: bool,
        children: Vec<usize>,
    },
    Text {
        text: String,
        raw: bool,
    },
}

fn add_child(arena: &mut [Item], parent_idx: usize, child_idx: usize) {
    if let Item::El { children, .. } = &mut arena[parent_idx] {
        children.push(child_idx);
    }
}

fn to_desired(arena: &[Item], idx: usize) -> Desired {
    match &arena[idx] {
        Item::El {
            tag,
            self_closing,
            children,
        } => Desired::El(
            tag.clone(),
            *self_closing,
            children.iter().map(|c| to_desired(arena, *c)).collect(),
        ),
        Item::Text { text, raw } => Desired::Text(text.clone(), *raw),
    }
}

fn parse(xml: &str) -> Vec<Desired> {
    let chars: Vec<char> = xml.chars().collect();
    let n = chars.len();
    let mut arena: Vec<Item> = vec![Item::El {
        tag: String::new(),
        self_closing: false,
        children: Vec::new(),
    }];
    let mut stack: Vec<usize> = vec![0];
    let mut i = 0usize;
    let mut in_binary = false;
    while i < n {
        if chars[i] == '<' {
            let mut j = i + 1;
            let mut quote = '\0';
            while j < n {
                let c = chars[j];
                if quote != '\0' {
                    if c == quote {
                        quote = '\0';
                    }
                } else if c == '"' || c == '\'' {
                    quote = c;
                } else if c == '>' {
                    break;
                }
                j += 1;
            }
            let end = (j + 1).min(n);
            let tag: String = chars[i..end].iter().collect();
            if tag.starts_with("</") {
                if stack.len() > 1 {
                    stack.pop();
                }
                if tag.starts_with("</office:binary-data") {
                    in_binary = false;
                }
            } else if tag.starts_with("<?") || tag.starts_with("<!") || tag.ends_with("/>") {
                let idx = arena.len();
                arena.push(Item::El {
                    tag,
                    self_closing: true,
                    children: Vec::new(),
                });
                let p = *stack.last().unwrap();
                add_child(&mut arena, p, idx);
            } else {
                let is_bin = tag.starts_with("<office:binary-data");
                let idx = arena.len();
                arena.push(Item::El {
                    tag,
                    self_closing: false,
                    children: Vec::new(),
                });
                let p = *stack.last().unwrap();
                add_child(&mut arena, p, idx);
                stack.push(idx);
                if is_bin {
                    in_binary = true;
                }
            }
            i = j + 1;
        } else {
            let mut end = i;
            while end < n && chars[end] != '<' {
                end += 1;
            }
            if in_binary {
                let text: String = chars[i..end].iter().collect();
                let idx = arena.len();
                arena.push(Item::Text { text, raw: true });
                let p = *stack.last().unwrap();
                add_child(&mut arena, p, idx);
            } else {
                for c in chars[i..end].iter() {
                    let idx = arena.len();
                    arena.push(Item::Text {
                        text: c.to_string(),
                        raw: false,
                    });
                    let p = *stack.last().unwrap();
                    add_child(&mut arena, p, idx);
                }
            }
            i = end;
        }
    }
    if let Item::El { children, .. } = &arena[0] {
        children.iter().map(|c| to_desired(&arena, *c)).collect()
    } else {
        Vec::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(id: &str, parent: &str, left: &str, kind: &str, payload: &str, lamport: i64, dev: &str) -> Node {
        Node {
            id: id.into(),
            parent: parent.into(),
            left: left.into(),
            kind: kind.into(),
            payload: payload.into(),
            deleted: false,
            lamport,
            dev: dev.into(),
            name: String::new(),
            attr_lamport: 0,
            attr_dev: String::new(),
        }
    }

    /// The serde output MUST be byte-for-byte identical to kotlinx.serialization:
    /// every field, declaration order, no whitespace, defaults included.
    #[test]
    fn exact_json_matches_kotlinx() {
        let state = State {
            device: "devA".into(),
            clock: 3,
            nodes: vec![
                Node {
                    id: "1:devA".into(),
                    parent: "".into(),
                    left: "".into(),
                    kind: "e".into(),
                    payload: "<p>".into(),
                    deleted: false,
                    lamport: 1,
                    dev: "devA".into(),
                    name: "p".into(),
                    attr_lamport: 1,
                    attr_dev: "devA".into(),
                },
                Node {
                    id: "2:devA".into(),
                    parent: "1:devA".into(),
                    left: "".into(),
                    kind: "c".into(),
                    payload: "x".into(),
                    deleted: false,
                    lamport: 2,
                    dev: "devA".into(),
                    name: "".into(),
                    attr_lamport: 0,
                    attr_dev: "".into(),
                },
            ],
        };
        // kotlinx (encodeDefaults=false) omits deleted/name/attrLamport/attrDev at
        // their defaults; parent/left are always emitted even when empty.
        let expected = r#"{"device":"devA","clock":3,"nodes":[{"id":"1:devA","parent":"","left":"","kind":"e","payload":"<p>","lamport":1,"dev":"devA","name":"p","attrLamport":1,"attrDev":"devA"},{"id":"2:devA","parent":"1:devA","left":"","kind":"c","payload":"x","lamport":2,"dev":"devA"}]}"#;
        let got = serde_json::to_string(&state).unwrap();
        assert_eq!(got, expected);
    }

    /// A deleted element op keeps `deleted` before `lamport` (declaration order).
    #[test]
    fn exact_json_deleted_and_element_order() {
        let n = Node {
            id: "5:B".into(),
            parent: "1:A".into(),
            left: "2:A".into(),
            kind: "e".into(),
            payload: "<p a=\"2\">".into(),
            deleted: true,
            lamport: 4,
            dev: "B".into(),
            name: "p".into(),
            attr_lamport: 7,
            attr_dev: "B".into(),
        };
        let expected = r#"{"id":"5:B","parent":"1:A","left":"2:A","kind":"e","payload":"<p a=\"2\">","deleted":true,"lamport":4,"dev":"B","name":"p","attrLamport":7,"attrDev":"B"}"#;
        assert_eq!(serde_json::to_string(&n).unwrap(), expected);
    }

    #[test]
    fn json_round_trip() {
        let json = r#"{"device":"devA","clock":3,"nodes":[{"id":"1:devA","parent":"","left":"","kind":"e","payload":"<p>","lamport":1,"dev":"devA","name":"p","attrLamport":1,"attrDev":"devA"}]}"#;
        let s: State = serde_json::from_str(json).unwrap();
        assert_eq!(serde_json::to_string(&s).unwrap(), json);
    }

    /// Deserialization tolerates missing defaulted fields and unknown keys.
    #[test]
    fn json_ignores_unknown_and_defaults() {
        let json = r#"{"device":"d","clock":0,"nodes":[{"id":"1:d","parent":"","left":"","kind":"c","payload":"a","lamport":1,"dev":"d","extra":42}]}"#;
        let s: State = serde_json::from_str(json).unwrap();
        assert!(!s.nodes[0].deleted);
        assert_eq!(s.nodes[0].name, "");
        assert_eq!(s.nodes[0].attr_lamport, 0);
    }

    #[test]
    fn update_render_flat_odf() {
        let mut c = DocumentTreeCrdt::new("A".into());
        let xml = "<office><p>hi</p></office>";
        c.update(xml);
        assert_eq!(c.render(), xml);
    }

    #[test]
    fn update_then_edit_render() {
        let mut c = DocumentTreeCrdt::new("A".into());
        c.update("<p>ab</p>");
        c.update("<p>axb</p>");
        assert_eq!(c.render(), "<p>axb</p>");
        c.update("<p>ab</p>");
        assert_eq!(c.render(), "<p>ab</p>");
    }

    #[test]
    fn apply_commutative_and_idempotent() {
        // Build a source doc and capture its ops.
        let mut src = DocumentTreeCrdt::new("A".into());
        let ops = src.update("<office><p>Hello</p><p>World</p></office>");
        let expected = src.render();

        // Apply in original order.
        let mut a = DocumentTreeCrdt::new("B".into());
        a.apply(&ops);
        assert_eq!(a.render(), expected);

        // Apply in reversed order -> same render (commutative).
        let mut b = DocumentTreeCrdt::new("B".into());
        let mut rev = ops.clone();
        rev.reverse();
        b.apply(&rev);
        assert_eq!(b.render(), expected);

        // Double apply -> same render (idempotent).
        let mut d = DocumentTreeCrdt::new("B".into());
        d.apply(&ops);
        d.apply(&ops);
        assert_eq!(d.render(), expected);
    }

    #[test]
    fn attribute_lww() {
        let base = node("1:A", "", "", "e", "<p>", 1, "A");
        let mut base = base;
        base.name = "p".into();
        base.attr_lamport = 1;
        base.attr_dev = "A".into();

        let mut lo = base.clone();
        lo.payload = "<p a=\"1\">".into();
        lo.attr_lamport = 2;
        lo.attr_dev = "A".into();

        let mut hi = base.clone();
        hi.payload = "<p a=\"2\">".into();
        hi.attr_lamport = 3;
        hi.attr_dev = "B".into();

        // Apply hi then lo -> hi wins (higher lamport).
        let mut c = DocumentTreeCrdt::new("Z".into());
        c.apply(&[base.clone(), hi.clone(), lo.clone()]);
        assert!(c.render().contains("a=\"2\""));

        // Order-independent.
        let mut c2 = DocumentTreeCrdt::new("Z".into());
        c2.apply(&[lo, base, hi]);
        assert!(c2.render().contains("a=\"2\""));
    }

    #[test]
    fn self_closing_and_raw_binary() {
        let mut c = DocumentTreeCrdt::new("A".into());
        let xml = "<?xml version=\"1.0\"?><office:binary-data>QUJD</office:binary-data><br/>";
        c.update(xml);
        assert_eq!(c.render(), xml);
    }
}
