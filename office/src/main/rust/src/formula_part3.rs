impl Parser {
    fn new(text: &str) -> Parser {
        Parser {
            s: text.chars().collect(),
            pos: 0,
        }
    }

    fn peek(&self) -> char {
        if self.pos < self.s.len() {
            self.s[self.pos]
        } else {
            '\0'
        }
    }

    fn skip_ws(&mut self) {
        while self.pos < self.s.len() && self.s[self.pos].is_whitespace() {
            self.pos += 1;
        }
    }

    fn match_token(&mut self, token: &str) -> bool {
        self.skip_ws();
        let tc: Vec<char> = token.chars().collect();
        if self.pos + tc.len() <= self.s.len() && self.s[self.pos..self.pos + tc.len()] == tc[..] {
            self.pos += tc.len();
            true
        } else {
            false
        }
    }

    fn substr(&self, start: usize, end: usize) -> String {
        self.s[start..end].iter().collect()
    }

    fn parse_expression(&mut self, ev: &mut Evaluator) -> R<Value> {
        self.parse_comparison(ev)
    }

    fn parse_arg_top(&mut self, ev: &mut Evaluator) -> R<Arg> {
        self.skip_ws();
        if self.peek() == '[' {
            let save = self.pos;
            let r = self.parse_ref_raw();
            self.skip_ws();
            if self.pos >= self.s.len() {
                return if r.is_range {
                    Ok(Arg::RangeRef {
                        r1: r.r1,
                        c1: r.c1,
                        r2: r.r2,
                        c2: r.c2,
                        sheet: r.sheet,
                    })
                } else {
                    Ok(Arg::Scalar(ev.eval_on(&r.sheet, r.r1, r.c1)?))
                };
            }
            self.pos = save;
        }
        Ok(Arg::Scalar(self.parse_expression(ev)?))
    }

    fn parse_ref_top(&mut self) -> Option<Ref> {
        self.skip_ws();
        if self.peek() != '[' {
            return None;
        }
        let r = self.parse_ref_raw();
        self.skip_ws();
        if self.pos >= self.s.len() {
            Some(r)
        } else {
            None
        }
    }

    fn parse_comparison(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut left = self.parse_concat(ev)?;
        loop {
            self.skip_ws();
            let op = if self.match_token("<=") {
                "<="
            } else if self.match_token(">=") {
                ">="
            } else if self.match_token("<>") {
                "<>"
            } else if self.peek() == '<' {
                self.pos += 1;
                "<"
            } else if self.peek() == '>' {
                self.pos += 1;
                ">"
            } else if self.peek() == '=' {
                self.pos += 1;
                "="
            } else {
                return Ok(left);
            };
            let right = self.parse_concat(ev)?;
            left = Value::Bool(ev.compare_op(op, &left, &right)?);
        }
    }

    fn parse_concat(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut v = self.parse_add_sub(ev)?;
        loop {
            self.skip_ws();
            if self.peek() == '&' {
                self.pos += 1;
                let rhs = self.parse_add_sub(ev)?;
                let ls = ev.str_of(v)?;
                let rs = ev.str_of(rhs)?;
                v = Value::Str(ls + &rs);
            } else {
                return Ok(v);
            }
        }
    }

    fn parse_add_sub(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut v = self.parse_mul_div(ev)?;
        loop {
            self.skip_ws();
            match self.peek() {
                '+' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_mul_div(ev)?;
                    let b = ev.num(rhs)?;
                    v = Value::Num(a + b);
                }
                '-' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_mul_div(ev)?;
                    let b = ev.num(rhs)?;
                    v = Value::Num(a - b);
                }
                _ => return Ok(v),
            }
        }
    }

    fn parse_mul_div(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut v = self.parse_power(ev)?;
        loop {
            self.skip_ws();
            match self.peek() {
                '*' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_power(ev)?;
                    let b = ev.num(rhs)?;
                    v = Value::Num(a * b);
                }
                '/' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_power(ev)?;
                    let d = ev.num(rhs)?;
                    if d == 0.0 {
                        return Err(ferr("#DIV/0!"));
                    }
                    v = Value::Num(a / d);
                }
                _ => return Ok(v),
            }
        }
    }

    fn parse_power(&mut self, ev: &mut Evaluator) -> R<Value> {
        let base = self.parse_unary(ev)?;
        self.skip_ws();
        if self.peek() == '^' {
            self.pos += 1;
            let a = ev.num(base)?;
            let rhs = self.parse_unary(ev)?;
            let b = ev.num(rhs)?;
            return Ok(Value::Num(a.powf(b)));
        }
        Ok(base)
    }

    fn parse_unary(&mut self, ev: &mut Evaluator) -> R<Value> {
        self.skip_ws();
        if self.peek() == '-' {
            self.pos += 1;
            let v = self.parse_unary(ev)?;
            return Ok(Value::Num(-ev.num(v)?));
        }
        if self.peek() == '+' {
            self.pos += 1;
            return self.parse_unary(ev);
        }
        self.parse_primary(ev)
    }

    fn parse_primary(&mut self, ev: &mut Evaluator) -> R<Value> {
        self.skip_ws();
        let c = self.peek();
        if c == '(' {
            self.pos += 1;
            let v = self.parse_expression(ev)?;
            self.skip_ws();
            if self.peek() == ')' {
                self.pos += 1;
            }
            Ok(v)
        } else if c == '[' {
            let r = self.parse_ref_raw();
            ev.eval_on(&r.sheet, r.r1, r.c1)
        } else if c == '"' {
            Ok(Value::Str(self.parse_string()))
        } else if c.is_ascii_digit() || c == '.' {
            Ok(Value::Num(self.parse_number()))
        } else if c.is_alphabetic() {
            self.parse_function_or_const(ev)
        } else {
            self.pos += 1;
            Ok(Value::Num(0.0))
        }
    }

    fn parse_number(&mut self) -> f64 {
        let start = self.pos;
        while self.pos < self.s.len() {
            let c = self.s[self.pos];
            if c.is_ascii_digit() || c == '.' || c == 'E' || c == 'e' {
                self.pos += 1;
            } else {
                break;
            }
        }
        let mut value = self.substr(start, self.pos).parse::<f64>().unwrap_or(0.0);
        self.skip_ws();
        if self.peek() == '%' {
            self.pos += 1;
            value /= 100.0;
        }
        value
    }

    fn parse_string(&mut self) -> String {
        self.pos += 1; // opening quote
        let mut sb = String::new();
        while self.pos < self.s.len() {
            let ch = self.s[self.pos];
            if ch == '"' {
                if self.pos + 1 < self.s.len() && self.s[self.pos + 1] == '"' {
                    sb.push('"');
                    self.pos += 2;
                    continue;
                }
                self.pos += 1;
                break;
            }
            sb.push(ch);
            self.pos += 1;
        }
        sb
    }

    fn parse_ref_raw(&mut self) -> Ref {
        self.pos += 1; // '['
        let mut sb = String::new();
        while self.pos < self.s.len() && self.s[self.pos] != ']' {
            sb.push(self.s[self.pos]);
            self.pos += 1;
        }
        if self.pos < self.s.len() {
            self.pos += 1; // ']'
        }
        let raw = sb.replace('$', "");
        let first_endpoint = substring_before(&raw, ":");
        let sheet_ref = {
            let before_dot = substring_before_last(&first_endpoint, '.', "");
            let s = before_dot.trim_start_matches('.').trim().trim_matches('\'');
            if s.is_empty() {
                None
            } else {
                Some(s.to_string())
            }
        };
        if raw.contains(':') {
            let mut parts = raw.splitn(2, ':');
            let a = parts.next().unwrap_or("");
            let b = parts.next().unwrap_or("");
            let (r1, c1) = parse_cell_coords(a);
            let (r2, c2) = parse_cell_coords(b);
            Ref {
                r1,
                c1,
                r2,
                c2,
                is_range: true,
                sheet: sheet_ref,
            }
        } else {
            let (r, c) = parse_cell_coords(&raw);
            Ref {
                r1: r,
                c1: c,
                r2: r,
                c2: c,
                is_range: false,
                sheet: sheet_ref,
            }
        }
    }

    fn parse_function_or_const(&mut self, ev: &mut Evaluator) -> R<Value> {
        let start = self.pos;
        while self.pos < self.s.len() {
            let c = self.s[self.pos];
            if c.is_alphanumeric() || c == '_' || c == '.' {
                self.pos += 1;
            } else {
                break;
            }
        }
        let mut name = self.substr(start, self.pos).to_uppercase();
        if let Some(rest) = name.strip_prefix("ORG.OPENOFFICE.") {
            name = rest.to_string();
        }
        if let Some(rest) = name.strip_prefix("COM.MICROSOFT.") {
            name = rest.to_string();
        }
        self.skip_ws();
        if self.peek() != '(' {
            return match name.as_str() {
                "TRUE" => Ok(Value::Bool(true)),
                "FALSE" => Ok(Value::Bool(false)),
                "PI" => Ok(Value::Num(std::f64::consts::PI)),
                _ => Err(ferr("#NAME?")),
            };
        }
        self.pos += 1; // '('
        let arg_texts = self.split_args();
        let thunks: Vec<ArgThunk> = arg_texts.into_iter().map(|t| ArgThunk { text: t }).collect();
        ev.apply_function(&name, &thunks)
    }

    fn split_args(&mut self) -> Vec<String> {
        let mut parts: Vec<String> = Vec::new();
        let mut cur = String::new();
        let mut depth = 0i32;
        let mut in_str = false;
        while self.pos < self.s.len() {
            let c = self.s[self.pos];
            if in_str {
                cur.push(c);
                if c == '"' {
                    in_str = false;
                }
                self.pos += 1;
                continue;
            }
            match c {
                '"' => {
                    in_str = true;
                    cur.push(c);
                    self.pos += 1;
                }
                '(' | '[' => {
                    depth += 1;
                    cur.push(c);
                    self.pos += 1;
                }
                ']' => {
                    depth -= 1;
                    cur.push(c);
                    self.pos += 1;
                }
                ')' => {
                    if depth == 0 {
                        self.pos += 1;
                        break;
                    } else {
                        depth -= 1;
                        cur.push(c);
                        self.pos += 1;
                    }
                }
                ',' | ';' if depth == 0 => {
                    parts.push(cur.clone());
                    cur.clear();
                    self.pos += 1;
                }
                _ => {
                    cur.push(c);
                    self.pos += 1;
                }
            }
        }
        if !cur.trim().is_empty() || !parts.is_empty() {
            parts.push(cur);
        }
        parts
    }
}

fn parse_cell_coords(token: &str) -> (i32, i32) {
    let t = substring_after_last(token, '.');
    // split leading letters then digits: ([A-Za-z]+)(\d+)
    let chars: Vec<char> = t.chars().collect();
    let mut i = 0;
    // skip to first letter
    while i < chars.len() && !chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let letter_start = i;
    while i < chars.len() && chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let letters: String = chars[letter_start..i].iter().collect();
    let digit_start = i;
    while i < chars.len() && chars[i].is_ascii_digit() {
        i += 1;
    }
    let digits: String = chars[digit_start..i].iter().collect();
    if letters.is_empty() || digits.is_empty() {
        return (0, 0);
    }
    let col = col_to_index(&letters);
    let row = digits.parse::<i32>().unwrap_or(1) - 1;
    (row, col)
}

// ---- Kotlin-style substring helpers ----------------------------------------

fn substring_before(s: &str, delim: &str) -> String {
    match s.find(delim) {
        Some(i) => s[..i].to_string(),
        None => s.to_string(),
    }
}

fn substring_before_last(s: &str, delim: char, missing: &str) -> String {
    match s.rfind(delim) {
        Some(i) => s[..i].to_string(),
        None => missing.to_string(),
    }
}
