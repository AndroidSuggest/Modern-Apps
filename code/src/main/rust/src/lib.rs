//! Native tree-sitter syntax highlighter for the `code` app, exposed to Kotlin via one JNI entry
//! point. It parses a whole source buffer with the appropriate grammar, runs the grammar's bundled
//! `highlights` query, and returns packed `(startByte, endByte, captureKindOrdinal)` triples that
//! [`com.vayunmathur.code.util.TreeSitterNative`] maps onto editor colours.
//!
//! The capture-kind ordinals are aligned with the Kotlin `TsKind` enum:
//! `0 keyword, 1 string, 2 number, 3 comment, 4 annotation, 5 function, 6 type, 7 property`.
//!
//! Targets the tree-sitter 0.22 API. Byte offsets are returned as-is; they line up with Kotlin
//! string indices for ASCII/BMP text (the common case for source code) — full UTF-16 mapping for
//! astral characters is a documented v1 limitation.

use jni::objects::{JClass, JString};
use jni::sys::jintArray;
use jni::JNIEnv;
use tree_sitter::{Language, Parser, Query, QueryCursor};

/// Resolves a language id (see `TreeSitterNative.languageIdFor`) to its grammar + highlights query.
fn grammar(id: &str) -> Option<(Language, &'static str)> {
    let entry: (Language, &'static str) = match id {
        "kotlin" => (
            tree_sitter_kotlin::language(),
            tree_sitter_kotlin::HIGHLIGHTS_QUERY,
        ),
        "java" => (
            tree_sitter_java::language(),
            tree_sitter_java::HIGHLIGHTS_QUERY,
        ),
        "javascript" => (
            tree_sitter_javascript::language(),
            tree_sitter_javascript::HIGHLIGHT_QUERY,
        ),
        "typescript" => (
            tree_sitter_typescript::language_typescript(),
            tree_sitter_typescript::HIGHLIGHTS_QUERY,
        ),
        "python" => (
            tree_sitter_python::language(),
            tree_sitter_python::HIGHLIGHTS_QUERY,
        ),
        "rust" => (
            tree_sitter_rust::language(),
            tree_sitter_rust::HIGHLIGHTS_QUERY,
        ),
        "go" => (
            tree_sitter_go::language(),
            tree_sitter_go::HIGHLIGHTS_QUERY,
        ),
        "json" => (
            tree_sitter_json::language(),
            tree_sitter_json::HIGHLIGHTS_QUERY,
        ),
        "c" => (
            tree_sitter_c::language(),
            tree_sitter_c::HIGHLIGHT_QUERY,
        ),
        "cpp" => (
            tree_sitter_cpp::language(),
            tree_sitter_cpp::HIGHLIGHT_QUERY,
        ),
        _ => return None,
    };
    Some(entry)
}

/// Maps a highlights capture name (e.g. `keyword.control`, `variable.member`) to a `TsKind` ordinal,
/// or `-1` to skip captures we don't colour.
fn kind_for(name: &str) -> i32 {
    match name.split('.').next().unwrap_or("") {
        "keyword" => 0,
        "string" | "char" => 1,
        "number" | "constant" | "float" => 2,
        "comment" => 3,
        "attribute" | "annotation" => 4,
        "function" | "method" => 5,
        "type" | "constructor" | "namespace" => 6,
        "property" | "variable" | "field" => 7,
        _ => -1,
    }
}

/// JNI: `TreeSitterNative.highlight(languageId, source): int[]` — packed span triples, or null.
///
/// # Safety
/// Called by the JVM with valid `env`/argument references.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_code_util_TreeSitterNative_highlight<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    language_id: JString<'local>,
    source: JString<'local>,
) -> jintArray {
    let null = std::ptr::null_mut();

    let id: String = match env.get_string(&language_id) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };
    let src: String = match env.get_string(&source) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };

    let (language, query_src) = match grammar(&id) {
        Some(g) => g,
        None => return null,
    };

    let mut parser = Parser::new();
    if parser.set_language(&language).is_err() {
        return null;
    }
    let tree = match parser.parse(&src, None) {
        Some(t) => t,
        None => return null,
    };
    let query = match Query::new(&language, query_src) {
        Ok(q) => q,
        Err(_) => return null,
    };

    let names = query.capture_names();
    let bytes = src.as_bytes();
    let mut cursor = QueryCursor::new();
    let mut out: Vec<i32> = Vec::new();

    for m in cursor.matches(&query, tree.root_node(), bytes) {
        for cap in m.captures {
            let name = names[cap.index as usize];
            let kind = kind_for(name);
            if kind < 0 {
                continue;
            }
            let node = cap.node;
            out.push(node.start_byte() as i32);
            out.push(node.end_byte() as i32);
            out.push(kind);
        }
    }

    let arr = match env.new_int_array(out.len() as i32) {
        Ok(a) => a,
        Err(_) => return null,
    };
    if env.set_int_array_region(&arr, 0, &out).is_err() {
        return null;
    }
    arr.into_raw()
}
