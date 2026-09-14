impl<'a> Evaluator<'a> {
    fn sheet(&self) -> &'a Sheet {
        &self.wb.sheets[self.sheet_idx]
    }

    fn evaluate_cell_value(&mut self, row: i32, col: i32) -> Value {
        match self.evaluate_cell_v(row, col) {
            Ok(v) => v,
            Err(e) => Value::Err(e.0),
        }
    }

    /// Resolves a (possibly cross-sheet) cell reference, evaluating on the target sheet.
    fn eval_on(&mut self, name: &Option<String>, row: i32, col: i32) -> R<Value> {
        match name {
            None => self.evaluate_cell_v(row, col),
            Some(n) if *n == self.sheet_name => self.evaluate_cell_v(row, col),
            Some(n) => {
                let idx = match self.wb.name_to_idx.get(n) {
                    Some(i) => *i,
                    None => return Err(ferr("#REF!")),
                };
                let mut ev = Evaluator {
                    wb: self.wb,
                    sheet_idx: idx,
                    sheet_name: n.clone(),
                    cur_row: 0,
                    cur_col: 0,
                    visiting: self.visiting,
                };
                ev.evaluate_cell_v(row, col)
            }
        }
    }

    fn evaluate_cell_v(&mut self, row: i32, col: i32) -> R<Value> {
        let key = format!("{}!{},{}", self.sheet_name, row, col);
        if self.visiting.borrow().contains(&key) {
            return Err(ferr("#REF!"));
        }
        let cell = match cell_at(self.sheet(), row, col) {
            Some(c) => c.clone(),
            None => return Ok(Value::Blank),
        };
        let formula = match &cell.formula {
            Some(f) => f.clone(),
            None => return Ok(raw_cell_value(&cell)),
        };
        self.visiting.borrow_mut().insert(key.clone());
        let save_r = self.cur_row;
        let save_c = self.cur_col;
        self.cur_row = row;
        self.cur_col = col;
        let expr = normalize(&formula);
        let mut parser = Parser::new(&expr);
        let result = parser.parse_expression(self);
        self.visiting.borrow_mut().remove(&key);
        self.cur_row = save_r;
        self.cur_col = save_c;
        result
    }

    // ---- coercions ---------------------------------------------------------

    fn num(&self, v: Value) -> R<f64> {
        match v {
            Value::Num(x) => Ok(x),
            Value::Bool(b) => Ok(if b { 1.0 } else { 0.0 }),
            Value::Str(s) => parse_f64(&s).ok_or_else(|| ferr("#VALUE!")),
            Value::Blank => Ok(0.0),
            Value::Err(c) => Err(EvalError(c)),
        }
    }

    fn num_or_null(&self, v: &Value) -> Option<f64> {
        match v {
            Value::Num(x) => Some(*x),
            Value::Bool(b) => Some(if *b { 1.0 } else { 0.0 }),
            _ => None,
        }
    }

    fn str_of(&self, v: Value) -> R<String> {
        match v {
            Value::Num(x) => Ok(format_number(x)),
            Value::Bool(b) => Ok(if b { "TRUE".into() } else { "FALSE".into() }),
            Value::Str(s) => Ok(s),
            Value::Blank => Ok(String::new()),
            Value::Err(c) => Err(EvalError(c)),
        }
    }

    fn truthy(&self, v: Value) -> R<bool> {
        match v {
            Value::Bool(b) => Ok(b),
            Value::Num(x) => Ok(x != 0.0),
            Value::Str(s) => {
                if s.eq_ignore_ascii_case("TRUE") {
                    Ok(true)
                } else if s.eq_ignore_ascii_case("FALSE") {
                    Ok(false)
                } else {
                    match parse_f64(&s) {
                        Some(x) => Ok(x != 0.0),
                        None => Err(ferr("#VALUE!")),
                    }
                }
            }
            Value::Blank => Ok(false),
            Value::Err(c) => Err(EvalError(c)),
        }
    }

    // ---- range / arg helpers ----------------------------------------------

    fn range_values(
        &mut self,
        r1: i32,
        c1: i32,
        r2: i32,
        c2: i32,
        sheet_ref: &Option<String>,
    ) -> R<Vec<Value>> {
        let mut out = Vec::new();
        let target_idx = match sheet_ref {
            None => self.sheet_idx,
            Some(n) if *n == self.sheet_name => self.sheet_idx,
            Some(n) => *self.wb.name_to_idx.get(n).unwrap_or(&self.sheet_idx),
        };
        for r in r1.min(r2)..=r1.max(r2) {
            for c in c1.min(c2)..=c1.max(c2) {
                if let Some(cell) = cell_at(&self.wb.sheets[target_idx], r, c) {
                    if cell.is_covered {
                        continue;
                    }
                }
                out.push(self.eval_on(sheet_ref, r, c)?);
            }
        }
        Ok(out)
    }

    fn arg_values(&mut self, arg: &Arg) -> R<Vec<Value>> {
        match arg {
            Arg::Scalar(v) => Ok(vec![v.clone()]),
            Arg::RangeRef {
                r1,
                c1,
                r2,
                c2,
                sheet,
            } => self.range_values(*r1, *c1, *r2, *c2, sheet),
        }
    }

    fn cell_at_ref(&self, sheet_ref: &Option<String>, r: i32, c: i32) -> Option<Cell> {
        let idx = match sheet_ref {
            None => self.sheet_idx,
            Some(n) if *n == self.sheet_name => self.sheet_idx,
            Some(n) => *self.wb.name_to_idx.get(n).unwrap_or(&self.sheet_idx),
        };
        cell_at(&self.wb.sheets[idx], r, c).cloned()
    }

    // ---- thunk accessors ---------------------------------------------------

    fn t_arg(&mut self, t: &ArgThunk) -> R<Arg> {
        let mut p = Parser::new(&t.text);
        p.parse_arg_top(self)
    }

    fn t_value(&mut self, t: &ArgThunk) -> R<Value> {
        let a = self.t_arg(t)?;
        match a {
            Arg::Scalar(v) => Ok(v),
            Arg::RangeRef { r1, c1, sheet, .. } => self.eval_on(&sheet, r1, c1),
        }
    }

    fn t_values(&mut self, t: &ArgThunk) -> R<Vec<Value>> {
        let a = self.t_arg(t)?;
        self.arg_values(&a)
    }

    fn t_ref(&mut self, t: &ArgThunk) -> Option<Ref> {
        let mut p = Parser::new(&t.text);
        p.parse_ref_top()
    }

    fn t_num(&mut self, t: &ArgThunk) -> R<f64> {
        let v = self.t_value(t)?;
        self.num(v)
    }

    fn t_truthy(&mut self, t: &ArgThunk) -> R<bool> {
        let v = self.t_value(t)?;
        self.truthy(v)
    }

    fn t_str(&mut self, t: &ArgThunk) -> R<String> {
        let v = self.t_value(t)?;
        self.str_of(v)
    }

    fn all_vals(&mut self, a: &[ArgThunk]) -> R<Vec<Value>> {
        let mut out = Vec::new();
        for t in a {
            out.extend(self.t_values(t)?);
        }
        Ok(out)
    }

    fn all_nums(&mut self, a: &[ArgThunk]) -> R<Vec<f64>> {
        let vals = self.all_vals(a)?;
        Ok(vals.iter().filter_map(|v| self.num_or_null(v)).collect())
    }

    // ---- comparison --------------------------------------------------------

    fn cmp_num(&self, v: &Value) -> R<Option<f64>> {
        match v {
            Value::Num(x) => Ok(Some(*x)),
            Value::Bool(b) => Ok(Some(if *b { 1.0 } else { 0.0 })),
            Value::Blank => Ok(Some(0.0)),
            Value::Err(c) => Err(EvalError(c.clone())),
            Value::Str(_) => Ok(None),
        }
    }

    fn compare_op(&self, op: &str, l: &Value, r: &Value) -> R<bool> {
        let ln = self.cmp_num(l)?;
        let rn = self.cmp_num(r)?;
        if let (Some(a), Some(b)) = (ln, rn) {
            return Ok(match op {
                "<" => a < b,
                "<=" => a <= b,
                ">" => a > b,
                ">=" => a >= b,
                "=" => a == b,
                "<>" => a != b,
                _ => false,
            });
        }
        let ls = self.str_of(l.clone())?;
        let rs = self.str_of(r.clone())?;
        let cmp = cmp_ignore_case(&ls, &rs);
        Ok(match op {
            "<" => cmp < 0,
            "<=" => cmp <= 0,
            ">" => cmp > 0,
            ">=" => cmp >= 0,
            "=" => cmp == 0,
            "<>" => cmp != 0,
            _ => false,
        })
    }

    // ---- criteria (SUMIF/COUNTIF/...) -------------------------------------

    fn matches_criteria(&self, v: &Value, criteria: &Value) -> R<bool> {
        let crit = self.str_of(criteria.clone())?;
        let mut op = "=";
        let mut rest = crit.trim().to_string();
        for o in ["<=", ">=", "<>", "<", ">", "="] {
            if rest.starts_with(o) {
                op = o;
                rest = rest[o.len()..].trim().to_string();
                break;
            }
        }
        let rest_num = parse_f64(&rest);
        let v_num = self.num_or_null(v);
        if let (Some(rn), Some(vn)) = (rest_num, v_num) {
            return Ok(match op {
                "<" => vn < rn,
                "<=" => vn <= rn,
                ">" => vn > rn,
                ">=" => vn >= rn,
                "<>" => vn != rn,
                _ => vn == rn,
            });
        }
        let v_str = if *v == Value::Blank {
            String::new()
        } else {
            self.str_of(v.clone())?
        };
        Ok(match op {
            "<>" => !wildcard_equals(&v_str, &rest),
            "=" => wildcard_equals(&v_str, &rest),
            _ => false,
        })
    }
}

