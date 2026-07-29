//! Hierarchical (tree) CRDT over a flat-ODF XML document. Faithful port of
//! `DocumentTreeCrdt.kt`. Node/State JSON is signed and exchanged across
//! devices, so serde output MUST be byte-for-byte identical to the Kotlin
//! kotlinx.serialization output (all fields, declaration order, compact).
//!
//! Node kinds: "e" element, "s" opaque leaf, "c" character, "r" raw run.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// A CRDT node. Field order MUST match the Kotlin `@Serializable data class Node`
/// declaration order exactly: id, parent, left, kind, payload, deleted, lamport,
/// dev, name, attrLamport, attrDev.
///
/// WIRE FORMAT: the ops/state JSON is signed and exchanged across devices, so
/// serde output must be byte-for-byte identical to kotlinx.serialization. The
/// Kotlin side serializes with `Json { ignoreUnknownKeys = true }`, whose
/// `encodeDefaults` defaults to FALSE — so fields that hold their Kotlin default
/// (`deleted=false`, `name=""`, `attrLamport=0`, `attrDev=""`) are OMITTED.
/// Fields without a Kotlin default (`parent`, `left`, ...) are always emitted,
/// even when empty. `skip_serializing_if` below mirrors that exactly.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Node {
    pub id: String,
    pub parent: String,
    pub left: String,
    pub kind: String,
    pub payload: String,
    #[serde(default, skip_serializing_if = "is_false")]
    pub deleted: bool,
    pub lamport: i64,
    pub dev: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub name: String,
    #[serde(default, rename = "attrLamport", skip_serializing_if = "is_zero_i64")]
    pub attr_lamport: i64,
    #[serde(default, rename = "attrDev", skip_serializing_if = "String::is_empty")]
    pub attr_dev: String,
}

fn is_false(b: &bool) -> bool {
    !*b
}

fn is_zero_i64(v: &i64) -> bool {
    *v == 0
}

/// Serialized document state. Field order: device, clock, nodes.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct State {
    pub device: String,
    pub clock: i64,
    pub nodes: Vec<Node>,
}

/// A desired child produced by parsing flat XML.
enum Desired {
    /// tag, self_closing, children
    El(String, bool, Vec<Desired>),
    /// text, raw
    Text(String, bool),
}

pub struct DocumentTreeCrdt {
    device: String,
    /// insertion-ordered ids (mirrors Kotlin LinkedHashMap iteration order)
    order: Vec<String>,
    nodes: HashMap<String, Node>,
    /// parent id -> child ids in insertion order (incl. tombstones)
    children_by_parent: HashMap<String, Vec<String>>,
    clock: i64,
}

impl DocumentTreeCrdt {
    pub fn new(device: String) -> Self {
        DocumentTreeCrdt {
            device,
            order: Vec::new(),
            nodes: HashMap::new(),
            children_by_parent: HashMap::new(),
            clock: 0,
        }
    }

    /// Snapshot of the current state (nodes in insertion order).
    pub fn to_state(&self) -> State {
        State {
            device: self.device.clone(),
            clock: self.clock,
            nodes: self.order.iter().map(|id| self.nodes[id].clone()).collect(),
        }
    }

    pub fn load_state(&mut self, json_str: &str) {
        let s: State = match serde_json::from_str(json_str) {
            Ok(v) => v,
            Err(_) => return,
        };
        self.order.clear();
        self.nodes.clear();
        self.children_by_parent.clear();
        for n in s.nodes {
            let c = n.clone();
            self.order.push(c.id.clone());
            self.children_by_parent
                .entry(c.parent.clone())
                .or_default()
                .push(c.id.clone());
            self.nodes.insert(c.id.clone(), c);
        }
        self.clock = s.clock;
    }

    pub fn serialize(&self) -> String {
        serde_json::to_string(&self.to_state()).unwrap_or_default()
    }

    /// Serializes just the node array of the current state (JSON array).
    pub fn to_state_nodes_json(&self) -> String {
        let nodes: Vec<Node> = self.order.iter().map(|id| self.nodes[id].clone()).collect();
        serde_json::to_string(&nodes).unwrap_or_default()
    }

    fn insert_node(&mut self, node: Node) {
        let id = node.id.clone();
        let parent = node.parent.clone();
        if !self.nodes.contains_key(&id) {
            self.order.push(id.clone());
        }
        self.children_by_parent
            .entry(parent)
            .or_default()
            .push(id.clone());
        self.nodes.insert(id, node);
    }

    /// Merges a batch of remote node ops. Commutative + idempotent.
    pub fn apply(&mut self, ops: &[Node]) {
        for op in ops {
            self.clock = self.clock.max(op.lamport.max(op.attr_lamport));
            if !self.nodes.contains_key(&op.id) {
                self.insert_node(op.clone());
            } else {
                let cur = self.nodes.get_mut(&op.id).unwrap();
                if op.deleted {
                    cur.deleted = true;
                }
                if op.kind == "e"
                    && (op.attr_lamport > cur.attr_lamport
                        || (op.attr_lamport == cur.attr_lamport && op.attr_dev > cur.attr_dev))
                {
                    cur.payload = op.payload.clone();
                    cur.attr_lamport = op.attr_lamport;
                    cur.attr_dev = op.attr_dev.clone();
                }
            }
        }
    }

