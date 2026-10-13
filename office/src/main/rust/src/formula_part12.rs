// ---- dynamic-array functions -------------------------------------------------
// XLOOKUP/FILTER/SORT/SORTBY/UNIQUE/SEQUENCE/LET implementations. Included into
// the formula module (see formula.rs); dispatched from apply_function4 in part8.

impl<'a> Evaluator<'a> {
    fn filter_fn(&mut self, a: &[ArgThunk]) -> R<Value> {
        let vals = self.t_values(&a[0])?;
        let include = self.include_values(&a[1])?;
        if vals.len() != include.len() {
            return Err(ferr("#CALC!"));
        }
        let mut out = Vec::new();
        for (v, inc) in vals.into_iter().zip(include) {
            if self.truthy(inc)? {
                out.push(v);
            }
        }
        if out.is_empty() {
            if a.len() > 2 && !a[2].text.trim().is_empty() {
                Ok(self.t_value(&a[2])?)
            } else {
                Err(ferr("#CALC!"))
            }
        } else {
            Ok(Value::Arr(out))
        }
    }

    fn sort_fn(&mut self, a: &[ArgThunk]) -> R<Value> {
        let mut vals = self.t_values(&a[0])?;
        let sort_index = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 1 };
        let order = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 1 };
        let _by_col = a.len() > 3 && self.t_truthy(&a[3])?;
        // Flattened vectors carry no 2-D shape: only a single vector sorts.
        if sort_index != 1 && sort_index != -1 {
            return Err(ferr("#VALUE!"));
        }
        if _by_col {
            return Err(ferr("#VALUE!"));
        }
        let desc = order == -1;
        vals.sort_by(|x, y| cmp_values(x, y));
        if desc {
            vals.reverse();
        }
        Ok(Value::Arr(vals))
    }

    fn sortby_fn(&mut self, a: &[ArgThunk]) -> R<Value> {
        let vals = self.t_values(&a[0])?;
        if a.len() < 2 {
            return Err(ferr("#VALUE!"));
        }
        let keys = self.t_values(&a[1])?;
        let order = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 1 };
        if vals.len() != keys.len() {
            return Err(ferr("#VALUE!"));
        }
        let mut paired: Vec<(Value, Value)> =
            keys.into_iter().zip(vals.into_iter()).collect();
        paired.sort_by(|x, y| cmp_values(&x.0, &y.0));
        if order == -1 {
            paired.reverse();
        }
        Ok(Value::Arr(paired.into_iter().map(|(_, v)| v).collect()))
    }

    fn unique_fn(&mut self, a: &[ArgThunk]) -> R<Value> {
        let vals = self.t_values(&a[0])?;
        let by_col = a.len() > 1 && self.t_truthy(&a[1])?;
        if by_col {
            return Err(ferr("#VALUE!"));
        }
        let exactly_once = a.len() > 2 && self.t_truthy(&a[2])?;
        let mut out = Vec::new();
        for v in &vals {
            if !out.contains(v) {
                let count = vals.iter().filter(|x| *x == v).count();
                if !exactly_once || count == 1 {
                    out.push(v.clone());
                }
            }
        }
        if out.is_empty() {
            Err(ferr("#CALC!"))
        } else {
            Ok(Value::Arr(out))
        }
    }

    fn sequence_fn(&mut self, a: &[ArgThunk]) -> R<Value> {
        let rows = self.t_num(&a[0])? as i32;
        let cols = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 1 };
        let start = if a.len() > 2 { self.t_num(&a[2])? } else { 1.0 };
        let step = if a.len() > 3 { self.t_num(&a[3])? } else { 1.0 };
        if rows < 1 || cols < 1 {
            return Err(ferr("#VALUE!"));
        }
        if rows as i64 * cols as i64 > 1_000_000 {
            return Err(ferr("#CALC!"));
        }
        let mut out = Vec::with_capacity((rows * cols) as usize);
        for r in 0..rows {
            for c in 0..cols {
                out.push(Value::Num(start + (r * cols + c) as f64 * step));
            }
        }
        Ok(Value::Arr(out))
    }

    /// XLOOKUP(lookup, lookup_array, return_array, [if_not_found], [match_mode],
    /// [search_mode]). match_mode: 0 exact (default), -1 exact-or-next-smaller,
    /// 1 exact-or-next-larger, 2 wildcard. search_mode: 1 first-to-last
    /// (default), -1 last-to-first, 2 binary ascending, -2 binary descending.
    fn xlookup(&mut self, a: &[ArgThunk]) -> R<Value> {
        if a.len() < 3 {
        return Err(ferr("#VALUE!"));
        }
        let key = self.t_value(&a[0])?;
        let lv = self.t_values(&a[1])?;
        let rv = self.t_values(&a[2])?;
        if lv.is_empty() || rv.is_empty() {
        return Err(ferr("#N/A"));
        }
        let match_mode = if a.len() > 4 { self.t_num(&a[4])? as i32 } else { 0 };
        let search_mode = if a.len() > 5 { self.t_num(&a[5])? as i32 } else { 1 };
        let missing = |me: &mut Self| -> R<Value> {
        // An empty 4th arg counts as omitted (Excel `XLOOKUP(..;;..)`).
        if a.len() > 3 && !a[3].text.trim().is_empty() {
            me.t_value(&a[3])
        } else {
            Err(ferr("#N/A"))
        }
        };
        let at = |idx: usize| -> R<Value> {
        rv.get(idx).cloned().ok_or_else(|| ferr("#VALUE!"))
        };
        let order: Vec<usize> = match search_mode {
        -1 => (0..lv.len()).rev().collect(),
        _ => (0..lv.len()).collect(),
        };
        match match_mode {
        2 => {
            // Wildcard: text pattern match, else exact equality.
            for i in order {
                if wildcard_match(&key, &lv[i])? {
                    return at(i);
                }
            }
            missing(self)
        }
        -1 | 1 => {
            // Exact first, else next smaller (-1) / larger (1).
            for i in order.clone() {
                if self.compare_op("=", &lv[i], &key)? {
                    return at(i);
                }
            }
            let mut best: Option<usize> = None;
            for i in order {
                let is_candidate = if match_mode == -1 {
                    self.compare_op("<", &lv[i], &key)?
                        || self.compare_op("=", &lv[i], &key)?
                } else {
                    self.compare_op(">", &lv[i], &key)?
                        || self.compare_op("=", &lv[i], &key)?
                };
                if !is_candidate {
                    continue;
                }
                best = Some(match best {
                    None => i,
                    Some(b) => {
                        let better = if match_mode == -1 {
                            self.compare_op(">", &lv[i], &lv[b])?
                        } else {
                            self.compare_op("<", &lv[i], &lv[b])?
                        };
                        if better { i } else { b }
                    }
                });
            }
            match best {
                Some(b) => at(b),
                None => missing(self),
            }
        }
        _ => {
            if search_mode == 2 || search_mode == -2 {
                // Binary search over a sorted array.
                let asc = search_mode == 2;
                let mut lo = 0i32;
                let mut hi = lv.len() as i32 - 1;
                while lo <= hi {
                    let mid = (lo + hi) / 2;
                    let ord = cmp_values(&lv[mid as usize], &key);
                    let eq = ord == std::cmp::Ordering::Equal;
                    if eq {
                        return at(mid as usize);
                    }
                    let go_right = if asc {
                        ord == std::cmp::Ordering::Less
                    } else {
                        ord == std::cmp::Ordering::Greater
                    };
                    if go_right {
                        lo = mid + 1;
                    } else {
                        hi = mid - 1;
                    }
                }
                missing(self)
            } else {
                for i in order {
                    if self.compare_op("=", &lv[i], &key)? {
                        return at(i);
                    }
                }
                missing(self)
            }
        }
        }
    }

    /// FILTER's include argument: normally a range of booleans, but users write
    /// `FILTER(A:A;B:B>15)` — a comparison over a range. The scalar parser would
    /// collapse `[.B1:.B3]` to its top-left cell, so detect a top-level comparison
    /// with a range operand here and evaluate it elementwise (scalar sides
    /// broadcast). Anything else falls back to the ordinary value list.
    fn include_values(&mut self, t: &ArgThunk) -> R<Vec<Value>> {
        if let Some((op, left, right)) = split_top_compare(&t.text) {
        let l_ref = is_bare_ref_text(&left);
        let r_ref = is_bare_ref_text(&right);
        if l_ref || r_ref {
            let lt = ArgThunk { text: left };
            let rt = ArgThunk { text: right };
            let mut lv = self.t_values(&lt)?;
            let mut rv = self.t_values(&rt)?;
            if lv.len() == 1 && rv.len() > 1 {
                lv = vec![lv[0].clone(); rv.len()];
            } else if rv.len() == 1 && lv.len() > 1 {
                rv = vec![rv[0].clone(); lv.len()];
            }
            if lv.len() != rv.len() {
                return Err(ferr("#CALC!"));
            }
            let mut out = Vec::with_capacity(lv.len());
            for (l, r) in lv.into_iter().zip(rv) {
                out.push(Value::Bool(self.compare_op(&op, &l, &r)?));
            }
            return Ok(out);
        }
        }
        self.t_values(t)
    }

    /// LET(name1, value1, …, calc): binds scalar names, then evaluates calc with
    /// token-boundary substitution (string literals and [refs] untouched).
    fn let_bind(&mut self, a: &[ArgThunk]) -> R<Value> {
        if a.len() < 3 || a.len() % 2 == 0 {
        return Err(ferr("#VALUE!"));
        }
        let mut bindings: Vec<(String, String)> = Vec::new();
        let mut i = 0;
        while i + 1 < a.len() - 1 {
        let raw_name = a[i].text.trim().to_uppercase();
        if !is_let_name(&raw_name) {
            return Err(ferr("#NAME?"));
        }
        // Eager, like Excel: a binding error propagates even if unused.
        let v = self.t_value(&a[i + 1])?;
        bindings.push((raw_name, value_literal(&v)?));
        i += 2;
        }
        let calc = substitute_let_names(&a[a.len() - 1].text, &bindings);
        let mut p = Parser::new(&calc);
        p.parse_expression(self)
    }
}