fn raw_cell_value(cell: &Cell) -> Value {
    if let Some(n) = cell.number_value {
        return Value::Num(n);
    }
    let t = &cell.text;
    if t.is_empty() {
        return Value::Blank;
    }
    if let Some(n) = parse_f64(t) {
        return Value::Num(n);
    }
    if t.eq_ignore_ascii_case("TRUE") {
        return Value::Bool(true);
    }
    if t.eq_ignore_ascii_case("FALSE") {
        return Value::Bool(false);
    }
    Value::Str(t.clone())
}

fn normalize(formula: &str) -> String {
    let mut f = formula.trim().to_string();
    if let Some(rest) = f.strip_prefix("of:") {
        f = rest.to_string();
    }
    if let Some(rest) = f.strip_prefix('=') {
        f = rest.to_string();
    }
    f
}

/// Case-insensitive comparison mirroring Kotlin String.compareTo(other, ignoreCase=true).
fn cmp_ignore_case(a: &str, b: &str) -> i32 {
    let mut ai = a.chars();
    let mut bi = b.chars();
    loop {
        match (ai.next(), bi.next()) {
            (Some(x), Some(y)) => {
                let xl = x.to_ascii_uppercase();
                let yl = y.to_ascii_uppercase();
                if xl != yl {
                    return (xl as i32) - (yl as i32);
                }
            }
            (None, None) => return 0,
            (None, Some(_)) => return -1,
            (Some(_), None) => return 1,
        }
    }
}