    pub fn render(&self) -> String {
        let mut sb = String::new();
        self.render_children("", &mut sb);
        sb
    }

    fn render_children(&self, parent: &str, sb: &mut String) {
        for id in self.ordered_children(parent) {
            let node = &self.nodes[&id];
            if node.deleted {
                continue;
            }
            if node.kind == "e" {
                sb.push_str(&node.payload);
                self.render_children(&node.id, sb);
                sb.push_str(&close_tag_of(&node.payload));
            } else {
                sb.push_str(&node.payload);
            }
        }
    }

    /// RGA order of a parent's children (incl. tombstones). Returns node ids.
    fn ordered_children(&self, parent: &str) -> Vec<String> {
        let kids = match self.children_by_parent.get(parent) {
            Some(k) => k,
            None => return Vec::new(),
        };
        // group by `left`
        let mut by_left: HashMap<String, Vec<String>> = HashMap::new();
        for id in kids {
            let n = &self.nodes[id];
            by_left.entry(n.left.clone()).or_default().push(id.clone());
        }
        // sort each group: descending lamport, then descending dev
        for group in by_left.values_mut() {
            group.sort_by(|a, b| {
                let na = &self.nodes[a];
                let nb = &self.nodes[b];
                nb.lamport
                    .cmp(&na.lamport)
                    .then_with(|| nb.dev.cmp(&na.dev))
            });
        }
        let mut result: Vec<String> = Vec::with_capacity(kids.len());
        let mut stack: Vec<String> = Vec::new();
        if let Some(roots) = by_left.get("") {
            for id in roots.iter().rev() {
                stack.push(id.clone());
            }
        }
        while let Some(e) = stack.pop() {
            result.push(e.clone());
            if let Some(children) = by_left.get(&e) {
                for id in children.iter().rev() {
                    stack.push(id.clone());
                }
            }
        }
        result
    }
}

// --- Reconcile the current tree toward a new flat-XML state, producing ops ---

impl DocumentTreeCrdt {
    pub fn update(&mut self, xml: &str) -> Vec<Node> {
        let mut ops: Vec<Node> = Vec::new();
        let desired = parse(xml);
        self.diff_children("", &desired, &mut ops);
        ops
    }

    fn new_id(&mut self) -> String {
        self.clock += 1;
        format!("{}:{}", self.clock, self.device)
    }

    fn key_of_node(&self, id: &str) -> String {
        let n = &self.nodes[id];
        if n.kind == "e" {
            format!("e:{}", n.name)
        } else {
            format!("{}:{}", n.kind, n.payload)
        }
    }

    fn reuse(&mut self, node_id: &str, d: &Desired, ops: &mut Vec<Node>, prev_id: &mut String) {
        if let Desired::El(tag, self_closing, children) = d {
            let kind_is_e = self.nodes[node_id].kind == "e";
            if kind_is_e {
                if self.nodes[node_id].payload != *tag {
                    self.clock += 1;
                    let c = self.clock;
                    let dev = self.device.clone();
                    let node = self.nodes.get_mut(node_id).unwrap();
                    node.payload = tag.clone();
                    node.attr_lamport = c;
                    node.attr_dev = dev;
                    ops.push(node.clone());
                }
                if !*self_closing {
                    let id = node_id.to_string();
                    self.diff_children(&id, children, ops);
                }
            }
        }
        *prev_id = node_id.to_string();
    }

    fn insert(&mut self, parent: &str, d: &Desired, ops: &mut Vec<Node>, prev_id: &mut String) {
        let id = self.new_id();
        let (kind, payload, name): (String, String, String) = match d {
            Desired::El(tag, self_closing, _) => {
                let kind = if *self_closing { "s" } else { "e" };
                let name = if !*self_closing { name_of(tag) } else { String::new() };
                (kind.to_string(), tag.clone(), name)
            }
            Desired::Text(text, raw) => {
                let kind = if *raw { "r" } else { "c" };
                (kind.to_string(), text.clone(), String::new())
            }
        };
        let is_e = kind == "e";
        let node = Node {
            id: id.clone(),
            parent: parent.to_string(),
            left: prev_id.clone(),
            kind,
            payload,
            deleted: false,
            lamport: self.clock,
            dev: self.device.clone(),
            name,
            attr_lamport: if is_e { self.clock } else { 0 },
            attr_dev: if is_e { self.device.clone() } else { String::new() },
        };
        self.insert_node(node.clone());
        ops.push(node);
        if let Desired::El(_, self_closing, children) = d {
            if !*self_closing {
                self.diff_children(&id, children, ops);
            }
        }
        *prev_id = id;
    }

