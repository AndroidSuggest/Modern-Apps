//! JSON accessors with nanojson semantics.
//!
//! The upstream extractor (NewPipe, and PipePipe after it) is written against nanojson, where
//! reading a missing key yields an *empty* container rather than an error:
//!
//! | nanojson                | on missing / wrong type |
//! |-------------------------|-------------------------|
//! | `getObject(k)`          | empty object            |
//! | `getArray(k)`           | empty array             |
//! | `getString(k)`          | null                    |
//! | `getInt`/`getLong`      | 0                       |
//! | `getBoolean(k)`         | false                   |
//!
//! That is not incidental — InnerTube responses legitimately omit keys, and the extractors walk
//! long `a.b.c.d` chains that must bottom out quietly. A port that makes absence an error (or a
//! panic) turns ordinary responses into hard failures. The Kotlin port learned this the hard way:
//! its shims returned null, ~546 call sites papered over it with `!!`, and every uncorrected
//! search threw a `NullPointerException` out of `getSearchSuggestion`.
//!
//! So the accessors here never fail. [`obj`](JsonExt::obj) returns `&Value` and falls back to
//! `Value::Null`, which is itself traversable — `v.obj("a").obj("b").str("c")` on an empty input
//! is simply `None`, with no intermediate checks. There is deliberately no fallible variant to
//! reach for.

use serde_json::Value;

/// Traversable stand-in for an absent object/array, so chains never need a branch.
static NULL: Value = Value::Null;

/// Empty slice returned for a missing array, so `for x in v.arr("k")` is always valid.
static EMPTY: &[Value] = &[];

pub trait JsonExt {
    /// Object at `key`, or a null value that can be traversed further. Never fails.
    fn obj(&self, key: &str) -> &Value;

    /// Array at `key` as a slice, empty when missing or the wrong type.
    fn arr(&self, key: &str) -> &[Value];

    /// Element at `index`, or a traversable null. Works on any value; non-arrays yield null.
    fn at(&self, index: usize) -> &Value;

    /// String at `key`. `None` when missing or not a string — matching nanojson.
    fn str(&self, key: &str) -> Option<&str>;

    /// String at `key`, or `default`.
    fn str_or<'a>(&'a self, key: &str, default: &'a str) -> &'a str;

    /// Integer at `key`, `0` when missing. Also parses numbers that arrive as strings,
    /// which InnerTube does for several fields (`audioSampleRate`, `contentLength`, …).
    fn int(&self, key: &str) -> i64;

    /// Boolean at `key`, `false` when missing.
    fn bool(&self, key: &str) -> bool;

    /// Whether `key` is present at all, for the `has(...)`-style branches upstream uses.
    fn has(&self, key: &str) -> bool;

    /// True when this is an absent/empty object, mirroring nanojson's `isEmpty()` checks.
    fn is_blank(&self) -> bool;
}

impl JsonExt for Value {
    fn obj(&self, key: &str) -> &Value {
        self.get(key).unwrap_or(&NULL)
    }

    fn arr(&self, key: &str) -> &[Value] {
        self.get(key).and_then(Value::as_array).map_or(EMPTY, |v| v.as_slice())
    }

    fn at(&self, index: usize) -> &Value {
        self.as_array().and_then(|a| a.get(index)).unwrap_or(&NULL)
    }

    fn str(&self, key: &str) -> Option<&str> {
        self.get(key).and_then(Value::as_str)
    }

    fn str_or<'a>(&'a self, key: &str, default: &'a str) -> &'a str {
        self.str(key).unwrap_or(default)
    }

    fn int(&self, key: &str) -> i64 {
        match self.get(key) {
            Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
            // InnerTube returns several numeric fields as strings.
            Some(Value::String(s)) => s.parse().unwrap_or(0),
            _ => 0,
        }
    }

    fn bool(&self, key: &str) -> bool {
        self.get(key).and_then(Value::as_bool).unwrap_or(false)
    }

    fn has(&self, key: &str) -> bool {
        self.get(key).is_some()
    }

    fn is_blank(&self) -> bool {
        match self {
            Value::Null => true,
            Value::Object(m) => m.is_empty(),
            Value::Array(a) => a.is_empty(),
            _ => false,
        }
    }
}

/// Elements of the array at `key`, skipping non-objects. Mirrors nanojson's
/// `getArray(k).streamAsJsonObjects()`, which upstream uses constantly.
pub fn objects<'a>(value: &'a Value, key: &str) -> impl Iterator<Item = &'a Value> {
    value.arr(key).iter().filter(|v| v.is_object())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn missing_keys_traverse_instead_of_failing() {
        let v = json!({});
        // The chain that threw a NullPointerException in the Kotlin port.
        assert_eq!(v.obj("contents").obj("primaryContents").at(0).str("token"), None);
    }

    #[test]
    fn nanojson_defaults() {
        let v = json!({ "s": "x", "n": 5, "sn": "7", "b": true, "o": {"k": 1}, "a": [1, 2] });
        assert_eq!(v.int("missing"), 0);
        assert_eq!(v.bool("missing"), false);
        assert!(v.arr("missing").is_empty());
        assert!(v.obj("missing").is_blank());
        assert_eq!(v.str("missing"), None);
        assert_eq!(v.str_or("missing", "d"), "d");
        // present values
        assert_eq!(v.int("n"), 5);
        assert_eq!(v.int("sn"), 7, "numeric strings parse, as InnerTube sends them");
        assert_eq!(v.str("s"), Some("x"));
        assert!(v.bool("b"));
        assert_eq!(v.arr("a").len(), 2);
        assert!(!v.obj("o").is_blank());
    }

    #[test]
    fn wrong_type_falls_back_rather_than_erroring() {
        let v = json!({ "o": "not-an-object", "a": 3, "n": "abc" });
        assert!(v.obj("o").obj("deeper").is_blank());
        assert!(v.arr("a").is_empty());
        assert_eq!(v.int("n"), 0);
    }

    #[test]
    fn index_out_of_range_is_null_not_panic() {
        let v = json!({ "a": [{ "x": 1 }] });
        assert_eq!(v.obj("a").at(5).str("x"), None);
        assert_eq!(v.obj("a").at(0).int("x"), 1);
    }
}