fn wildcard_equals(value: &str, pattern: &str) -> bool {
    if !pattern.contains('*') && !pattern.contains('?') {
        return value.eq_ignore_ascii_case(pattern);
    }
    let v: Vec<char> = value.chars().map(|c| c.to_ascii_uppercase()).collect();
    let p: Vec<char> = pattern.chars().map(|c| c.to_ascii_uppercase()).collect();
    // Iterative wildcard match with '*' and '?'.
    let (mut i, mut j) = (0usize, 0usize);
    let (mut star, mut mark) = (usize::MAX, 0usize);
    while i < v.len() {
        if j < p.len() && (p[j] == '?' || p[j] == v[i]) {
            i += 1;
            j += 1;
        } else if j < p.len() && p[j] == '*' {
            star = j;
            mark = i;
            j += 1;
        } else if star != usize::MAX {
            j = star + 1;
            mark += 1;
            i = mark;
        } else {
            return false;
        }
    }
    while j < p.len() && p[j] == '*' {
        j += 1;
    }
    j == p.len()
}

// ---- column helpers --------------------------------------------------------

fn col_to_index(col: &str) -> i32 {
    let mut n = 0i32;
    for c in col.chars() {
        if c.is_ascii_alphabetic() {
            n = n * 26 + (c.to_ascii_uppercase() as i32 - 'A' as i32 + 1);
        }
    }
    n - 1
}

fn index_to_col(index: i32) -> String {
    if index < 0 {
        return "A".to_string();
    }
    let mut n = index + 1;
    let mut chars = Vec::new();
    while n > 0 {
        let rem = (n - 1) % 26;
        chars.push((b'A' + rem as u8) as char);
        n = (n - 1) / 26;
    }
    chars.iter().rev().collect()
}

// ---- recursive-descent parser ----------------------------------------------

struct Parser {
    s: Vec<char>,
    pos: usize,
}