    fn diff_children(&mut self, parent: &str, desired: &[Desired], ops: &mut Vec<Node>) {
        let current: Vec<String> = self
            .ordered_children(parent)
            .into_iter()
            .filter(|id| !self.nodes[id].deleted)
            .collect();
        let key_c: Vec<String> = current.iter().map(|id| self.key_of_node(id)).collect();
        let key_d: Vec<String> = desired.iter().map(key_of_desired).collect();

        let mut pre = 0usize;
        while pre < current.len() && pre < desired.len() && key_c[pre] == key_d[pre] {
            pre += 1;
        }
        let mut suf = 0usize;
        while suf < current.len() - pre
            && suf < desired.len() - pre
            && key_c[current.len() - 1 - suf] == key_d[desired.len() - 1 - suf]
        {
            suf += 1;
        }

        let mut prev_id = String::new();
        for i in 0..pre {
            let id = current[i].clone();
            self.reuse(&id, &desired[i], ops, &mut prev_id);
        }

        let mid_c_start = pre;
        let mid_c_end = current.len() - suf;
        let mid_d_start = pre;
        let mid_d_end = desired.len() - suf;
        let mid_c: Vec<String> = current[mid_c_start..mid_c_end].to_vec();
        let mid_c_keys: Vec<String> = key_c[mid_c_start..mid_c_end].to_vec();
        let mid_d_keys: Vec<String> = key_d[mid_d_start..mid_d_end].to_vec();

        let pairs = lcs(&mid_d_keys, &mid_c_keys);
        let mut matched_c: std::collections::HashSet<usize> = std::collections::HashSet::new();
        let mut pp = 0usize;
        for di in 0..(mid_d_end - mid_d_start) {
            if pp < pairs.len() && pairs[pp].0 == di {
                let ci = pairs[pp].1;
                pp += 1;
                matched_c.insert(ci);
                let id = mid_c[ci].clone();
                self.reuse(&id, &desired[mid_d_start + di], ops, &mut prev_id);
            } else {
                self.insert(parent, &desired[mid_d_start + di], ops, &mut prev_id);
            }
        }
        for ci in 0..mid_c.len() {
            if !matched_c.contains(&ci) {
                let id = &mid_c[ci];
                if !self.nodes[id].deleted {
                    self.nodes.get_mut(id).unwrap().deleted = true;
                    ops.push(self.nodes[id].clone());
                }
            }
        }
        for i in 0..suf {
            let id = current[current.len() - suf + i].clone();
            self.reuse(&id, &desired[desired.len() - suf + i], ops, &mut prev_id);
        }
    }
}

fn lcs(a: &[String], b: &[String]) -> Vec<(usize, usize)> {
    let n = a.len();
    let m = b.len();
    if n == 0 || m == 0 {
        return Vec::new();
    }
    let mut dp = vec![vec![0i32; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            dp[i][j] = if a[i] == b[j] {
                dp[i + 1][j + 1] + 1
            } else {
                dp[i + 1][j].max(dp[i][j + 1])
            };
        }
    }
    let mut out: Vec<(usize, usize)> = Vec::new();
    let mut i = 0usize;
    let mut j = 0usize;
    while i < n && j < m {
        if a[i] == b[j] {
            out.push((i, j));
            i += 1;
            j += 1;
        } else if dp[i + 1][j] >= dp[i][j + 1] {
            i += 1;
        } else {
            j += 1;
        }
    }
    out
}

fn key_of_desired(d: &Desired) -> String {
    match d {
        Desired::El(tag, self_closing, _) => {
            if *self_closing {
                format!("s:{}", tag)
            } else {
                format!("e:{}", name_of(tag))
            }
        }
        Desired::Text(text, raw) => {
            if *raw {
                format!("r:{}", text)
            } else {
                format!("c:{}", text)
            }
        }
    }
}

fn name_of(tag: &str) -> String {
    let chars: Vec<char> = tag.chars().collect();
    let mut k = 1usize;
    while k < chars.len()
        && chars[k] != ' '
        && chars[k] != '>'
        && chars[k] != '/'
        && chars[k] != '\t'
        && chars[k] != '\n'
    {
        k += 1;
    }
    if chars.len() < 1 {
        return String::new();
    }
    chars[1.min(chars.len())..k.min(chars.len())].iter().collect()
}

fn close_tag_of(open_tag: &str) -> String {
    format!("</{}>", name_of(open_tag))
}

// --- Flat XML -> desired tree ---

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
            } else if tag.starts_with("<?") || tag.starts_with("<!") {
                let idx = arena.len();
                arena.push(Item::El {
                    tag,
                    self_closing: true,
                    children: Vec::new(),
                });
                let p = *stack.last().unwrap();
                add_child(&mut arena, p, idx);
            } else if tag.ends_with("/>") {
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
        assert_eq!(s.nodes[0].deleted, false);
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
